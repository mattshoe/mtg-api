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

    @Test
    fun unlockSendsThePasswordAndNoToken() = runTest {
        val token = api(body = """{"ok":true,"token":"0.abc","expires_at":null}""").unlock("hunter2")
        assertEquals("0.abc", token)

        val req = seen.single()
        assertEquals("/admin", req.url.encodedPath)
        assertEquals("hunter2", Json.parseToJsonElement(req.bodyText()).jsonObject["password"]!!.jsonPrimitive.content)
        assertNull(req.headers[HttpHeaders.Authorization], "the password call must not carry a token")
    }

    @Test
    fun aTokenlessServerReplyIsARefusalNotASilentSuccess() = runTest {
        assertFailsWith<ApiFailure> { api(body = """{"ok":true}""").unlock("x") }
    }

    @Test
    fun aWrongPasswordSurfacesTheServersOwnWords() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.Unauthorized, """{"error":"wrong password"}""").unlock("nope")
        }
        assertEquals("wrong password", e.message)
    }

    @Test
    fun aPreviewSaysDryRunAndCarriesTheToken() = runTest {
        val out = api(
            body = """{"applied":false,"dry_run":true,"resolved":2,"failed":0,
                "changes":[["Lightning Bolt","2X2","117","nonfoil",0,4]],"errors":[],"notes":[]}""",
        ).cards("0.abc", Direction.ADD, "matt", "4 Lightning Bolt", dryRun = true)

        val req = seen.single()
        assertEquals("/cards/add", req.url.encodedPath)
        assertEquals("Bearer 0.abc", req.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(req.bodyText()).jsonObject
        assertEquals("matt", sent["owner"]!!.jsonPrimitive.content)
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
            .cards("t", Direction.REMOVE, "kayla", "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("/cards/remove", seen.single().url.encodedPath)
        assertEquals("kayla", sent["owner"]!!.jsonPrimitive.content)
        assertEquals("false", sent["dry_run"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsAndNotesComeBackWhole() = runTest {
        val out = api(
            body = """{"applied":true,"resolved":1,"failed":2,"changes":[],
                "errors":["no card named Biterblosom","line 4 unreadable"],
                "notes":["rulings skipped on a big import"]}""",
        ).cards("t", Direction.ADD, "matt", "x", dryRun = false)
        assertEquals(2, out.failed)
        assertEquals("no card named Biterblosom", out.errors[0])
        assertEquals(1, out.notes.size)
    }

    @Test
    fun anExpiredTokenIsReportedNotSwallowed() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.Unauthorized, """{"error":"admin session expired — unlock again"}""")
                .cards("stale", Direction.ADD, "matt", "1 Sol Ring", dryRun = true)
        }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test
    fun aReplyThatIsNotJsonSaysSoRatherThanCrashing() = runTest {
        val e = assertFailsWith<ApiFailure> {
            api(HttpStatusCode.InternalServerError, "<html>upstream is having a moment</html>")
                .cards("t", Direction.ADD, "matt", "1 Sol Ring", dryRun = true)
        }
        assertTrue(e.message!!.contains("500") || e.message!!.contains("could not be read"))
    }

    @Test
    fun aListFullOfCommasAndApostrophesSurvivesTheJson() = runTest {
        val list = "1 Kardur, Doomscourge\n1 Ambition's Cost\n\"quoted\",2"
        api(body = """{"applied":false,"dry_run":true,"resolved":3,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "matt", list, dryRun = true)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals(list, sent["list"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWholeCollectionExportGoesUpIntact() = runTest {
        val list = (1..4000).joinToString("\n") { "1 Card Number $it" }
        api(body = """{"applied":false,"dry_run":true,"resolved":4000,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "matt", list, dryRun = true)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals(list, sent["list"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWriteGoesToWhicheverCollectionItWasHanded() = runTest {
        // It used to take one of two names, which is every collection
        // there would ever be. A slug is whatever an account's slug
        // is, and the server is the one that decides whether this
        // session may write to it.
        api(body = """{"applied":true,"dry_run":false,"resolved":1,"changes":[],"errors":[]}""")
            .cards("t", Direction.ADD, "ulamog-fan-42", "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("ulamog-fan-42", sent["owner"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNewDeckLandsInWhicheverCollectionItWasHanded() = runTest {
        api(body = """{"created":true,"dry_run":false,"name":"Burn","slug":"burn","missing":[]}""")
            .createDeck("t", "Burn", "modern", "ulamog-fan-42", null, "1 Sol Ring", dryRun = false)
        val sent = Json.parseToJsonElement(seen.single().bodyText()).jsonObject
        assertEquals("ulamog-fan-42", sent["owner"]!!.jsonPrimitive.content)
    }
}
