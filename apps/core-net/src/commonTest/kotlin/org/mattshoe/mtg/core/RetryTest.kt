package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A flaky minute is a pause, not an error banner.
 *
 * Five retries after the first attempt, doubling from 300ms. The
 * schedule is asserted on the arithmetic rather than on a stopwatch —
 * a test that actually waited nine seconds would be nine seconds
 * nobody runs twice.
 */
class RetryTest {

    // ------------------------------------------------------ the schedule

    @Test
    fun theFirstWaitIsThreeHundredMillisecondsAndEachDoubles() {
        assertEquals(listOf(300L, 600L, 1200L, 2400L, 4800L), (1..5).map { Retry.delayFor(it) })
    }

    @Test
    fun thereAreFiveRetriesAfterTheFirstTry() {
        assertEquals(5, Retry.MAX)
        // Nine and a bit seconds of trying before anything is reported.
        assertEquals(9300L, (1..Retry.MAX).sumOf { Retry.delayFor(it) })
    }

    @Test
    fun anAttemptNumberBelowOneIsStillTheFirstWait() {
        assertEquals(300L, Retry.delayFor(0))
        assertEquals(300L, Retry.delayFor(-3))
    }

    // -------------------------------------------------- what is retried

    @Test
    fun aServerErrorAndATooManyRequestsAreWorthAnotherTry() {
        assertTrue(Retry.worthRetrying(500))
        assertTrue(Retry.worthRetrying(502))
        assertTrue(Retry.worthRetrying(503))
        assertTrue(Retry.worthRetrying(429))
    }

    @Test
    fun aBadRequestIsNotWorthATry() {
        // The server saying the request is wrong. It will be just as
        // wrong the sixth time, and retrying it turns one clear error
        // into a nine-second hang.
        listOf(400, 401, 403, 404, 409, 422).forEach {
            assertTrue(!Retry.worthRetrying(it), "$it should not be retried")
        }
        assertTrue(!Retry.worthRetrying(200))
    }

    @Test
    fun aCancelledRequestIsNotRetried() {
        // A newer search replacing this one. Retrying a dead job is
        // how the cancelled one comes back and closes the live list.
        assertTrue(!Retry.worthRetrying(kotlinx.coroutines.CancellationException("newer search")))
        assertTrue(Retry.worthRetrying(RuntimeException("connection reset")))
    }

    // ------------------------------------------------- through a client

    private fun apiThatFails(times: Int, then: String): Pair<MtgApi, () -> Int> {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls <= times) {
                respondError(HttpStatusCode.BadGateway)
            } else {
                respond(then, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) {
            retries()
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi("https://example.test", http) to { calls }
    }

    @Test
    fun aCallThatFailsTwiceStillComesBackWithAnAnswer() = runTest {
        val (api, calls) = apiThatFails(2, """{"cols":["n"],"rows":[[1]],"n":1}""")
        val r = api.query(Sql("SELECT 1", emptyList()))
        assertEquals(1, r.n)
        assertEquals(3, calls(), "two failures and the one that worked")
    }

    @Test
    fun aCallThatNeverWorksGivesUpAfterSixTries() = runTest {
        val (api, calls) = apiThatFails(99, "")
        assertFailsWith<ApiFailure> { api.query(Sql("SELECT 1", emptyList())) }
        assertEquals(Retry.MAX + 1, calls(), "the first try plus ${Retry.MAX} retries")
    }

    @Test
    fun aRefusalIsReportedAtOnceRatherThanRetriedForNineSeconds() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond(
                """{"error":"near \"SELEC\": syntax error"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            retries()
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = MtgApi("https://example.test", http)
        val e = assertFailsWith<ApiFailure> { api.query(Sql("SELEC 1", emptyList())) }
        assertEquals(1, calls, "a 400 was retried")
        assertTrue(e.message.orEmpty().contains("syntax error"), e.message.orEmpty())
    }
}
