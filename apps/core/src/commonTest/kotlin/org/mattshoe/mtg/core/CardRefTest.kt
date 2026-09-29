package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A card you can send somebody.
 *
 * The drawer is an overlay, so it was not part of any URL — open a
 * card, copy the address, and what you had sent was the page behind
 * it. These are the cases that made the obvious fix wrong: a name
 * with a colon in it, a name with a space, and the address going back
 * to what it was when the drawer shuts.
 */
class CardRefTest {

    @Test
    fun aCardGoesIntoTheAddressAndComesBackOut() {
        val ref = CardRef("matt", "sol ring")
        assertEquals(ref, CardRef.parse(ref.encoded()))
    }

    @Test
    fun theSeparatorIsTheOnlyLiteralColon() {
        // So `indexOf(':')` finds the separator and not a colon that
        // is part of the name.
        val ref = CardRef("matt", "nahiri: the lithomancer")
        assertEquals("matt:nahiri%3A+the+lithomancer", ref.encoded())
    }

    @Test
    fun aColonInTheNameDoesNotSplitTheReferenceInTwo() {
        // "Chandra, Torch of Defiance" is fine; a planeswalker deck
        // card called "Nahiri: the Lithomancer" is not, and neither is
        // anything with a slash in it.
        val ref = CardRef("kayla", "nahiri: the lithomancer // x")
        val round = CardRef.parse(ref.encoded())
        assertEquals(ref, round)
        assertEquals("nahiri: the lithomancer // x", round?.nameNorm)
    }

    @Test
    fun anAccentSurvivesTheTrip() {
        val ref = CardRef("matt", "jötun grunt")
        assertEquals(ref, CardRef.parse(ref.encoded()))
    }

    @Test
    fun rubbishIsNoCardRatherThanAWrongOne() {
        assertNull(CardRef.parse(null))
        assertNull(CardRef.parse(""))
        assertNull(CardRef.parse("noseparator"))
        assertNull(CardRef.parse(":no owner"))
        assertNull(CardRef.parse("matt:"))
    }

    @Test
    fun itIsFoundInAQueryStringWithEverythingElseInIt() {
        val q = "q=bolt&colors=G%2CU&card=matt:sol+ring&sort=price"
        assertEquals(CardRef("matt", "sol ring"), CardRef.from(q))
        assertNull(CardRef.from("q=bolt&sort=price"))
    }

    @Test
    fun itIsAppendedToWhateverTheRouteAlreadySays() {
        val ref = CardRef("matt", "sol ring")
        // A route with a query already, and one without.
        assertEquals("#/search?q=bolt&card=matt:sol+ring", CardRef.appendTo("#/search?q=bolt", ref))
        assertEquals("#/stats?card=matt:sol+ring", CardRef.appendTo("#/stats", ref))
        assertEquals("#/stats", CardRef.appendTo("#/stats", null))
    }

    // ------------------------------------------------- through the state

    private fun opened() = AppState()
        .copy(card = CardDetail(name = "Sol Ring", owner = "matt", nameNorm = "sol ring"))
        .opening(Overlay.CARD)

    @Test
    fun anOpenCardIsInTheAppsOwnAddress() {
        val hash = opened().hash()
        assertTrue(hash.contains("card=matt:sol+ring"), hash)
        assertEquals(CardRef("matt", "sol ring"), CardRef.from(hash.substringAfter('?', "")))
    }

    @Test
    fun andItLeavesTheAddressWhenTheDrawerShuts() {
        val shut = opened().dismissTop()!!
        assertNull(shut.cardRef)
        assertTrue(!shut.hash().contains("card="), shut.hash())
    }

    @Test
    fun theSearchUnderneathIsStillInTheLinkAsWell() {
        // Both halves, or sharing a card loses the search you found it
        // in.
        val s = opened().copy(library = Library(Filters(q = "sol", colors = listOf("C"))))
        val hash = s.hash()
        assertTrue(hash.contains("q=sol"), hash)
        assertTrue(hash.contains("card=matt:sol+ring"), hash)
        assertEquals("sol", FilterUrl.fromHash(hash.substringAfter('?')).q)
    }

    @Test
    fun aCardOpenOverADeckIsAlsoALink() {
        val s = AppState().navigate(Route(View.DECKS, "alela"))
            .copy(card = CardDetail(name = "Sol Ring", owner = "matt", nameNorm = "sol ring"))
            .opening(Overlay.CARD)
        assertEquals("#/decks/alela?card=matt:sol+ring", s.hash())
    }

    // --------------------------------------------- back, and the X

    @Test
    fun theHistoryDoesNotGiveTheCardAnEntryOfItsOwn() {
        // Two things both owning "back closes the card" is why Close
        // did not. The drawer shut, the overlay history popped an
        // entry, and the entry it popped to still said `card=` — so
        // the hashchange that followed opened it straight back up.
        assertEquals(0, opened().overlays.historyDepth)
        assertEquals(1, AppState().opening(Overlay.CHEATSHEET).overlays.historyDepth)
        assertEquals(
            1,
            AppState().opening(Overlay.CHEATSHEET).opening(Overlay.CARD).overlays.historyDepth,
        )
    }

    @Test
    fun openingACardIsAStepBackCanUndo() {
        val shut = AppState()
        val open = opened()
        assertTrue(open.opensACardOver(shut))
        // Closing is not: it rewrites where you are, so back does not
        // land on an address that still names the card.
        assertTrue(!shut.opensACardOver(open))
        // Nor is changing a filter with one already open.
        val moved = open.copy(library = Library(Filters(q = "x")))
        assertTrue(!moved.opensACardOver(open))
    }

    @Test
    fun aDifferentCardOverTheFirstIsNotANewStep() {
        // Tapping a card inside the drawer's "in decks" list replaces
        // it. One entry, not one per card looked at.
        val first = opened()
        val second = first.copy(
            card = CardDetail(name = "Arcane Signet", owner = "matt", nameNorm = "arcane signet"),
        )
        assertTrue(!second.opensACardOver(first))
    }

    @Test
    fun aCardHeldButNotOpenIsNotInTheAddress() {
        // `navigate` clears the card, but a state that still holds one
        // with the overlay closed must not advertise it.
        val s = AppState().copy(card = CardDetail(name = "Sol Ring", owner = "matt", nameNorm = "sol ring"))
        assertNull(s.cardRef)
    }
}
