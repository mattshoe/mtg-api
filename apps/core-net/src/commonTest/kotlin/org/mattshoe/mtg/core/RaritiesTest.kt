package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rarity a card has been printed at, for the Fetch list's Also
 * check.
 *
 * The database holds the rarity of the printing that was entered, and
 * Matt: "sometimes the rarity i have in the database does not match
 * the rarity of the actual printing i have". So the other rarities come
 * from Scryfall, one search per rarity over a batch of names.
 */
class RaritiesTest {

    private val asked = mutableListOf<String>()

    private fun scryfall(byRarity: Map<String, List<String>>): Scryfall {
        val engine = MockEngine { req ->
            val q = req.url.parameters["q"].orEmpty()
            asked += q
            val r = Regex("""r:(\w+)""").find(q)?.groupValues?.get(1).orEmpty()
            val names = byRarity[r].orEmpty()
            if (names.isEmpty()) {
                respond(
                    """{"object":"error","code":"not_found","status":404}""",
                    HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"data":[${names.joinToString(",") { "{\"name\":\"$it\"}" }}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        return Scryfall.withEngine(HttpClient(engine))
    }

    @Test
    fun aCardPrintedAtTwoRaritiesComesBackWithBoth() = runTest {
        val s = scryfall(
            mapOf(
                "common" to listOf("Arcane Signet"),
                "uncommon" to listOf("Arcane Signet", "Brazen Borrower // Petty Theft"),
                "mythic" to listOf("Brazen Borrower // Petty Theft"),
            ),
        )
        val found = s.rarities(listOf("Arcane Signet", "Brazen Borrower"))
        assertEquals(setOf("common", "uncommon"), found["arcane signet"])
        // A list writes the front face, so that is a key as well.
        assertEquals(setOf("uncommon", "mythic"), found["brazen borrower"])
        assertEquals(setOf("uncommon", "mythic"), found["brazen borrower // petty theft"])
    }

    @Test
    fun theNamesAreAskedForExactlyAndOnPaper() = runTest {
        scryfall(emptyMap()).rarities(listOf("Sol Ring"))
        assertTrue(asked.isNotEmpty(), "Scryfall was never asked")
        assertTrue(asked.all { "!\"Sol Ring\"" in it && "game:paper" in it }, asked.toString())
    }

    @Test
    fun scryfallBeingDownIsNoAlsoCheckRatherThanAnError() = runTest {
        val engine = MockEngine { throw IllegalStateException("offline") }
        assertEquals(emptyMap(), Scryfall.withEngine(HttpClient(engine)).rarities(listOf("Sol Ring")))
    }
}
