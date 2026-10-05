package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Library screen's state and row decoding, on every platform.
 *
 * Paging arithmetic and "is there a next page" are the kind of thing two
 * hand-written implementations get subtly different, so there is one.
 */
class LibraryTest {

    private fun rowsOf(vararg json: String) = json.map { Json.parseToJsonElement(it) as JsonArray }

    private val cols = listOf(
        "id", "owner", "name", "name_norm", "face2", "layout", "scryfall_id", "mana_cost",
        "cmc", "type_line", "color_identity", "rarity", "setcode", "set_name",
        "collector_number", "edhrec_rank", "released_at", "finish", "power", "toughness",
        "artist", "qty", "printings", "free", "price", "value",
    )

    private fun oneRow() = rowsOf(
        """[12,"matt","Sol Ring","sol ring",null,"normal","abc","{1}",1,"Artifact","",
            "uncommon","M3C","Modern Horizons 3","409",123,"2024-06-14","nonfoil",null,null,
            "Mike Bierek",3,2,1,2.5,7.5]""".replace("\n", "").replace("  ", ""),
    )

    // --------------------------------------------------------- decoding

    @Test
    fun aRowDecodesByColumnName() {
        val card = Rows.cards(cols, oneRow()).single()
        assertEquals(12L, card.id)
        assertEquals("matt", card.owner)
        assertEquals("Sol Ring", card.name)
        assertEquals(1.0, card.cmc)
        assertEquals(3, card.qty)
        assertEquals(2, card.printings)
        assertEquals(1, card.free)
        assertEquals(2.5, card.price)
        assertEquals(7.5, card.value)
        assertTrue(card.isFree)
    }

    /**
     * Read positionally, a dropped column shifts every field left and the
     * result still parses — silently wrong. By name it just goes null.
     */
    @Test
    fun aMissingColumnIsNullRatherThanTheNextFieldAlong() {
        val fewer = cols.filterNot { it == "price" }
        val row = rowsOf(
            """[12,"matt","Sol Ring","sol ring",null,"normal","abc","{1}",1,"Artifact","",
               "uncommon","M3C","Modern Horizons 3","409",123,"2024-06-14","nonfoil",null,null,
               "Mike Bierek",3,2,1,7.5]""".replace("\n", "").replace("  ", ""),
        )
        val card = Rows.cards(fewer, row).single()
        assertNull(card.price)
        assertEquals("Mike Bierek", card.artist, "the other columns must not shift")
        assertEquals(3, card.qty)
    }

    @Test
    fun nullsStayNull() {
        val row = rowsOf("[1,\"matt\",\"X\",\"x\",null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,0,0,null,null,null]")
        val card = Rows.cards(cols, row).single()
        assertNull(card.price)
        assertNull(card.free)
        assertNull(card.cmc)
        assertFalse(card.isFree)
    }

    @Test
    fun aTwoFacedCardShowsBothNames() {
        val card = Rows.cards(cols, oneRow()).single().copy(face2 = "Reverse")
        assertEquals("Sol Ring // Reverse", card.fullName)
    }

    @Test
    fun aTwoFacedCardDoesNotShowItsBackTwice() {
        // What the database actually holds. `cards.name` is the whole
        // printed name, both halves, and `cards.face2` is the second
        // half again — so joining them put the back face on twice:
        // "Brazen Borrower // Petty Theft // Petty Theft" on every
        // tile, every alt text and every exported list.
        //
        // The test above has been green since the day it was written
        // and never saw this, because it builds a row the query
        // cannot return: a name with one face in it and a `face2`
        // beside it. An end-to-end journey against the real fixture
        // is what found it.
        val card = Rows.cards(cols, oneRow()).single()
            .copy(name = "Brazen Borrower // Petty Theft", face2 = "Petty Theft")
        assertEquals("Brazen Borrower // Petty Theft", card.fullName)
    }

    @Test
    fun colorIdentityIsReadLetterByLetter() {
        val card = Rows.cards(cols, oneRow()).single().copy(colorIdentity = "UW")
        assertEquals(listOf("U", "W"), card.colors)
        assertEquals(emptyList(), Rows.cards(cols, oneRow()).single().copy(colorIdentity = null).colors)
    }

    /** A value with some copies unpriced is a floor, and the screen says so. */
    @Test
    fun aValueIsPartialOnlyWhenSomeCopiesHaveNoPrice() {
        val card = Rows.cards(cols, oneRow()).single()
        assertFalse(card.copy(unpriced = 0, value = 7.5).valueIsPartial)
        assertTrue(card.copy(unpriced = 1, value = 7.5).valueIsPartial)
        // Nothing priced at all is not a partial total either.
        assertFalse(card.copy(unpriced = 1, value = null).valueIsPartial)
    }

    @Test
    fun aCountQueryDecodesToOneNumber() {
        assertEquals(6607, Rows.count(rowsOf("[6607]")))
        assertEquals(0, Rows.count(emptyList()))
    }

    @Test
    fun aSingleColumnQueryDecodesToAList() {
        assertEquals(listOf("2X2", "M3C"), Rows.column(rowsOf("[\"2X2\"]", "[\"M3C\"]")))
    }

    // ----------------------------------------------------------- paging

    @Test
    fun pagesAreRoundedUp() {
        assertEquals(67, Library(total = 6607).pages)
        assertEquals(1, Library(total = 1).pages)
        assertEquals(1, Library(total = 0).pages, "an empty result is still one page, not zero")
        assertEquals(1, Library(total = 100).pages)
        assertEquals(2, Library(total = 101).pages)
    }

    @Test
    fun theRangeShownReadsTheWayAPersonWouldSayIt() {
        val s = Library(total = 6607, filters = Filters(page = 2))
        assertEquals(101..200, s.showing)
        assertEquals(6601..6607, Library(total = 6607, filters = Filters(page = 67)).showing)
        assertTrue(Library(total = 0).showing.isEmpty())
    }

    @Test
    fun theShownLabelMatchesTheRangeUnlessThereIsNothing() {
        val s = Library(total = 6607, filters = Filters(page = 2))
        assertEquals("101–200 of 6607", s.showingLabel)
        assertEquals("No cards", Library(total = 0).showingLabel)
    }

    @Test
    fun nextAndPreviousStopAtTheEnds() {
        val first = Library(total = 250)
        assertFalse(first.hasPrev)
        assertTrue(first.hasNext)
        assertEquals(1, first.prev().page, "page zero is not a page")

        val last = Library(total = 250, filters = Filters(page = 3))
        assertTrue(last.hasPrev)
        assertFalse(last.hasNext)
        assertEquals(3, last.next().page, "there is no page four")
    }

    @Test
    fun jumpingBeyondTheEndLandsOnTheLastPage() {
        assertEquals(3, Library(total = 250).goToPage(99).page)
        assertEquals(1, Library(total = 250).goToPage(-5).page)
    }

    /** Page 9 of a result that now has two looks broken. */
    @Test
    fun changingAFilterReturnsToPageOne() {
        val deep = Library(total = 6607, filters = Filters(page = 40))
        assertEquals(1, deep.where(deep.filters.copy(q = "bolt")).page)
    }

    @Test
    fun pickingFromADropdownLeavesTheDirectionAlone() {
        // sortedBy is for a dropdown: picking "Price" twice in a row
        // must not quietly reverse it the way the button's sortBy does.
        val s = Library(filters = Filters(sort = Sort.NAME, descending = false)).sortedBy(Sort.CMC)
        assertEquals(Sort.CMC, s.filters.sort)
        assertFalse(s.filters.descending)
        // Picking the very same column again still does not flip it.
        assertFalse(s.sortedBy(Sort.CMC).filters.descending)
        assertEquals(1, s.page, "changing the sort is a filter change too")
    }

    @Test
    fun sortingByTheSameColumnFlipsDirection() {
        val s = Library().sortBy(Sort.NAME)
        assertEquals(Sort.NAME, s.filters.sort)
        assertTrue(s.filters.descending)
        assertFalse(s.sortBy(Sort.NAME).filters.descending)
        assertTrue(s.sortBy(Sort.NAME).sortBy(Sort.NAME).filters.descending)
    }

    @Test
    fun sortingByADifferentColumnStartsDescending() {
        val s = Library().sortBy(Sort.NAME).sortBy(Sort.NAME)   // now ascending
        assertTrue(s.sortBy(Sort.CMC).filters.descending)
    }

    @Test
    fun sortingReturnsToPageOne() {
        assertEquals(1, Library(total = 999, filters = Filters(page = 5)).sortBy(Sort.CMC).page)
    }

    // ------------------------------------------------------------ state

    @Test
    fun anEmptyResultIsDistinctFromLoadingAndFromFailing() {
        assertFalse(Library().loading().isEmpty)
        assertFalse(Library().failed("nope").isEmpty)
        assertTrue(Library().loaded(emptyList(), 0).isEmpty)
    }

    /**
     * `fresh` is what decides whether coming back to the Library has
     * to ask again or can just show what is already on screen. Rows
     * loaded for a different filter, or sat behind an error, are not
     * fresh even though they are not empty either.
     */
    @Test
    fun freshMeansTheRowsOnScreenAnswerTheFilterOnScreen() {
        val loaded = Library(filters = Filters(q = "bolt")).loaded(emptyList(), 0)
        assertTrue(loaded.fresh)
        assertFalse(loaded.where(Filters(q = "sol")).fresh, "the filter moved on but the rows have not")
        assertFalse(loaded.failed("HTTP 500").fresh, "an error is not a fresh answer")
    }

    @Test
    fun aFailureKeepsTheFiltersItFailedWith() {
        val s = Library(filters = Filters(q = "bolt")).loading().failed("HTTP 500")
        assertEquals("bolt", s.filters.q)
        assertFalse(s.busy)
        assertEquals("HTTP 500", s.error)
    }

    @Test
    fun theTwoQueriesAgreeOnTheFilterAndDifferOnTheShape() {
        val (page, count) = Library(filters = Filters(q = "bolt", page = 2)).queries()
        assertTrue(page.sql.contains("LIMIT 100 OFFSET 100"))
        assertTrue(count.sql.startsWith("SELECT COUNT(*)"))
        assertEquals(page.params, count.params, "the count must be of the same search")
    }
}
