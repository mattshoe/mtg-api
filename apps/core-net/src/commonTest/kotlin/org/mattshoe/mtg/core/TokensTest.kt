package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tokens a deck makes, from Scryfall's own `all_parts`.
 *
 * Read out of the rules text — which is what this did first — a token
 * was a phrase, and the phrases included "Or more" and "Twice that
 * many of those". These are real cards with real art.
 */
class TokensTest {

    private val sent = mutableListOf<String>()

    /**
     * Two rounds: the deck's cards, whose `all_parts` name the token
     * ids, and then the tokens themselves, which is the only place
     * their power, toughness and colours come from.
     */
    private fun scryfall(cards: String, tokens: String): Scryfall {
        var round = 0
        val engine = MockEngine { request ->
            sent += request.url.toString()
            round++
            respond(
                if (round == 1) cards else tokens,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return Scryfall.withEngine(HttpClient(engine) { retries() })
    }

    private val twoCardsMakingBirds = """
        {"data":[
          {"id":"c1","name":"Battle Screech","all_parts":[
             {"id":"t-white-bird","name":"Bird","component":"token","type_line":"Token Creature — Bird"}]},
          {"id":"c2","name":"Chocobo Camp","all_parts":[
             {"id":"t-white-bird","name":"Bird","component":"token","type_line":"Token Creature — Bird"},
             {"id":"t-green-bird","name":"Bird","component":"token","type_line":"Token Creature — Bird"}]}
        ]}
    """

    private val theBirdsThemselves = """
        {"data":[
          {"id":"t-white-bird","name":"Bird","type_line":"Token Creature — Bird",
           "power":"1","toughness":"1","colors":["W"],
           "purchase_uris":{"tcgplayer":"https://tcg.example/white-bird"}},
          {"id":"t-green-bird","name":"Bird","type_line":"Token Creature — Bird",
           "power":"2","toughness":"2","colors":["G"]}
        ]}
    """

    @Test
    fun aTokenComesBackAsACardWithArt() = runTest {
        val found = scryfall(twoCardsMakingBirds, theBirdsThemselves).tokens(listOf("c1", "c2"))
        assertEquals(2, found.size)
        assertTrue(found.all { it.art.orEmpty().contains("art_crop") }, found.toString())
    }

    @Test
    fun twoBirdsThatDifferOnlyByColourAreTwoTokens() = runTest {
        // And the same Bird printed in four sets is one. Neither can
        // be told from `all_parts`, which carries a name and a type
        // line and nothing else — which is why the tokens themselves
        // are fetched.
        val found = scryfall(twoCardsMakingBirds, theBirdsThemselves).tokens(listOf("c1", "c2"))
        assertEquals(listOf("1/1", "2/2"), found.map { it.stats }.sortedBy { it })
        assertEquals(setOf("W", "G"), found.map { it.colors }.toSet())
    }

    @Test
    fun howManyCardsMakeEachOne() = runTest {
        val found = scryfall(twoCardsMakingBirds, theBirdsThemselves).tokens(listOf("c1", "c2"))
        // Two cards make the white one, one makes the green.
        assertEquals(2, found.first { it.colors == "W" }.madeBy)
        assertEquals(1, found.first { it.colors == "G" }.madeBy)
        // And the one made most often is listed first.
        assertEquals("W", found.first().colors)
    }

    @Test
    fun theSameTokenInSeveralSetsCollapsesToOne() = runTest {
        val cards = """
            {"data":[{"id":"c1","name":"A","all_parts":[
               {"id":"t1","name":"Soldier","component":"token","type_line":"Token Creature — Soldier"},
               {"id":"t2","name":"Soldier","component":"token","type_line":"Token Creature — Soldier"}]}]}
        """
        val tokens = """
            {"data":[
              {"id":"t1","name":"Soldier","type_line":"Token Creature — Soldier","power":"1","toughness":"1","colors":["W"]},
              {"id":"t2","name":"Soldier","type_line":"Token Creature — Soldier","power":"1","toughness":"1","colors":["W"]}
            ]}
        """
        val found = scryfall(cards, tokens).tokens(listOf("c1"))
        assertEquals(1, found.size, "the same Soldier from two sets is one Soldier")
        assertEquals(2, found.first().madeBy)
    }

    @Test
    fun aTokenCarriesWhereToBuyOne() = runTest {
        val found = scryfall(twoCardsMakingBirds, theBirdsThemselves).tokens(listOf("c1", "c2"))
        assertEquals("https://tcg.example/white-bird", found.first { it.colors == "W" }.tcgplayer)
    }

    @Test
    fun andNullWhenScryfallHasNoListing() = runTest {
        // A token from a set nobody sells singles of. The row stays a
        // row rather than becoming a link to nowhere.
        val found = scryfall(twoCardsMakingBirds, theBirdsThemselves).tokens(listOf("c1", "c2"))
        assertEquals(null, found.first { it.colors == "G" }.tcgplayer)
    }

    @Test
    fun onlyTokenPartsCount() = runTest {
        // `all_parts` also lists melded halves, combo pieces and the
        // card itself.
        val cards = """
            {"data":[{"id":"c1","name":"A","all_parts":[
               {"id":"c1","name":"A","component":"combo_piece","type_line":"Creature"},
               {"id":"t1","name":"Clue","component":"token","type_line":"Token Artifact — Clue"}]}]}
        """
        val tokens = """{"data":[{"id":"t1","name":"Clue","type_line":"Token Artifact — Clue","colors":[]}]}"""
        val found = scryfall(cards, tokens).tokens(listOf("c1"))
        assertEquals(listOf("Clue"), found.map { it.name })
        assertEquals(null, found.first().stats, "an artifact token has no power")
    }

    @Test
    fun aDeckThatMakesNothingAsksScryfallNothingTwice() = runTest {
        val found = scryfall("""{"data":[{"id":"c1","name":"A","all_parts":[]}]}""", "{}")
            .tokens(listOf("c1"))
        assertEquals(emptyList(), found)
        assertEquals(1, sent.size, "the second round ran with nothing to look up")
    }

    @Test
    fun noIdsMeansNoRequestAtAll() = runTest {
        val found = scryfall("{}", "{}").tokens(emptyList())
        assertEquals(emptyList(), found)
        assertEquals(0, sent.size)
    }

    @Test
    fun aFailureLosesTheRowRatherThanTheDeck() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val s = Scryfall.withEngine(HttpClient(engine))
        assertEquals(emptyList(), s.tokens(listOf("c1")))
    }
}
