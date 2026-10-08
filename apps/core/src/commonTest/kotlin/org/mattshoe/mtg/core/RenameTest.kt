package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renaming a deck.
 *
 * The name and only the name. A deck lives at its key, which is
 * random and never worked out from the name, so a rename does not
 * move it and every link anybody has to it keeps working.
 */
class RenameTest {

    private fun opened() = RenameState(key = "q8ytka9m", was = "Fairy Deck")

    @Test
    fun itOpensOnTheNameItAlreadyHas() {
        val s = opened()
        assertEquals("Fairy Deck", s.name)
        assertEquals("q8ytka9m", s.key)
    }

    @Test
    fun thereIsNothingToSaveUntilSomethingChanges() {
        assertFalse(opened().canSave, "it offered to rename a deck to its own name")
    }

    @Test
    fun aNewNameIsSaveable() {
        assertTrue(opened().typed("Alela Flyers").canSave)
    }

    @Test
    fun theAddressStaysWhereItIsWhateverTheNameBecomes() {
        val typed = opened().typed("Alela Flyers")
        assertEquals("q8ytka9m", typed.key, "typing a new name moved the deck")
        assertEquals("q8ytka9m", typed.working().finished().key, "saving a new name moved the deck")
        assertEquals("q8ytka9m", typed.failed("no").key)
    }

    @Test
    fun whitespaceAloneIsNotARename() {
        assertFalse(opened().typed("   ").canSave)
        assertFalse(opened().typed(" Fairy Deck ").canSave, "the same name padded is still the same name")
    }

    @Test
    fun nothingIsOfferedTwiceWhileItIsBeingWritten() {
        assertFalse(opened().typed("Alela Flyers").working().canSave)
    }

    @Test
    fun norAfterItIsDone() {
        assertFalse(opened().typed("Alela Flyers").finished().canSave)
        assertTrue(opened().typed("Alela Flyers").finished().done)
    }

    @Test
    fun aFailureLetsYouTryAgainWithoutLosingWhatYouTyped() {
        val s = opened().typed("Alela Flyers").working().failed("already taken")
        assertEquals("Alela Flyers", s.name)
        assertEquals("already taken", s.error)
        assertTrue(s.canSave, "a refusal locked the box")
    }

    @Test
    fun typingAgainClearsTheLastComplaint() {
        val s = opened().typed("Alela Flyers").failed("already taken").typed("Alela Fliers")
        assertNull(s.error)
    }

    @Test
    fun closingItForgetsIt() {
        val app = AppState(rename = opened()).opening(Overlay.RENAME)
        assertNull(app.dismissTop()?.rename, "the rename box was left holding a name")
    }
}
