package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The carousel's sheet says how much the card is played.
 *
 * Matt: "I asked to have the edhrec rank of every card on its 'bottom
 * sheet' thingy on the carousel. It's still missing."
 *
 * #35's message said it had shipped and its diff did not have it. The
 * Library's rows already carried `edhrec_rank`, and the deck's list
 * never selected it at all, so the sheet had nothing to say over a
 * deck even if it had wanted to.
 */
class PeekEdhrecTest {

    private fun deckCard(name: String, rank: Long?) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        edhrecRank = rank,
    )

    private fun overADeck(vararg cards: DeckCard) = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks.loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", cards.toList()),
            )
        }

    private fun row(name: String, rank: Long?) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = null, scryfallId = null, manaCost = null, cmc = null, typeLine = "Artifact",
        colorIdentity = null, rarity = null, setCode = null, setName = null,
        collectorNumber = null, edhrecRank = rank, releasedAt = null, finish = null,
        power = null, toughness = null, artist = null, qty = 2, printings = 1, free = 1,
        price = null, value = null,
    )

    private fun tags(s: AppState) = assertNotNull(s.peeked, "nothing is peeked").tags.map { it.text }

    @Test
    fun aDecksCarouselSaysTheCardsEdhrecRank() {
        val ring = deckCard("Sol Ring", 1L)
        val s = overADeck(ring).peekCard(ring)
        assertTrue("EDHREC #1" in tags(s), "the deck's sheet has no EDHREC rank: ${tags(s)}")
    }

    @Test
    fun theLibrarysCarouselSaysItToo() {
        val r = row("Cultivate", 1234L)
        val s = AppState().navigate(View.LIBRARY)
            .let { it.copy(library = it.library.loaded(listOf(r), 1)) }
            .peekRow(r)
        assertTrue("EDHREC #1,234" in tags(s), "the Library's sheet has no EDHREC rank: ${tags(s)}")
    }

    @Test
    fun aCardEdhrecNeverRankedSaysSoRatherThanSayingNothing() {
        // Every card gets the line, so a missing one is a bug and not
        // a basic land.
        val plains = deckCard("Plains", null)
        val s = overADeck(plains).peekCard(plains)
        assertTrue("EDHREC unranked" in tags(s), "an unranked card says nothing: ${tags(s)}")
    }

    @Test
    fun theDecksListAsksForTheRankAndReadsItBack() {
        val q = DeckQueries.cards("alela").sql
        assertTrue("edhrec_rank" in q, "the deck's list never selects edhrec_rank")
        val cards = DeckQueries.decodeCards(
            listOf("name", "name_norm", "qty", "owned", "edhrec_rank"),
            listOf(
                JsonArray(listOf(JsonPrimitive("Sol Ring"), JsonPrimitive("sol ring"), JsonPrimitive(1), JsonPrimitive(1), JsonPrimitive(1))),
                JsonArray(listOf(JsonPrimitive("Plains"), JsonPrimitive("plains"), JsonPrimitive(1), JsonPrimitive(0), JsonNull)),
            ),
        )
        assertEquals(listOf(1L, null), cards.map { it.edhrecRank })
    }
}
