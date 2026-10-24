package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An entry flow that has finished is over. It does not ask for more.
 *
 * Matt: "when a 'entry' flow of any kind finishes, DO NOT fucking ask
 * for more you dipshit just exit the fucking flow back to home".
 *
 * The entry wizard ended on a receipt with "Enter more" as its only
 * button, and the deck wizard ended on "Created" with a "Done" button.
 * Both are one more screen between finishing and getting on with it.
 */
class EntryFlowsLeaveWhenDoneTest {

    private fun change(n: Int, before: Int, after: Int) =
        Change("Card $n", "fra", "$n", "nonfoil", before, after)

    private fun applying(direction: Direction = Direction.ADD) = AppState().copy(
        route = Route(View.ENTRY),
        sharedList = "1 Card 1",
        entry = MassEntry(direction = direction).type("1 Card 1\n2 Card 2"),
    )

    @Test
    fun aCleanWriteLeavesTheWizardForTheLibrary() {
        val left = applying().entryApplied(
            Applied(applied = true, changes = listOf(change(1, 0, 1), change(2, 1, 3))),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals(View.LIBRARY, left.view, "it stayed in the wizard after the write landed")
        assertEquals(MassEntry(), left.entry, "the finished run is still in the wizard for next time")
        assertNull(left.sharedList, "the share is still waiting to be used")
        assertEquals(1, left.history.entries.size, "the write was not recorded in the history")
        assertNull(left.library.loadedFor, "the Library still believes rows from before the write")
    }

    @Test
    fun whatMovedIsSaidOnTheWayOut() {
        val added = applying().entryApplied(
            Applied(applied = true, changes = listOf(change(1, 0, 1), change(2, 1, 3))),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals("Added 3 copies of 2 printings", added.toast)
        assertFalse(added.toastFailed, "a write that landed was styled as a failure")

        val removed = applying(Direction.REMOVE).entryApplied(
            Applied(applied = true, changes = listOf(change(1, 1, 0))),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals("Removed 1 copy of 1 printing", removed.toast)
    }

    @Test
    fun aWriteThatMovedNothingSaysSoAndStillLeaves() {
        val left = applying(Direction.REMOVE).entryApplied(
            Applied(applied = true, changes = emptyList()),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals(View.LIBRARY, left.view)
        assertEquals("Nothing changed", left.toast)
    }

    @Test
    fun cardsTheServerCouldNotResolveKeepTheReceiptOnScreen() {
        // Leaving here would throw away the only place that says which
        // cards were dropped. That is not asking for more; it is the
        // answer to what went wrong.
        val stayed = applying().entryApplied(
            Applied(
                applied = true,
                failed = 1,
                changes = listOf(change(1, 0, 1)),
                errors = listOf("Sol Rong: no such card"),
            ),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals(View.ENTRY, stayed.view, "it left before saying which cards were dropped")
        assertEquals(Step.DONE, stayed.entry.step)
        assertEquals(1, stayed.history.entries.size)
    }

    @Test
    fun leavingTheReceiptGoesToTheLibraryWithNothingLeftBehind() {
        val receipt = applying().entryApplied(
            Applied(applied = true, failed = 1, errors = listOf("Sol Rong: no such card")),
            now = "2026-10-24T17:00:00Z",
        )
        val left = receipt.leftEntry()
        assertEquals(View.LIBRARY, left.view)
        assertEquals(MassEntry(), left.entry)
    }

    @Test
    fun aCreatedDeckClosesTheWizardAndOpensTheDeck() {
        val s = AppState().copy(route = Route(View.DECKS))
            .opening(Overlay.NEW_DECK)
            .let { it.copy(newDeck = it.newDeck.pick(Format.COMMANDER).finished("k3y4b2c1")) }
        val left = s.deckCreated("k3y4b2c1", "Alela")
        assertFalse(Overlay.NEW_DECK in left.overlays, "the deck wizard is still open over the deck")
        assertEquals(NewDeck(), left.newDeck, "the finished wizard is still held for next time")
        assertEquals(Route(View.DECKS, "k3y4b2c1"), left.route, "it did not land on the deck it made")
        assertEquals("Created Alela", left.toast)
    }

    @Test
    fun aDeckStartedFromTheEntryWizardLeavesThatWizardToo() {
        val s = AppState().copy(
            route = Route(View.ENTRY),
            entry = MassEntry().type("1 Sol Ring").startADeck(),
        ).startingADeck()
        val left = s.deckCreated("k3y4b2c1", "Alela")
        assertEquals(View.DECKS, left.view)
        assertEquals(MassEntry(), left.entry, "the entry wizard still holds the list the deck was made from")
        assertTrue(left.toast.orEmpty().isNotEmpty())
    }
}
