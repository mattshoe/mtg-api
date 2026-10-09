package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The card name box is bound to `complete.term`, not to `filters.q`.
 *
 * So anything that changes the filters from somewhere other than the
 * box — "Reset everything" above all — has to move the box too, or the
 * search is cleared and the old name sits above it looking applied.
 */
class NameBoxFollowsFiltersTest {

    private fun typed(name: String) = AppState().typedCardName(Completion().typed(name))

    @Test
    fun resetEverythingEmptiesTheNameBox() {
        val s = typed("bolt")
        val reset = s.filtered(s.library.where(Filters()))
        assertEquals("", reset.library.filters.q)
        assertEquals("", reset.complete.term, "reset cleared the search and left the name in the box")
    }

    @Test
    fun andPutsTheSuggestionListAway() {
        val s = typed("bolt").copy(complete = Completion().typed("bolt").suggested(listOf("Lightning Bolt")))
        val reset = s.filtered(s.library.where(Filters()))
        assertFalse(reset.complete.open, "the suggestions for a cleared name stayed open")
    }

    @Test
    fun aFilterThatLeavesTheNameAloneLeavesTheBoxAlone() {
        val s = typed("bolt").copy(complete = Completion().typed("bolt").suggested(listOf("Lightning Bolt")))
        val next = s.filtered(s.library.where(s.library.filters.copy(colors = listOf("R"))))
        assertEquals(s.complete, next.complete)
    }
}
