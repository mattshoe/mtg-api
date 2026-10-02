package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a deck a card at a time.
 *
 * Opening one card, going back, opening the next is three gestures
 * per card and loses your place on the list each time. The cards are
 * in an order on the page, so the card page can just follow it.
 */
class DeckRunTest {

    private fun card(name: String, type: String = "Creature") = DeckCard(
        name = name,
        nameNorm = name.lowercase(),
        qty = 1,
        role = null,
        owned = 1,
        typeLine = type,
        scryfallId = null,
    )

    /** Deliberately out of page order: lands before creatures, z before a. */
    private val deck = DecksState(
        decks = listOf(
            Deck("alela", "Alela", "matt", commander = null, colors = null, bracket = null, artId = null),
        ),
        openSlug = "alela",
        cards = listOf(
            card("Zealous Conscripts"),
            card("Command Tower", "Land"),
            card("Arcane Signet", "Artifact"),
            card("Bitterblossom"),
        ),
    )

    private fun onCard(norm: String) = AppState(decks = deck)
        .navigate(Route(View.DECKS, "alela"))
        .openCard(CardRef(norm))

    @Test
    fun theRunIsTheOrderThePageDraws() {
        assertEquals(
            deck.byType.flatMap { it.second }.map { it.name },
            deck.pageOrder.map { it.name },
            "the run is not what the page shows",
        )
    }

    @Test
    fun whichIsBySectionAndAlphabeticalInside() {
        // Not the order the rows arrived in.
        assertEquals(
            listOf("Bitterblossom", "Zealous Conscripts", "Arcane Signet", "Command Tower"),
            deck.pageOrder.map { it.name },
        )
    }

    @Test
    fun aCardInTheMiddleHasOneEitherSide() {
        val s = onCard("zealous conscripts")
        assertEquals("Bitterblossom", s.previousCard?.name)
        assertEquals("Arcane Signet", s.nextCard?.name)
    }

    @Test
    fun theFirstHasNothingBeforeIt() {
        val s = onCard("bitterblossom")
        assertNull(s.previousCard)
        assertEquals("Zealous Conscripts", s.nextCard?.name)
    }

    @Test
    fun andTheLastNothingAfterIt() {
        val s = onCard("command tower")
        assertEquals("Arcane Signet", s.previousCard?.name)
        assertNull(s.nextCard)
    }

    @Test
    fun itSaysWhereYouAre() {
        assertEquals("1 of 4", onCard("bitterblossom").cardPlace)
        assertEquals("4 of 4", onCard("command tower").cardPlace)
    }

    @Test
    fun aCardOpenedFromTheLibraryIsInNoRun() {
        val s = AppState(decks = deck).navigate(Route(View.LIBRARY)).openCard(CardRef("bitterblossom"))
        assertTrue(s.deckRun.isEmpty())
        assertNull(s.nextCard)
        assertNull(s.cardPlace)
    }

    @Test
    fun norIsOneOpenedFromADeckThatIsNoLongerLoaded() {
        val s = AppState(decks = deck.copy(openSlug = "something-else"))
            .navigate(Route(View.DECKS, "alela"))
            .openCard(CardRef("bitterblossom"))
        assertTrue(s.deckRun.isEmpty(), "it offered neighbours out of a deck that is not open")
    }

    @Test
    fun aCardTheDeckDoesNotHoldHasNoPlaceInIt() {
        val s = onCard("sol ring")
        assertEquals(-1, s.cardAt)
        assertNull(s.nextCard)
        assertNull(s.previousCard)
        assertNull(s.cardPlace)
    }

    @Test
    fun steppingThroughKeepsTheDeckBehindYou() {
        // Going card to card used to blank `from`, so one step along
        // the run left back with nowhere to go and the run itself
        // empty from then on.
        var s = onCard("bitterblossom")
        repeat(3) { s = s.openCard(CardRef(s.nextCard!!.nameNorm)) }
        assertEquals("Command Tower", s.card?.nameNorm?.let { n -> deck.pageOrder.first { it.nameNorm == n }.name })
        assertEquals("4 of 4", s.cardPlace)
        assertEquals(Route(View.DECKS, "alela"), s.from, "back forgot the deck")
    }

    @Test
    fun andBackStillGoesToTheDeck() {
        var s = onCard("bitterblossom")
        s = s.openCard(CardRef(s.nextCard!!.nameNorm))
        assertEquals(Route(View.DECKS, "alela"), s.leaveCard().route)
    }
}
