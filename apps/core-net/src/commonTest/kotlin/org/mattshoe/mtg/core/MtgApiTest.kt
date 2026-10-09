package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API client, against a mock engine, on every platform at once.
 *
 * What it pins down is the contract with the Worker: the request shape,
 * the bearer token, `dry_run` saying what it was told to say, and the
 * server's own words surviving a refusal instead of being flattened into
 * a status code.
 */
class MtgApiTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String): MtgApi {
        val engine = MockEngine { request ->
            seen += request
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi("https://example.invalid", client)
    }

    private suspend fun HttpRequestData.bodyText(): String {
        val content = body as io.ktor.http.content.OutgoingContent.ByteArrayContent
        return content.bytes().decodeToString()
    }

    // Three tests over `unlock(password)` stood here: the password
    // going up with no token on it, a tokenless reply being a refusal,
    // and the server's own words surviving a wrong one. The client has
    // no `unlock` — the apps have no password — and the nightly
    // scripts talk to the Worker in Python.

    @Test
    fun aPreviewSaysDryRunAndCarriesTheToken() = runTest {
        val out = api(
            body = """{"applied":false,"dry_run":true,"resolved":2,"failed":0,
                "changes":[["Lightning Bolt","2X2","117","nonfoil",0,4]],"errors":[],"notes":[]}""",
        ).cards("0.abc", Direction.ADD, "e7de0cb1", "4 Lightning Bolt", dryRun = true)

        val req = seen.single()
        assertEquals("/cards/add", req.url.encodedPath)
        assertEquals("Bearer 0.abc", req.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(req.bodyText()).jsonObject
        assertEquals("e7de0cb1", sent["collection"]!!.jsonPrimitive.content)
        assertEquals("true", sent["dry_run"]!!.jsonPrimitive.content)

        assertTrue(out.dryRun)
        assertEquals(1, out.changes.size)
        out.changes[0].let {
            assertEquals("Lightning Bolt", it.name)
            assertEquals("2X2", it.set)
            assertEquals("117", it.collectorNumber)
            assertEquals(0, it.before)
            assertEquals(4, it.after)
            assertTrue(it.isIncrease)
        }
    }

    @Test
    fun removeGoesToTheRemoveEndpoint() = runTest {
        api(body = """{"applied":true,"dry_run":false,"resolved":1,"changes":[],"errors":[]}""")
            .cards("t", Direction.REMOVE, "bprh3d2s", "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("/cards/remove", seen.single().url.encodedPath)
        assertEquals("bprh3d2s", sent["collection"]!!.jsonPrimitive.content)
        assertEquals("false", sent["dry_run"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsAndNotesComeBackWhole() = runTest {
        val out = api(
            body = """{"applied":true,"resolved":1,"failed":2,"changes":[],
                "errors":["no card named Biterblosom","line 4 unreadable"],
                "notes":["rulings skipped on a big import"]}""",
        ).cards("t", Direction.ADD, "e7de0cb1", "x", dryRun = false)
        assertEquals(2, out.failed)
        assertEquals("no card named Biterblosom", out.errors[0])
        assertEquals(1, out.notes.size)
    }

    @Test
    fun anExpiredTokenIsReportedNotSwallowed() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.Unauthorized, """{"error":"admin session expired — unlock again"}""")
                .cards("stale", Direction.ADD, "e7de0cb1", "1 Sol Ring", dryRun = true)
        }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test
    fun aReplyThatIsNotJsonSaysSoRatherThanCrashing() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.InternalServerError, "<html>upstream is having a moment</html>")
                .cards("t", Direction.ADD, "e7de0cb1", "1 Sol Ring", dryRun = true)
        }
        assertTrue(e.message!!.contains("500") || e.message!!.contains("could not be read"))
    }

    @Test
    fun aListFullOfCommasAndApostrophesSurvivesTheJson() = runTest {
        val list = "1 Kardur, Doomscourge\n1 Ambition's Cost\n\"quoted\",2"
        api(body = """{"applied":false,"dry_run":true,"resolved":3,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "e7de0cb1", list, dryRun = true)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals(list, sent["list"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWholeCollectionExportGoesUpIntact() = runTest {
        val list = (1..4000).joinToString("\n") { "1 Card Number $it" }
        api(body = """{"applied":false,"dry_run":true,"resolved":4000,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "e7de0cb1", list, dryRun = true)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals(list, sent["list"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWriteGoesToWhicheverCollectionItWasHanded() = runTest {
        // It used to take one of two names, which is every collection
        // there would ever be. A collection is whatever key it is
        // handed, and the server is the one that decides whether this
        // session may write to it.
        api(body = """{"applied":true,"dry_run":false,"resolved":1,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "u1am0g42", "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("u1am0g42", sent["collection"]!!.jsonPrimitive.content)
        assertNull(sent["owner"], "the retired owner field still goes up")
    }

    @Test
    fun aNewDeckLandsInWhicheverCollectionItWasHanded() = runTest {
        api(body = """{"created":true,"dry_run":false,"name":"Burn","key":"b7rn4k2x","missing":[]}""")
            .createDeck("t", "Burn", "modern", "u1am0g42", null, "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("u1am0g42", sent["collection"]!!.jsonPrimitive.content)
        assertNull(sent["owner"], "the retired owner field still goes up")
    }

    @Test
    fun aNewTaskGoesUpWithItsFilesAndComesBackAsAKey() = runTest {
        val key = api(body = """{"key":"ab12cd34","files":1}""").sendTask(
            "0.abc",
            NewTask().titled("Bigger buttons").described("Too small")
                .attach(listOf(TaskFile("shot.png", "image/png", 5, "aGVsbG8="))),
        )
        assertEquals("ab12cd34", key)
        val req = seen.single()
        assertEquals("/tasks", req.url.encodedPath)
        assertEquals("Bearer 0.abc", req.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(req.bodyText()).jsonObject
        assertEquals("Bigger buttons", sent["title"]!!.jsonPrimitive.content)
        assertEquals("Too small", sent["details"]!!.jsonPrimitive.content)
        val file = (sent["files"] as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals("shot.png", file["name"]!!.jsonPrimitive.content)
        assertEquals("image/png", file["type"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", file["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusedTaskSaysWhatTheServerSaid() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.BadRequest, """{"error":"shot.png is over 1.5 MB"}""")
                .sendTask("0.abc", NewTask().titled("x").described("y"))
        }
        assertEquals("shot.png is over 1.5 MB", e.message)
    }

    /**
     * Matt: "The system is just looking at fucking branch names?!?!"
     * Every task and where it is comes from the Worker, which keeps it in
     * D1 — not from GitHub, which the phone asked unauthenticated at
     * sixty an hour until both panels failed at once.
     */
    @Test
    fun tasksComeFromTheWorkerWithTheSession() = runTest {
        val found = api(
            body = """{"tasks":[{"key":"ab12cd34","name":"bigger-buttons","title":"Bigger buttons",
                "status":"in progress","pr":null,"created_at":"2026-10-08T09:00:00.000Z",
                "started_at":"2026-10-08T09:15:00.000Z","finished_at":null}]}""",
        ).tasks("0.abc")
        assertEquals(listOf("Bigger buttons" to "in progress"), found.map { it.title to it.status.word })
        assertEquals("2026-10-08T09:15:00.000Z", found.single().startedAt)
        val req = seen.single()
        assertEquals("https://example.invalid/tasks", req.url.toString())
        assertEquals("Bearer 0.abc", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aRefusedTaskListSaysWhatTheServerSaid() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.Forbidden, """{"error":"that needs the admin role"}""").tasks("0.abc")
        }
        assertEquals("that needs the admin role", e.message)
    }

    @Test
    fun releaseNotesComeFromTheWorkerNewestFirst() = runTest {
        val found = api(
            body = """[{"tag_name":"android-v2.1.0-1","published_at":"2026-01-01T00:00:00Z","body":"old"},
                {"tag_name":"android-v2.1.0-2","published_at":"2026-01-02T00:00:00Z","body":"new"}]""",
        ).releases()
        assertEquals(listOf("new", "old"), found.map { it.note })
        assertEquals("https://example.invalid/releases", seen.single().url.toString())
    }

    /**
     * The Worker serves its last copy when GitHub refuses, and only says
     * so when it has none. Read as "no releases" that would look like
     * nothing ever shipped.
     */
    @Test
    fun aRefusalIsAnErrorThatSaysWhyNotAnEmptyList() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.BadGateway, """{"error":"API rate limit exceeded"}""").releases()
        }
        assertEquals("API rate limit exceeded", e.message)
    }

    /** A task's own page: one row and its files, by key, with the session. */
    @Test
    fun oneTaskIsAskedForByItsKeyWithTheSession() = runTest {
        val d = api(
            body = """{"key":"ab12cd34","title":"Bigger buttons","status":"pending","details":"Too small.",
                "files":[{"name":"shot.png","type":"image/png","data":"aGVsbG8="}]}""",
        ).task("0.abc", "ab12cd34")
        assertEquals("Too small.", d.body)
        assertEquals(listOf("shot.png"), d.files.map { it.name })
        val req = seen.single()
        assertEquals("/tasks/ab12cd34", req.url.encodedPath)
        assertEquals("Bearer 0.abc", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aTaskNobodySentSaysSo() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.NotFound, """{"error":"no task has that key"}""").task("0.abc", "zzzzzzzz")
        }
        assertEquals("no task has that key", e.message)
    }
}
