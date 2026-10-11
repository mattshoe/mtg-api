package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tapping a bar in the deck page's Creature types chart.
 *
 * Matt: "on the creature breakdown in the deck detail, i want to be
 * able to tap a bar, and it takes me to a carousel with all those
 * creatures in it. so tapping a 'human' bar would bring up a carousel
 * with all human creatures".
 */
class TribePeekTest {

    private fun card(name: String, type: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
    )

    private val deck = Deck("alela", "Alela", "matt", null, "UW", 3, null)

    private fun onADeck() = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks.loaded(listOf(deck)).opened(
                    "alela",
                    listOf(
                        card("Champion of the Parish", "Creature — Human Soldier"),
                        card("Llanowar Elves", "Creature — Elf Druid"),
                        card("Delver of Secrets", "Creature — Human Wizard // Creature — Human Insect"),
                        card("Sol Ring", "Artifact"),
                        card("Kindred Dominance", "Kindred Sorcery — Human"),
                        card("Thalia", "Legendary Creature — Human Soldier"),
                    ),
                ),
            )
        }

    @Test
    fun tappingTheHumanBarOpensACarouselOfEveryHumanAndNothingElse() {
        val s = onADeck().peekTribe("Human")
        assertTrue(Overlay.CARD_PEEK in s.overlays, "the carousel did not open")
        assertEquals(
            listOf("Champion of the Parish", "Delver of Secrets", "Thalia", "Kindred Dominance"),
            s.peekRun.map { it.title },
            "the carousel is not every Human in the deck, in the order the page lists them",
        )
        assertEquals("Champion of the Parish", assertNotNull(s.peeked).title)
        assertEquals("1 of 4", s.peekPlace)
    }

    @Test
    fun swipingStaysInsideTheType() {
        val s = onADeck().peekTribe("Human").peekTo(99)
        assertEquals("Kindred Dominance", assertNotNull(s.peeked).title, "the swipe left the Humans")
        assertEquals("4 of 4", s.peekPlace)
    }

    @Test
    fun tappingACardRowAfterwardsIsTheWholeDeckAgain() {
        val s = onADeck().peekTribe("Elf").closing(Overlay.CARD_PEEK)
        val sol = s.decks.pageOrder.first { it.name == "Sol Ring" }
        assertEquals(6, s.peekCard(sol).peekRun.size, "the Elf filter outlived its carousel")
    }

    @Test
    fun aTypeNoCardHasOpensNothing() {
        assertFalse(Overlay.CARD_PEEK in onADeck().peekTribe("Dragon").overlays)
    }
}
