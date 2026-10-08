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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One key per write, so retrying is safe.
 *
 * The Worker applies every mutation as a single batch, so a 5xx means
 * nothing landed. The case this closes is the other one: the batch
 * committed and the answer never got back. Without a key, the retry
 * that follows adds the cards a second time.
 */
class IdempotencyTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun api(body: String): MtgApi {
        val engine = MockEngine { request ->
            seen += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return MtgApi(
            "https://example.test",
            HttpClient(engine) {
                retries()
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
    }

    private fun keyOf(i: Int) = seen[i].headers[Idempotency.HEADER]

    @Test
    fun aKeyIsThirtyTwoHexCharacters() {
        val k = Idempotency.key()
        assertEquals(32, k.length)
        assertTrue(k.all { it in "0123456789abcdef" }, k)
    }

    @Test
    fun everyKeyIsItsOwn() {
        // Two deliberate identical writes — one Sol Ring, then another
        // — are two writes and must both apply. Only a retry of the
        // same attempt shares a key, and Ktor reuses the request it is
        // repeating rather than building a new one.
        val keys = (1..200).map { Idempotency.key() }.toSet()
        assertEquals(200, keys.size)
    }

    @Test
    fun everyWriteCarriesOne() = runTest {
        val a = api("""{"applied":true,"resolved":1,"failed":0,"changes":[],"errors":[]}""")
        a.cards("t", Direction.ADD, "e7de0cb1", "1 Sol Ring", dryRun = false)
        assertNotNull(keyOf(0), "an add went out with no idempotency key")

        val d = api("""{"key":"x","card_count":0,"owned_count":0,"buying":0,"applied":true}""")
        d.setDeckList("t", "x", "c", "1 Sol Ring", dryRun = false)
        d.disassemble("t", "x", dryRun = false)
        d.createDeck("t", "n", "commander", "e7de0cb1", null, "1 Sol Ring", dryRun = false)
        listOf(1, 2, 3).forEach { assertNotNull(seen[it].headers[Idempotency.HEADER], "request $it") }
        // And no two of them are the same.
        assertEquals(4, (0..3).mapNotNull { keyOf(it) }.toSet().size)
    }

    @Test
    fun aReadCarriesNone() = runTest {
        // Reads are safe to repeat by construction; a key on one would
        // only make the server remember an answer it need not.
        val a = api("""{"cols":[],"rows":[],"n":0}""")
        a.query(Sql("SELECT 1", emptyList()))
        assertNull(keyOf(0))
    }
}
