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
}
