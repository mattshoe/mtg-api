package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Find on the Fetch list, the way both shells run it.
 *
 * Matt: "the new fetch list system is AGONIZINGLY fucking slow. it
 * takes several full minutes to run". The collection answers in one
 * query; it was the Also check — a couple of dozen Scryfall searches,
 * one after another — that the whole list waited on. So the filing
 * goes on screen the moment the collection answers, and the Also
 * check is filled in when Scryfall gets there.
 */
class FetchFindTest {

    private fun api(): MtgApi {
        val engine = MockEngine {
            respond(
                """{"cols":["name_norm","name","face1","types","colors","color_identity","rarity","qty"],"rows":[
                    ["arcane signet","Arcane Signet","Arcane Signet","Artifact",null,null,"common",1]
                ],"n":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi.withEngine("https://example.invalid", client)
    }

    /** Scryfall, holding every answer until `open` is completed. */
    private fun scryfall(open: CompletableDeferred<Unit>): Scryfall {
        val engine = MockEngine { req ->
            open.await()
            val q = req.url.parameters["q"].orEmpty()
            if ("r:uncommon" in q) {
                respond(
                    """{"data":[{"name":"Arcane Signet"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"object":"error","code":"not_found","status":404}""",
                    HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        return Scryfall.withEngine(HttpClient(engine))
    }

    @Test
    fun theFilingIsOnScreenBeforeScryfallHasAnswered() = runTest {
        val open = CompletableDeferred<Unit>()
        val filed = CompletableDeferred<FetchResult>()
        var done: FetchResult? = null
        val job = launch {
            done = FetchList.find(api(), scryfall(open), DeckList.entries("1 Arcane Signet"), "bprh3d2s") {
                filed.complete(it)
            }
        }
        // Real time, because the mock engine answers on its own threads.
        val first = withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { filed.await() } }
        if (first == null) open.complete(Unit)
        assertNotNull(first, "nothing was filed until Scryfall answered, which is the minutes Matt waited")
        assertEquals("Arcane Signet", first.sections.single().cards.single().name)
        assertNull(done, "the Also check finished without Scryfall")

        open.complete(Unit)
        job.join()
        assertEquals(
            listOf(FetchRarity.UNCOMMON),
            assertNotNull(done).sections.single().cards.single().alsoCheck,
        )
    }
}
