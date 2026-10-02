package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The number on the tweak sheet, and the two ends it stops at.
 *
 * `count` is one line of state but it decides three things at once:
 * what the server is told, whether the Preview button is live, and
 * whether a plan that is already on screen still describes the change
 * it was asked for. The ends matter because the number comes from a
 * text box as well as two buttons — a thumb on the wrong key is a
 * four-digit purchase, and a backspace is a card leaving the deck.
 */
class TweakCounterCoreTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1) =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase())

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    private fun counting(have: Int) =
        DeckTweak.on(deck, "x", card("Swamp", have), Tweak.QUANTITY)

    private fun adding() = DeckTweak.add(deck, "x").picked(bolt)

    // ------------------------------------------------------- the floor

    @Test
    fun theNumberNeverGoesBelowNought() {
        assertEquals(0, counting(3).count(-1).qty)
        assertEquals(0, counting(3).count(-9999).qty)
    }

    @Test
    fun aCountCanGoToNoughtBecauseThatTakesTheCardOut() {
        // Nought is a real answer here: the line comes out of the list.
        val t = counting(3)
        assertEquals(0, t.floor)
        assertTrue(t.canTakeOne)
        assertEquals(0, t.count(0).qty)
        assertFalse(t.count(0).canTakeOne, "the minus was still live at the floor")
    }

    @Test
    fun butAddingNoughtOfSomethingIsNotAChangeSoItStopsAtOne() {
        val t = adding()
        assertEquals(1, t.floor)
        assertFalse(t.canTakeOne, "an add offered to add nought copies")
        assertEquals(1, t.count(0).qty)
        assertEquals(1, t.count(-5).qty)
    }

    @Test
    fun norDoesASwapSwapInNothing() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 4), Tweak.SWAP).picked(bolt)
        assertEquals(1, t.floor)
        assertEquals(1, t.count(0).qty)
    }

    // ----------------------------------------------------- the ceiling

    @Test
    fun theNumberStopsShortOfAHundred() {
        // The box takes typing, so 9999 is one slipped thumb away and
        // the plan that comes back is a 9999-card purchase.
        assertEquals(DeckTweak.MAX_QTY, counting(3).count(9999).qty)
        assertEquals(99, DeckTweak.MAX_QTY)
    }

    @Test
    fun thePlusGoesOffAtTheCeiling() {
        val full = counting(3).count(DeckTweak.MAX_QTY)
        assertFalse(full.canAddOne, "the plus was still live at the ceiling")
        assertTrue(full.canTakeOne)
        assertTrue(counting(3).canAddOne)
    }

    // ------------------------------------------------- and the plan

    @Test
    fun aRealChangeThrowsAwayThePlan() {
        val t = counting(10).count(7).planned(DeckPlan())
        assertTrue(t.canApply)
        assertFalse(t.count(6).canApply, "a plan for 7 would have been applied as 6")
    }

    @Test
    fun aNumberThatDidNotMoveKeepsThePlan() {
        // A plus at the ceiling, or a 999 clamped back — the number is
        // the one the plan is about, so killing the plan only makes
        // the Apply button go dead for no visible reason.
        val t = counting(10).count(DeckTweak.MAX_QTY).planned(DeckPlan())
        assertTrue(t.count(DeckTweak.MAX_QTY + 1).canApply, "the ceiling ate the plan")
        assertTrue(t.count(9999).canApply)
        assertSame(t, t.count(DeckTweak.MAX_QTY), "the same number made a new state")
    }

    @Test
    fun andAFloorThatDidNotMoveKeepsItToo() {
        val t = adding().count(1).planned(DeckPlan())
        assertTrue(t.count(0).canApply, "the floor ate the plan")
        assertEquals(1, t.count(0).qty)
    }

    // ------------------------------------------------ stepping about

    @Test
    fun steppingUpAndBackDownLandsWhereItStarted() {
        var t = counting(4)
        repeat(5) { t = t.count(t.qty + 1) }
        assertEquals(9, t.qty)
        repeat(5) { t = t.count(t.qty - 1) }
        assertEquals(4, t.qty)
    }

    @Test
    fun theNumberTheButtonsShowIsTheNumberTheServerGets() {
        // Whatever the clamping did, the list that goes out says it.
        val t = counting(10).count(9999)
        assertTrue("${DeckTweak.MAX_QTY} Swamp" in t.listAfter(listOf(card("Swamp", 10))), t.listAfter(listOf(card("Swamp", 10))))
        assertTrue("${t.qty} Swamp" in t.listAfter(listOf(card("Swamp", 10))))
    }

    @Test
    fun aCountOfNoughtStillMeansTheCardLeaves() {
        // The floor is nought for a reason, and that reason is this.
        val t = counting(3).count(0)
        assertEquals("", t.listAfter(listOf(card("Swamp", 3))))
        assertTrue(t.ready, "taking a card out was not a change worth previewing")
    }
}
