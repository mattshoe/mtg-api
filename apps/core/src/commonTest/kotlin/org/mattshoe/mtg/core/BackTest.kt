package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private fun onADeck() = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let { it.copy(decks = it.decks.loaded(listOf(deck())).opened("alela", listOf(card("Sol Ring")))) }

    private fun readingACardFromThatDeck() =
        onADeck().openCard(CardRef(nameNorm = "sol ring"), "Sol Ring")

    /** The card-name box with a list of suggestions open over it. */
    private fun suggesting() = Completion().typed("sol")
        .suggested(listOf("Sol Ring", "Solemn Simulacrum"))

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
            once.decks.openKey,
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
        assertNull(out.decks.openKey, "the second press did not come out of the deck")
    }

    // ----------------------------------------- the suggestion list

    @Test
    fun theSuggestionListIsTheFirstThingBackCloses() {
        // The one that would have caught 1.4. Everything is true at
        // once — a list over a card, over an open deck, under an
        // overlay, on a view that is not the default — and the only
        // thing back is allowed to touch is the list.
        val s = readingACardFromThatDeck()
            .opening(Overlay.CHEATSHEET)
            .typedCardName(suggesting())
        assertTrue(s.complete.open, "the fixture never opened the list")

        val once = s.back() ?: error("back wanted to leave the app with a list open")

        assertFalse(once.complete.open, "the suggestion list was left over the screen")
        assertTrue(Overlay.CHEATSHEET in once.overlays, "an overlay back was not asked to touch came off")
        assertEquals(View.CARD, once.view, "back left the card as well as the list")
        assertEquals("alela", once.decks.openKey, "a deck back was not asked to touch closed")
    }

    @Test
    fun closingTheListLeavesWhatWasTypedAlone() {
        // `closed()` and not a rebuilt `Completion`. One that carries
        // a term hands back whatever the frame it was built in was
        // drawing, which on the web put a search you had just cleared
        // back on the screen.
        val s = AppState().typedCardName(suggesting())
        val once = s.back() ?: error("nothing to go back to")

        assertEquals("sol", once.complete.term, "closing the list also emptied the box")
        assertEquals("sol", once.library.filters.q, "closing the list rewrote the name filter")
        assertEquals(listOf("Sol Ring", "Solemn Simulacrum"), once.complete.items)
    }

    @Test
    fun aSecondPressAfterTheListGoesOnToWhatIsUnderneath() {
        val s = onADeck().typedCardName(suggesting())

        val closed = s.back() ?: error("nothing to go back to")
        assertEquals("alela", closed.decks.openKey, "the first press went past the list")

        val out = closed.back() ?: error("nothing to go back to")
        assertEquals(View.DECKS, out.view)
        assertEquals("", out.route.rest, "the second press did not come out of the deck")
    }

    @Test
    fun aListOnTheDefaultViewIsNotAnExit() {
        // Without the list in `back()` this press fell through to
        // null, which `AppShell` forwards to `onExit` — the app left
        // while the suggestions were still on screen.
        val s = AppState().typedCardName(suggesting())
        assertNotNull(s.back(), "back with a list open still asked to leave the app")
        assertFalse(s.wouldExitWithUnsavedEntry, "a press that only closes a list was read as an exit")
    }

    @Test
    fun anAlreadyClosedListIsNotSomethingBackAnswersFor() {
        // A term typed and a list that has been put away is not a
        // reason to swallow the gesture.
        val s = AppState().typedCardName(suggesting().closed())
        assertNull(s.back(), "a closed list still ate the press")
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
        val once = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(View.STATS).back()
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

    // ------------------------------------------------- leaving with work

    @Test
    fun backThatWouldLeaveWithAPastedListFlagsIt() {
        val s = AppState().copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        assertNull(s.back(), "the fixture is not on a bare default view")
        assertTrue(s.wouldExitWithUnsavedEntry, "an unsent list did not flag the exit")
    }

    @Test
    fun backThatWouldLeaveWithNothingPastedDoesNotFlagIt() {
        val s = AppState()
        assertNull(s.back(), "the fixture is not on a bare default view")
        assertFalse(s.wouldExitWithUnsavedEntry, "an empty box flagged an exit anyway")
    }

    @Test
    fun backThatWouldLeaveAfterAnAppliedListDoesNotFlagIt() {
        val s = AppState().copy(entry = MassEntry().copy(list = "1 Sol Ring", result = Applied(applied = true)))
        assertNull(s.back(), "the fixture is not on a bare default view")
        assertFalse(s.wouldExitWithUnsavedEntry, "a list already applied still flagged the exit")
    }

    @Test
    fun aPastedListDoesNotFlagAPressThatOnlyMovesWithinTheApp() {
        // Reported as "leaving the mass entry screen", not "pressing
        // back anywhere" — a press that is only coming off a card, an
        // overlay or an open deck is not the one that loses the list,
        // so it must not be the one that gets flagged.
        val s = readingACardFromThatDeck().copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        assertFalse(s.wouldExitWithUnsavedEntry, "a press that only leaves a card flagged an exit")
    }
}
