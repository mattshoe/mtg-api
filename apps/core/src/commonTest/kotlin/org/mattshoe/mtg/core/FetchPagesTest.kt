package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Fetch list as two pages.
 *
 * Matt: "the root page should have the entry box and the saved
 * fetches", "searching should take you to a NEW page", "i should be
 * able to check off cards as i find them", "the checked boxes should
 * Persist", and "editing should not remove existing check boxes".
 */
class FetchPagesTest {

    private val list = "1 Counterspell\n1 Arcane Signet"

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private fun build(text: String) = FetchList.build(DeckList.entries(text), owned)

    /** On the root, with a list typed into the box. */
    private fun typed(text: String = list) =
        AppState(route = Route(View.FETCH)).let { it.copy(fetch = it.fetch.drafted(text)) }

    /** Find pressed and the collection answered. */
    private fun found(s: AppState = typed()): AppState {
        val going = s.findingFetch()
        return going.copy(fetch = going.fetch.loading().found(build(going.fetch.list)))
    }

    @Test
    fun findTakesYouToTheListPageWithTheListTyped() {
        val s = typed().findingFetch()
        assertEquals(Route(View.FETCH, FetchListState.PAGE), s.route, "Find did not go to a page of its own")
        assertTrue(s.onFetchList, "the list page does not know it is the list page")
        assertEquals(list, s.fetch.list, "the typed list did not come along")
        assertEquals("", s.fetch.draft, "the root's box still holds the list it sent")
    }

    @Test
    fun theRootIsTheEntryBoxAndNotTheList() {
        assertFalse(typed().onFetchList, "the root reads as the list page")
    }

    @Test
    fun backFromTheListPageIsTheRoot() {
        assertEquals(Route(View.FETCH), found().back()?.route)
    }

    @Test
    fun aCardCanBeTickedAndUnticked() {
        val ticked = found().fetch.tick("Counterspell")
        assertTrue(ticked.isTicked("Counterspell"))
        assertFalse(ticked.isTicked("Arcane Signet"))
        assertFalse(ticked.tick("Counterspell").isTicked("Counterspell"), "a second tap did not untick it")
    }

    @Test
    fun theProgressCountsTicksAgainstEveryCardOnTheList() {
        val f = found().fetch.tick("Counterspell")
        assertEquals("1 of 2 found", f.progress)
    }

    @Test
    fun ticksOnAnUnsavedListComeBackAfterARestart() {
        val store = Store.inMemory()
        val f = found().fetch.tick("Counterspell")
        FetchListState.keep(store, f.current)
        val back = FetchListState.restore(store)
        assertEquals(list, back.list, "the open list was lost")
        assertTrue(back.isTicked("Counterspell"), "the tick was lost")
        assertEquals(2, back.result?.sections?.sumOf { it.cards.size }, "the filing was lost")
    }

    @Test
    fun ticksOnASavedListAreKeptWithIt() {
        val store = Store.inMemory()
        val saved = found().fetch.save("Alela", "2026-10-12T00:00:00Z").tick("Arcane Signet")
        FetchListState.persist(store, saved.saved)
        val back = FetchListState.restore(store)
        val opened = back.open(back.saved.single().id)
        assertTrue(opened.isTicked("Arcane Signet"), "the saved list forgot its tick")
    }

    @Test
    fun openingASavedListGoesToTheListPage() {
        val saved = found().fetch.save("Alela", "2026-10-12T00:00:00Z")
        val root = AppState(route = Route(View.FETCH), fetch = saved.cleared())
        val s = root.openFetch(saved.saved.single().id)
        assertTrue(s.onFetchList)
        assertEquals("Alela", s.fetch.title)
    }

    @Test
    fun editingKeepsTheTicks() {
        val s = found()
        val editing = s.copy(fetch = s.fetch.tick("Counterspell").edit())
        assertEquals(list, editing.fetch.editing, "Edit did not start from the list")
        val changed = editing.copy(fetch = editing.fetch.editTyped("$list\n1 Sol Ring"))
        val refound = found(changed)
        assertEquals("$list\n1 Sol Ring", refound.fetch.list)
        assertNull(refound.fetch.editing, "still editing after Find")
        assertTrue(refound.onFetchList, "an edit left the list page")
        assertTrue(refound.fetch.isTicked("Counterspell"), "editing removed a tick")
        assertEquals("1 of 3 found", refound.fetch.progress)
    }

    @Test
    fun cancellingAnEditLeavesTheListAsItWas() {
        val s = found()
        val f = s.fetch.edit().editTyped("1 Sol Ring").cancelEdit()
        assertEquals(list, f.list)
        assertNull(f.editing)
    }

    @Test
    fun backWhileEditingStopsEditingAndStaysOnThePage() {
        val s = found().let { it.copy(fetch = it.fetch.edit()) }
        val b = s.back()
        assertTrue(b?.onFetchList == true, "back while editing left the list page")
        assertNull(b?.fetch?.editing)
    }

    @Test
    fun editingASavedListUpdatesTheSavedOne() {
        val s = found().let { it.copy(fetch = it.fetch.save("Alela", "2026-10-12T00:00:00Z")) }
        val changed = s.copy(fetch = s.fetch.edit().editTyped("1 Sol Ring"))
        val refound = found(changed)
        assertEquals(listOf("1 Sol Ring"), refound.fetch.saved.map { it.list }, "the saved list kept the old text")
    }

    @Test
    fun aNewListFromTheRootStartsWithNoTicks() {
        val first = found().let { it.copy(fetch = it.fetch.tick("Counterspell")) }.back()!!
        val second = found(first.copy(fetch = first.fetch.drafted("1 Counterspell")))
        assertFalse(second.fetch.isTicked("Counterspell"), "a new list came with the old list's ticks")
    }

    @Test
    fun aFailedFindLeavesTheListPageSayingSoAndNotSpinning() {
        val s = typed().findingFetch().fetching(View.FETCH)
        assertTrue(s.fetch.busy)
        val failed = s.fetchFailed("HTTP 500", View.FETCH)
        assertFalse(failed.fetch.busy, "the spinner is still up")
        assertEquals("HTTP 500", failed.fetch.error)
        assertTrue(failed.onFetchList)
        val again = failed.findingFetch()
        assertEquals(list, again.fetch.list, "trying again lost the list")
    }
}
