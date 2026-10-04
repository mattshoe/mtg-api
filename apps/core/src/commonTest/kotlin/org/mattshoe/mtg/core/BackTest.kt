package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One press of back, from everywhere it can be pressed.
 *
 * Matt: "The back button when looking at a card detail from a deck
 * doesn't work. You have to tap it twice and it goes back to library."
 * The phone's handler asked whether a deck was open before it asked
 * whether a card was on screen, and reading a card from a deck both
 * are true — so the first press closed the deck underneath and left
 * the card up, and the second fell through to the default view.
 *
 * The order lives here now rather than in `AppShell`, so it is one
 * rule both platforms obey and it can be checked without a device.
 */
class BackTest {

    private fun deck(slug: String = "alela") =
        Deck(slug, "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String) = DeckCard(
        name, qty = 1, role = null, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Artifact", scryfallId = "abcdef12-3456",
    )

    private fun onADeck() = AppState(admin = Admin(token = "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let { it.copy(decks = it.decks.loaded(listOf(deck())).opened("alela", listOf(card("Sol Ring")))) }

    private fun readingACardFromThatDeck() =
        onADeck().openCard(CardRef(nameNorm = "sol ring"), "Sol Ring")

    // ------------------------------------------------- the reported bug

    @Test
    fun backFromACardOpenedInADeckGoesToTheDeck() {
        val card = readingACardFromThatDeck()
        assertEquals(View.CARD, card.view, "the fixture is not on a card")

        val once = card.back() ?: error("back wanted to leave the app from a card")
        assertEquals(View.DECKS, once.view, "one press did not come off the card")
        assertEquals("alela", once.route.rest, "it left the card but not back to the deck")
    }

    @Test
    fun oneBackIsEnoughAndItIsNotTheLibrary() {
        val once = readingACardFromThatDeck().back() ?: error("nothing to go back to")
        assertTrue(
            once.view != View.DEFAULT,
            "one press landed on the default view instead of the deck it came from",
        )
    }

    @Test
    fun theDeckIsStillOpenAfterComingBackToIt() {
        val once = readingACardFromThatDeck().back() ?: error("nothing to go back to")
        assertEquals(
            "alela",
            once.decks.openSlug,
            "coming back off the card closed the deck it came from",
        )
    }

    @Test
    fun comingOutOfADeckPutsTheAddressBackToTheList() {
        // The deck has to be in the route while it is open, because
        // that is the only thing a card opened from it can remember.
        // So coming out of it has to take the route back as well, or
        // the screen shows the list over an address that still names
        // a deck — and the next card opened from the list claims it
        // came from a deck nobody is looking at.
        val atDeck = readingACardFromThatDeck().back() ?: error("nothing to go back to")
        assertEquals("alela", atDeck.route.rest, "the open deck is not in the address")

        val out = atDeck.back() ?: error("nothing to go back to")
        assertEquals(View.DECKS, out.view)
        assertEquals("", out.route.rest, "coming out of the deck left its slug in the address")
    }

    @Test
    fun backOutOfThatDeckThenClosesIt() {
        val atDeck = readingACardFromThatDeck().back() ?: error("nothing to go back to")
        val out = atDeck.back() ?: error("nothing to go back to")
        assertNull(out.decks.openSlug, "the second press did not come out of the deck")
    }

    // --------------------------------------------- the rest of the order

    @Test
    fun anOverlayComesOffBeforeAnythingElse() {
        val s = readingACardFromThatDeck().opening(Overlay.CHEATSHEET)
        val once = s.back() ?: error("nothing to go back to")
        assertTrue(Overlay.CHEATSHEET !in once.overlays, "back did not take the overlay off")
        assertEquals(View.CARD, once.view, "taking an overlay off also left the card")
    }

    @Test
    fun overlaysComeOffOneAtATime() {
        val s = onADeck().opening(Overlay.NEW_DECK).opening(Overlay.CHEATSHEET)
        val once = s.back() ?: error("nothing to go back to")
        assertEquals(listOf(Overlay.NEW_DECK), once.overlays.stack, "both overlays went at once")
    }

    @Test
    fun backFromACardOpenedFromTheLibraryGoesToTheLibrary() {
        val fromLibrary = AppState().navigate(View.LIBRARY)
            .openCard(CardRef(nameNorm = "sol ring"), "Sol Ring")
        val once = fromLibrary.back() ?: error("nothing to go back to")
        assertEquals(View.LIBRARY, once.view, "a card opened from Library went somewhere else")
    }

    @Test
    fun backFromAnotherViewGoesToTheDefaultOne() {
        val once = AppState(admin = Admin(token = "t")).navigate(View.STATS).back()
            ?: error("nothing to go back to")
        assertEquals(View.DEFAULT, once.view)
    }

    @Test
    fun backFromTheDefaultViewWithNothingOpenLeavesTheApp() {
        assertNull(AppState().back(), "back on a bare default view did not ask to leave")
    }

    @Test
    fun steppingAlongADeckStillLeavesToTheDeckAndNotToThePreviousCard() {
        // `openCard` keeps where the run started rather than the last
        // card, so back is one press out of the whole run.
        val run = readingACardFromThatDeck()
            .openCard(CardRef(nameNorm = "birds of paradise"), "Birds of Paradise")
        val once = run.back() ?: error("nothing to go back to")
        assertEquals(View.DECKS, once.view, "back walked to the previous card instead of out")
        assertEquals("alela", once.route.rest)
    }
}
