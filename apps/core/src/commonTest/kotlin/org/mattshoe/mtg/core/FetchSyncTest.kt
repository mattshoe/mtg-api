package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The saved Fetch lists, once the account's copy has come back.
 *
 * Matt: "making a fetch list on web does not show on android and vice
 * versa". The Worker keeps the account's set; these are the rules for
 * what a device does with it.
 */
class FetchSyncTest {

    private val r = FetchResult(sections = emptyList(), missing = emptyList())
    private fun saved(id: String, title: String, ticked: Set<String> = emptySet()) =
        SavedFetch(id, title, "2026-10-17T12:00:00Z", "1 Counterspell", r, ticked)

    @Test
    fun theFirstTimeADeviceSyncsItsOwnListsAreKeptAlongsideTheAccounts() {
        val here = FetchListState(saved = listOf(saved("p", "From the phone")))
        val s = here.adopted(listOf(saved("w", "From the website")), merge = true)
        assertEquals(listOf("From the website", "From the phone"), s.saved.map { it.title },
            "a list made on this device before syncing was lost, or the account's never arrived")
    }

    @Test
    fun afterThatTheAccountsListsAreTheLists() {
        val here = FetchListState(saved = listOf(saved("a", "Alela"), saved("b", "Bulk")))
        val s = here.adopted(listOf(saved("b", "Bulk")), merge = false)
        assertEquals(listOf("Bulk"), s.saved.map { it.title }, "a list deleted on the other device came back")
    }

    @Test
    fun theSameIdOnBothSidesIsTheAccountsVersion() {
        val here = FetchListState(saved = listOf(saved("a", "Alela")))
        val s = here.adopted(listOf(saved("a", "Alela", setOf("Counterspell"))), merge = true)
        assertEquals(1, s.saved.size, "one list listed twice")
        assertEquals(setOf("Counterspell"), s.saved.single().ticked)
    }

    @Test
    fun theOpenListPicksUpTicksMadeOnTheOtherDevice() {
        val here = FetchListState(saved = listOf(saved("a", "Alela"))).open("a")
        val s = here.adopted(listOf(saved("a", "Alela", setOf("Counterspell"))), merge = false)
        assertEquals(setOf("Counterspell"), s.ticked, "the page still shows the old ticks")
        assertEquals("a", s.openId)
    }

    @Test
    fun anOpenListDeletedElsewhereStaysOnThePageUnsaved() {
        val here = FetchListState(saved = listOf(saved("a", "Alela"))).open("a")
        val s = here.adopted(emptyList(), merge = false)
        assertNull(s.openId, "the page still says it is saved")
        assertEquals("1 Counterspell", s.list, "the list on the page was taken away")
    }

    @Test
    fun anEditInProgressIsNotOverwritten() {
        val here = FetchListState(saved = listOf(saved("a", "Alela"))).open("a").edit().editTyped("2 Sol Ring")
        val s = here.adopted(listOf(saved("a", "Alela", setOf("Counterspell"))), merge = false)
        assertEquals("2 Sol Ring", s.editing, "the edit being typed was thrown away")
    }

    @Test
    fun itIsSyncedOnlyOnceTheAccountHasAnswered() {
        val before = FetchListState()
        assertFalse(before.synced, "synced before anything was heard")
        val after = before.adopted(emptyList(), merge = false)
        assertTrue(after.synced)
        assertTrue(after.cleared().synced, "clearing the page forgot the lists are the account's")
    }

    @Test
    fun aListFiledUnderTheOldColourSectionsIsKeptButNotShownAsFiled() {
        val old = SavedFetch("o", "Old", "", "1 Counterspell",
            FetchResult(listOf(FetchSection(FetchType.INSTANT, "U", FetchRarity.COMMON, emptyList(), legacy = "BLUE"))))
        val s = FetchListState().adopted(listOf(old), merge = false)
        assertNull(s.saved.single().result, "an old filing is shown as if it were right")
    }

    @Test
    fun aDeviceMergesOnlyTheFirstTimeAnyAccountSyncsOnIt() {
        val store = Store.inMemory()
        assertTrue(FetchListState.firstSync(store), "a device that has never synced did not merge its own lists in")
        FetchListState.syncedAs(store, "m4tt0001")
        assertFalse(FetchListState.firstSync(store),
            "a second account signing in here would have had the first one's lists merged into theirs")
    }

    @Test
    fun theFailuresSayWhatHappened() {
        val loaded = AppState().fetchListsFailedToLoad("D1 is down")
        assertEquals("Your saved Fetch lists did not load: D1 is down", loaded.toast)
        assertTrue(loaded.toastFailed, "a failure toast styled as a success")
        val sent = AppState().fetchListsFailedToSave("offline")
        assertEquals("Your Fetch lists did not save to your account: offline", sent.toast)
        assertTrue(sent.toastFailed)
    }
}
