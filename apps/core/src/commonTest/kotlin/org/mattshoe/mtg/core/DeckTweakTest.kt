package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maintaining a deck one card at a time.
 *
 * What is checked here is the list that goes to the server, because
 * that is the whole mechanism: there is no per-card endpoint, a
 * change is the deck's own list with one line different, and the
 * server diffs it exactly as it diffs a full rewrite.
 */
class DeckTweakTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1, role: String? = null) =
        DeckCard(name, qty, role, qty, nameNorm = name.lowercase())

    private val cards = listOf(
        card("Alela, Cunning Conqueror", role = "commander"),
        card("Sol Ring"),
        card("Counterspell", qty = 2),
        card("Swamp", qty = 10),
    )

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    private fun list(t: DeckTweak) = t.listAfter(cards)

    // ----------------------------------------------------------- adding

    @Test
    fun addingACardPutsItOnTheEnd() {
        val t = DeckTweak.add(deck, "Alela, Cunning Conqueror").picked(bolt).count(1)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp\n1 Lightning Bolt", list(t))
    }

    @Test
    fun addingOneTheDeckAlreadyHoldsIsOneMoreOfIt() {
        // Not a second row. The server would fold them anyway, and a
        // list that says the same card twice reads as a mistake.
        val t = DeckTweak.add(deck, "x").picked(Found(2, "Counterspell", null, null, 4, "matt")).count(2)
        assertEquals("1 Sol Ring\n4 Counterspell\n10 Swamp", list(t))
    }

    @Test
    fun theCommanderIsNeverInTheList() {
        // It travels in its own field, so writing it into the list
        // would make the server see a second copy of it.
        val t = DeckTweak.add(deck, "Alela, Cunning Conqueror").picked(bolt)
        assertTrue("Alela" !in list(t), list(t))
    }

    // --------------------------------------------------------- removing

    @Test
    fun removingACardTakesTheWholeLineOut() {
        val t = DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.REMOVE)
        assertEquals("1 Sol Ring\n10 Swamp", list(t))
    }

    @Test
    fun removingSomethingTheDeckDoesNotHaveChangesNothing() {
        val t = DeckTweak.on(deck, "x", card("Black Lotus"), Tweak.REMOVE)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", list(t))
    }

    // ------------------------------------------------------- how many

    @Test
    fun changingTheCountRewritesThatLineOnly() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY).count(7)
        assertEquals("1 Sol Ring\n2 Counterspell\n7 Swamp", list(t))
    }

    @Test
    fun takingTheCountToNoughtIsARemoval() {
        val t = DeckTweak.on(deck, "x", card("Sol Ring"), Tweak.QUANTITY).count(0)
        assertEquals("2 Counterspell\n10 Swamp", list(t))
    }

    @Test
    fun aCountThatHasNotChangedIsNotReady() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY)
        assertFalse(t.ready, "nothing to do, but the button was live")
        assertTrue(t.count(9).ready)
    }

    // --------------------------------------------------------- swapping

    @Test
    fun aSwapPutsTheNewCardWhereTheOldOneWas() {
        // In place, so a list read top to bottom does not reshuffle
        // every time one card changes.
        val t = DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.SWAP).picked(bolt)
        assertEquals("1 Sol Ring\n2 Lightning Bolt\n10 Swamp", list(t))
    }

    @Test
    fun aSwapTakesAsManyAsItGives() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).picked(bolt)
        assertTrue("10 Lightning Bolt" in list(t), list(t))
    }

    @Test
    fun aSwapCanChangeHowManyAsWell() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).picked(bolt).count(3)
        assertEquals("1 Sol Ring\n2 Counterspell\n3 Lightning Bolt", list(t))
    }

    @Test
    fun swappingIntoACardTheDeckAlreadyHoldsAddsToIt() {
        val t = DeckTweak.on(deck, "x", card("Sol Ring"), Tweak.SWAP)
            .picked(Found(3, "Counterspell", null, null, 4, "matt"))
        assertEquals("3 Counterspell\n10 Swamp", list(t))
    }

    // ------------------------------------------------- what it will say

    @Test
    fun everyChangeSaysWhatItIsInOneLine() {
        assertEquals(
            "Add 2× Lightning Bolt",
            DeckTweak.add(deck, "x").picked(bolt).count(2).summary,
        )
        assertEquals(
            "Counterspell → Lightning Bolt",
            DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.SWAP).picked(bolt).summary,
        )
        assertEquals(
            "Remove 2× Counterspell",
            DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.REMOVE).summary,
        )
        assertEquals(
            "Swamp: 10 → 7",
            DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY).count(7).summary,
        )
    }

    // ------------------------------------------------------- the gates

    @Test
    fun nothingIsPlannedUntilACardIsNamed() {
        val t = DeckTweak.add(deck, "x")
        assertFalse(t.ready, "an add with no card was ready to go")
        assertTrue(t.picked(bolt).ready)
    }

    @Test
    fun nothingIsWrittenUntilThePlanIsBack() {
        val t = DeckTweak.add(deck, "x").picked(bolt)
        assertFalse(t.canApply, "it would have written without a dry run")
        assertTrue(t.planned(DeckPlan()).canApply)
    }

    @Test
    fun pickingADifferentCardThrowsAwayThePlan() {
        // The plan was about the other card. Applying it would write
        // something nobody approved.
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan())
        assertTrue(t.canApply)
        assertFalse(t.picked(Found(9, "Opt", null, null, 1, "matt")).canApply)
    }

    @Test
    fun changingHowManyThrowsAwayThePlanToo() {
        val t = DeckTweak.add(deck, "x").picked(bolt).count(1).planned(DeckPlan())
        assertFalse(t.count(4).canApply)
    }

    @Test
    fun aChangeThatHasBeenWrittenCannotBeWrittenAgain() {
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan()).finished()
        assertFalse(t.canApply)
        assertTrue(t.saved)
    }

    // ------------------------------------------------ finding the card

    @Test
    fun whatIsOwnedComesFirst() {
        val t = DeckTweak.add(deck, "x").searched(
            listOf(bolt),
            listOf("Lightning Helix", "Lightning Strike"),
        )
        assertEquals(
            listOf("Lightning Bolt", "Lightning Helix", "Lightning Strike"),
            t.found.map { it.name },
        )
        assertEquals(4, t.found[0].qty, "the owned one lost its count")
        assertEquals(0, t.found[1].qty, "a card nobody owns reads as owned")
    }

    @Test
    fun aCardNobodyOwnsCanStillBePutInADeck() {
        // That is what the plan calls "to buy". A finder that only
        // lists owned cards cannot be used to add one.
        val t = DeckTweak.add(deck, "x").searched(emptyList(), listOf("Black Lotus"))
        assertEquals(listOf("Black Lotus"), t.found.map { it.name })
        assertTrue(t.picked(t.found.single()).ready)
    }

    @Test
    fun theSameCardIsNotOfferedTwice() {
        // Scryfall knows every card, including the ones in the
        // collection, so the two lists overlap by definition.
        val t = DeckTweak.add(deck, "x").searched(listOf(bolt), listOf("lightning bolt", "Lightning Helix"))
        assertEquals(listOf("Lightning Bolt", "Lightning Helix"), t.found.map { it.name })
    }
}
