package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryHistoryTest {

    private fun entry(n: Int) = HistoryEntry(
        at = "2026-09-2${n % 10}T10:00:00Z",
        direction = "add",
        owner = "matt",
        count = n,
        list = "$n Sol Ring",
    )

    @Test
    fun newestFirst() {
        val h = EntryHistory().remember(entry(1)).remember(entry(2))
        assertEquals(listOf(2, 1), h.entries.map { it.count })
    }

    @Test
    fun itStopsAtThirty() {
        var h = EntryHistory()
        repeat(40) { h = h.remember(entry(it)) }
        assertEquals(EntryHistory.MAX, h.entries.size)
        assertEquals(39, h.entries.first().count)
    }

    @Test
    fun onlyTwelveAreShown() {
        var h = EntryHistory()
        repeat(20) { h = h.remember(entry(it)) }
        assertEquals(EntryHistory.SHOWN, h.recent.size)
    }

    @Test
    fun reusePutsTheListBackReadyToGo() {
        // The row remembers which collection it went to, and the
        // wizard does not ask: a reused list lands in the collection
        // being looked at, which is the only one the server will
        // take a write for.
        val e = HistoryEntry("2026-09-28T10:00:00Z", "remove", "kayla", 3, "3 Sol Ring")
        val entry = EntryHistory().reuse(e)
        assertEquals("3 Sol Ring", entry.list)
        assertEquals(Direction.REMOVE, entry.direction)
        assertEquals(Step.LIST, entry.step)
        assertTrue(entry.canPreview, "a reused list still needs a question answered")
    }

    @Test
    fun itSurvivesARoundTripThroughAStore() {
        val store = Store.inMemory()
        val h = EntryHistory().remember(entry(1)).remember(entry(2))
        EntryHistory.save(store, h)
        assertEquals(h, EntryHistory.load(store))
    }

    @Test
    fun rubbishInTheStoreIsNoHistoryRatherThanACrash() {
        val store = Store.inMemory()
        store.put(EntryHistory.KEY, "{not json")
        assertTrue(EntryHistory.load(store).isEmpty)
    }

    @Test
    fun clearingEmptiesTheStoreToo() {
        val store = Store.inMemory()
        EntryHistory.save(store, EntryHistory().remember(entry(1)))
        EntryHistory.save(store, EntryHistory())
        assertNull(store.get(EntryHistory.KEY))
    }

    @Test
    fun clearedIsAnEmptyHistoryRatherThanJustAnEmptyStore() {
        val h = EntryHistory().remember(entry(1)).remember(entry(2))
        assertTrue(h.cleared().isEmpty)
        assertEquals(EntryHistory(), h.cleared())
    }

    @Test
    fun aRowKnowsWhetherItWasAnAddOrARemoval() {
        assertTrue(entry(1).copy(direction = "add").isAdd)
        assertFalse(entry(1).copy(direction = "remove").isAdd)
    }

    @Test
    fun aRowCanNameBackTheOwnerItWasEnteredFor() {
        assertEquals("matt", entry(1).copy(owner = "matt").asOwner())
        // A row old enough to predate an owner being required at all.
        assertNull(entry(1).copy(owner = "").asOwner())
    }

    @Test
    fun aFinishedEntryBecomesARow() {
        val done = MassEntry()
            .choose(Direction.ADD)
            .type("2 Sol Ring\n1 Arcane Signet")
            .finished(Applied(applied = true, resolved = 2))
        val state = AppState(entry = done).browsing("kayla").recordEntry("2026-09-28T10:00:00Z")
        assertEquals(1, state.history.entries.size)
        val row = state.history.entries.first()
        assertEquals("add", row.direction)
        assertEquals("kayla", row.owner)
        assertEquals(2, row.count)
    }

    @Test
    fun anUnfinishedEntryIsNotRecorded() {
        val half = MassEntry().choose(Direction.ADD).type("2 Sol Ring")
        assertTrue(AppState(entry = half).recordEntry("2026-09-28T10:00:00Z").history.isEmpty)
    }
}

class UploadTest {

    @Test
    fun aFileNeverEatsWhatWasTyped() {
        assertEquals(
            "1 Sol Ring\n2 Arcane Signet",
            Upload.merge("1 Sol Ring\n", "2 Arcane Signet"),
        )
        assertEquals("2 Arcane Signet", Upload.merge("   ", "2 Arcane Signet"))
    }

    @Test
    fun twoMegabytesIsTheCeiling() {
        assertFalse(Upload.tooBig(2L * 1024 * 1024))
        assertTrue(Upload.tooBig(2L * 1024 * 1024 + 1))
    }

    @Test
    fun theSizeReadsLikeASize() {
        assertEquals("2.4 MB", Upload.size(2_400_000))
        assertEquals("0.5 MB", Upload.size(500_000))
    }

    @Test
    fun itSaysWhatCameOffDisk() {
        val csv = "Name,Set code,Quantity\nSol Ring,m3c,2\nArcane Signet,m3c,1"
        assertEquals("box.csv — CSV, 2 cards", Upload.describe(listOf("box.csv"), csv))
        assertEquals(
            "a.txt — decklist, 1 card",
            Upload.describe(listOf("a.txt"), "1 Sol Ring"),
        )
    }
}
