package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A card you can send somebody.
 *
 * It was a drawer whose address rode in the query string of whatever
 * page it opened over, so a link to a card carried the deck somebody
 * happened to have open, back had to guess between dismissing and
 * navigating, and the page behind kept its own scroll. It is a
 * destination now: `#/card/matt:sol+ring`, naming the card and
 * nothing else.
 *
 * It does not name an owner either. It used to, which gave the same
 * card two addresses and hid Kayla's copies from Matt's page.
 *
 * These are the cases that make the encoding worth having: a name
 * with a colon in it, one with a slash, and one with an accent.
 */
class CardRefTest {

    @Test
    fun aCardGoesIntoTheAddressAndComesBackOut() {
        val ref = CardRef("sol ring")
        assertEquals(ref, CardRef.parse(ref.encoded()))
    }

    @Test
    fun aColonInTheNameIsEncodedNotLeftInThePath() {
        val ref = CardRef("nahiri: the lithomancer")
        assertEquals("nahiri%3A+the+lithomancer", ref.encoded())
    }

    @Test
    fun aSlashInTheNameSurvivesThePath() {
        val ref = CardRef("nahiri: the lithomancer // x")
        val round = CardRef.parse(ref.encoded())
        assertEquals(ref, round)
        assertEquals("nahiri: the lithomancer // x", round?.nameNorm)
    }

    @Test
    fun anAccentSurvivesTheTrip() {
        val ref = CardRef("jötun grunt")
        assertEquals(ref, CardRef.parse(ref.encoded()))
    }

    @Test
    fun rubbishIsNoCardRatherThanAWrongOne() {
        assertNull(CardRef.parse(null))
        assertNull(CardRef.parse(""))
        assertNull(CardRef.parse("   "))
    }

    // ------------------------------------------------- as a destination

    private val ref = CardRef("sol ring")

    private fun opened(from: AppState = AppState()) = from.openCard(ref, "Sol Ring")

    @Test
    fun aCardIsItsOwnAddressAndNothingElses() {
        assertEquals("#/card/sol+ring", opened().hash())
        assertEquals(ref, CardRef.parse(Route.parse(opened().hash()).rest))
    }

    @Test
    fun aLinkToACardDoesNotCarryTheDeckItWasOpenedFrom() {
        // The whole reason it moved out of the query string.
        val fromADeck = opened(AppState().navigate(Route(View.DECKS, "alela")))
        assertEquals("#/card/sol+ring", fromADeck.hash())
        assertTrue("alela" !in fromADeck.hash(), fromADeck.hash())
    }

    @Test
    fun aLinkToACardDoesNotCarryTheSearchEither() {
        val fromASearch = opened(AppState().copy(library = Library(Filters(q = "sol", colors = listOf("C")))))
        assertEquals("#/card/sol+ring", fromASearch.hash())
    }

    @Test
    fun theAddressSaysWhichCardTheStateIsShowing() {
        assertEquals(ref, opened().cardRef)
        assertNull(AppState().cardRef)
    }

    @Test
    fun aCardHeldWhileSomewhereElseIsNotTheCardOnScreen() {
        // The detail survives a navigation away in memory; the
        // address is what decides what is being shown.
        val elsewhere = opened().navigate(Route(View.STATS)).copy(card = opened().card)
        assertNull(elsewhere.cardRef)
    }

    // --------------------------------------------------- back, and ← Back

    @Test
    fun openingACardIsAStepBackCanUndo() {
        assertTrue(opened().isAStepFrom(AppState()))
    }

    @Test
    fun anotherCardIsAnotherStep() {
        // Tapping a card in the "in decks" list is going somewhere,
        // so back comes back to the card you were reading.
        val first = opened()
        val second = first.openCard(CardRef("arcane signet"), "Arcane Signet")
        assertTrue(second.isAStepFrom(first))
    }

    @Test
    fun backGoesToThePageTheCardWasOpenedFrom() {
        val fromADeck = opened(AppState().navigate(Route(View.DECKS, "alela")))
        assertEquals(Route(View.DECKS, "alela"), fromADeck.from)
        assertEquals("#/decks/alela", fromADeck.leaveCard().hash())
    }

    @Test
    fun aCardOpenedFromALinkGoesBackToTheLibrary() {
        // Nothing behind it, so "back" cannot mean the page before.
        val fromALink = AppState().navigate(Route(View.CARD, ref.encoded()))
        assertNull(fromALink.from)
        assertEquals(View.DEFAULT, fromALink.leaveCard().view)
    }

    @Test
    fun theDeckIsStillThereWhenYouComeBackToIt() {
        // Opening a card off a deck list used to close the deck,
        // which meant reading all of its cards again on the way back.
        val deck = AppState().navigate(Route(View.DECKS, "alela"))
            .copy(decks = DecksState(decks = listOf(Deck("alela", "Alela", "matt", null, "UB", null, null))))
            .let { it.copy(decks = it.decks.opened("alela", listOf(DeckCard("Sol Ring", 1, null, 1)))) }
        val card = deck.openCard(ref, "Sol Ring")
        assertEquals(1, card.decks.cards.size, "the deck's cards were thrown away")
        assertEquals("alela", card.leaveCard().decks.openSlug)
    }

    @Test
    fun aCardIsNotSomewhereTheMenuOffers() {
        // It is reached from a card, not from a list of places to go.
        assertTrue(View.CARD !in Admin(token = "t").visible)
        assertTrue(Admin().reachable(View.CARD), "a link to a card must open while locked")
    }

    @Test
    fun aCardsRouteIsItsEncodedNameUnderTheCardView() {
        val route = CardRef("nahiri: the lithomancer").route()
        assertEquals(View.CARD, route.view)
        assertEquals("nahiri%3A+the+lithomancer", route.rest)
    }
}
