package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import kotlin.test.assertTrue

class ScryfallTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun scryfall(body: String, status: HttpStatusCode = HttpStatusCode.OK): Scryfall {
        val engine = MockEngine { req ->
            seen += req
            if (status == HttpStatusCode.OK) {
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(status)
            }
        }
        return Scryfall(HttpClient(engine))
    }

    @Test
    fun aShortTermAsksNothingAtAll() = runTest {
        val s = scryfall("""{"data":["Sol Ring"]}""")
        assertTrue(s.complete("s").isEmpty())
        assertTrue(seen.isEmpty(), "one letter is not worth a request")
    }

    @Test
    fun namesComeBackAndTheTermIsSentAsTyped() = runTest {
        val s = scryfall("""{"data":["Sol Ring","Solemn Simulacrum"]}""")
        assertEquals(listOf("Sol Ring", "Solemn Simulacrum"), s.complete("sol"))
        assertTrue(seen.single().url.toString().contains("autocomplete"))
        assertTrue(seen.single().url.parameters["q"] == "sol")
    }

    @Test
    fun theListIsCapped() = runTest {
        val many = (1..40).joinToString(",") { "\"Card $it\"" }
        assertEquals(3, scryfall("""{"data":[$many]}""").complete("car", limit = 3).size)
    }

    /** Being offline is not worth an error in a convenience. */
    @Test
    fun aFailureIsNoSuggestionsRatherThanAnException() = runTest {
        assertTrue(scryfall("", HttpStatusCode.ServiceUnavailable).complete("sol").isEmpty())
    }

    @Test
    fun garbageBackIsNoSuggestionsToo() = runTest {
        assertTrue(scryfall("<html>nope</html>").complete("sol").isEmpty())
    }
}

class ValidationTest {

    private fun api(body: String): MtgApi {
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return MtgApi(
            "https://example.invalid",
            HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        )
    }

    @Test
    fun everyNameKnownIsOk() = runTest {
        val v = api("""{"ok":true,"checked":2,"unknown":0,
            "cards":[{"name":"Sol Ring","ok":true,"source":"collection"},
                     {"name":"Opt","ok":true,"source":"scryfall"}]}""").validateRaw("1 Sol Ring\n1 Opt")
        assertTrue(v.ok)
        assertTrue(v.bad.isEmpty())
    }

    /** "did you mean Bitterblossom" beats "no card named Biterblosom". */
    @Test
    fun aTypoComesBackWithItsSuggestion() = runTest {
        val v = api("""{"ok":false,"checked":1,"unknown":1,
            "cards":[{"name":"Biterblosom","ok":false,"suggestion":"Bitterblossom"}]}""")
            .validateRaw("1 Biterblosom")
        assertEquals(1, v.bad.size)
        assertEquals(listOf("Biterblosom" to "Bitterblossom"), v.suggestions)
    }

    @Test
    fun anUnknownNameWithNoNearMissIsStillReported() = runTest {
        val v = api("""{"ok":false,"checked":1,"unknown":1,
            "cards":[{"name":"Zzzz","ok":false}]}""").validateRaw("1 Zzzz")
        assertEquals(1, v.bad.size)
        assertTrue(v.suggestions.isEmpty())
    }
}
