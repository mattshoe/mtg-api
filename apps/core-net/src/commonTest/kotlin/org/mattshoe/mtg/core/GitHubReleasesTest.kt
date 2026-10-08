package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubReleasesTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun github(body: String, status: HttpStatusCode = HttpStatusCode.OK) = GitHubReleases(
        HttpClient(MockEngine { req ->
            seen += req
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    @Test
    fun itAsksThisRepositorysReleasesAndReadsThemNewestFirst() = runTest {
        val found = github(
            """[{"tag_name":"android-v2.1.0-1","published_at":"2026-01-01T00:00:00Z","body":"old"},
                {"tag_name":"android-v2.1.0-2","published_at":"2026-01-02T00:00:00Z","body":"new"}]""",
        ).releases()
        assertEquals(listOf("new", "old"), found.map { it.note })
        val url = seen.single().url.toString()
        assertTrue(url.startsWith("https://api.github.com/repos/mattshoe/mtg-api/releases"), url)
    }

    /**
     * GitHub's refusals are a JSON object with a message, not an array.
     * Read as "no releases" that would look like nothing ever shipped.
     */
    @Test
    fun aRefusalIsAnErrorThatSaysWhyNotAnEmptyList() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            github("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden).releases()
        }
        assertEquals("API rate limit exceeded", e.message)
    }

    private fun routed(vararg answers: Pair<String, String>, status: HttpStatusCode = HttpStatusCode.OK) = GitHubReleases(
        HttpClient(MockEngine { req ->
            seen += req
            val body = answers.first { req.url.toString().contains(it.first) }.second
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    @Test
    fun tasksAskForRequestPullRequestsBranchesAndTheDoneFolder() = runTest {
        val found = routed(
            "/pulls" to """[{"title":"Shipped","state":"closed","head":{"ref":"request/shipped-1234567"},
                "merged_at":"2026-01-02T00:00:00Z","closed_at":"2026-01-02T00:00:00Z"}]""",
            "/git/matching-refs/heads/request/" to """[{"ref":"refs/heads/request/shipped-1234567"},
                {"ref":"refs/heads/request/going-7654321"}]""",
            "/contents/requests/done" to """[{"name":"shipped.md","type":"file"}]""",
        ).tasks()
        assertEquals(listOf("Shipped" to "done", "going" to "building"), found.map { it.title to it.status.word })
        val urls = seen.map { it.url.toString() }
        assertTrue(urls.any { it.startsWith("https://api.github.com/repos/mattshoe/mtg-api/pulls?state=all") }, urls.toString())
        assertTrue(urls.any { it == "https://api.github.com/repos/mattshoe/mtg-api/git/matching-refs/heads/request/" }, urls.toString())
        assertTrue(urls.any { it == "https://api.github.com/repos/mattshoe/mtg-api/contents/requests/done" }, urls.toString())
    }

    @Test
    fun aRefusedTaskLoadIsAnErrorThatSaysWhy() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            routed("" to """{"message":"API rate limit exceeded"}""", status = HttpStatusCode.Forbidden).tasks()
        }
        assertEquals("API rate limit exceeded", e.message)
    }
}
