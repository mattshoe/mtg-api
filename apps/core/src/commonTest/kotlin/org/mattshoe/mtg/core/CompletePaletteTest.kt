package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionTest {

    private val three = Completion().typed("sol").suggested(listOf("Sol Ring", "Sol Talisman", "Solemn"))

    @Test
    fun oneCharacterIsNotWorthAsking() {
        assertFalse(Completion().typed("s").worthAsking)
        assertTrue(Completion().typed("so").worthAsking)
    }

    @Test
    fun droppingBelowTheMinimumClearsWhatWasShowing() {
        val back = three.typed("s")
        assertTrue(back.isEmpty)
        assertFalse(back.open)
    }

    @Test
    fun nothingIsHighlightedUntilYouMove() {
        assertEquals(-1, three.active)
        assertNull(three.highlighted)
    }

    @Test
    fun theHighlightWrapsBothWays() {
        assertEquals("Sol Ring", three.down().highlighted)
        assertEquals("Sol Talisman", three.down().down().highlighted)
        // Off the bottom comes back to the top.
        assertEquals("Sol Ring", three.down().down().down().down().highlighted)
        // And up from nothing lands on the last.
        assertEquals("Solemn", three.up().highlighted)
    }

    @Test
    fun upFromTheMiddleJustStepsBackOne() {
        // The wrap only matters off the very top. From anywhere else
        // up is just "one sooner" — the plain decrement, not the wrap.
        val middle = three.down().down()
        assertEquals("Sol Ring", middle.up().highlighted)
    }

    @Test
    fun aPointerCanJumpStraightToAnEntry() {
        assertEquals("Sol Talisman", three.highlight(1).highlighted)
        // Off the end of the list is not a position to jump to.
        assertEquals(three, three.highlight(9))
    }

    @Test
    fun closingTheListAlsoDropsTheHighlight() {
        val shut = three.down().closed()
        assertFalse(shut.open)
        assertNull(shut.highlighted, "a closed list should not remember where you were")
    }

    @Test
    fun pickingPutsTheNameInTheBoxAndClosesTheList() {
        val (next, name) = three.pick(1)
        assertEquals("Sol Talisman", name)
        assertEquals("Sol Talisman", next.term)
        assertFalse(next.open)
    }

    @Test
    fun pickingNothingIsNotAnError() {
        val (next, name) = three.pick(9)
        assertNull(name)
        assertEquals(three, next)
    }

    @Test
    fun anEmptyAnswerDoesNotOpenAnEmptyList() {
        assertFalse(Completion().typed("zzzz").suggested(emptyList()).open)
    }

    @Test
    fun itNeverShowsMoreThanTen() {
        val many = (1..40).map { "Card $it" }
        assertEquals(Completion.LIMIT, Completion().typed("card").suggested(many).items.size)
    }
}

class PaletteStateTest {

    private fun found(name: String) = Found(1, name, null, "Artifact", 2, "matt")

    private val three = PaletteState().opened().typed("bo")
        .found(listOf(found("Bolt"), found("Bolt Bend"), found("Boltwave")))

    @Test
    fun theFirstRowIsChosenUntilYouMove() {
        assertEquals("Bolt", three.chosen?.name)
    }

    @Test
    fun theHighlightStopsAtBothEnds() {
        // Unlike autocomplete this one does not wrap: the palette is a
        // short list you scan, and wrapping past the end reads as a bug.
        assertEquals("Boltwave", three.down().down().down().down().chosen?.name)
        assertEquals("Bolt", three.up().chosen?.name)
    }

    @Test
    fun closingEmptiesIt() {
        val shut = three.closed()
        assertFalse(shut.open)
        assertEquals("", shut.term)
        assertTrue(shut.items.isEmpty())
    }

    @Test
    fun blankIsNotWorthARoundTrip() {
        assertFalse(PaletteState().typed("   ").worthAsking)
        assertTrue(PaletteState().typed("bo").worthAsking)
    }

    @Test
    fun itLooksAtBothFacesAndBothOwners() {
        val sql = PaletteQueries.find("Bolt").sql
        assertTrue(sql.contains("name_norm LIKE ?"), sql)
        assertTrue(sql.contains("lower(face1) LIKE ?"), sql)
        assertTrue(sql.contains("lower(face2) LIKE ?"), sql)
        assertTrue(sql.contains("GROUP BY owner_id, name_norm"), sql)
        // Shortest first: typing "bolt" should offer Lightning Bolt
        // before Bolt Bend.
        assertTrue(sql.contains("ORDER BY length(name), name"), sql)
        assertTrue(sql.contains("LIMIT 12"), sql)
    }

    @Test
    fun theTermIsBoundNotPasted() {
        val sql = PaletteQueries.find("O'Brien; DROP")
        assertEquals(3, sql.params.size)
        assertEquals("%o'brien; drop%", sql.params.first())
        assertFalse(sql.sql.contains("DROP"))
    }
}

/** A name taken off the list is an answer, not another question. */
class PickedNameTest {

    private val list = Completion().typed("Vesu").suggested(listOf("Vesuva", "Vesuvan Mist"))

    @Test
    fun pickingClosesTheList() {
        val (next, name) = list.pick(1)
        assertEquals("Vesuvan Mist", name)
        assertEquals("Vesuvan Mist", next.term)
        assertFalse(next.open, "the list is still open")
    }

    @Test
    fun andDoesNotGoAndAskAboutItself() {
        // Picking put the name in the box, the box asked Scryfall
        // about the name, and the answer was the name — so the list
        // reopened over the field with the one thing already in it.
        val (next, _) = list.pick(0)
        assertFalse(next.worthAsking, "it went straight back to ask about the name it just took")
    }

    @Test
    fun butTypingAfterwardsAsksAgain() {
        val (next, _) = list.pick(0)
        assertTrue(next.typed("Vesuvan").worthAsking, "editing the name stopped suggesting anything")
    }
}
