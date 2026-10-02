package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renaming a deck.
 *
 * The slug is the address. It moves with the name, so the box has to
 * say where the deck is about to live before anything is written —
 * every link anybody has to it is about to change.
 */
class RenameTest {

    private fun opened() = RenameState(slug = "fairy-deck", was = "Fairy Deck")

    @Test
    fun itOpensOnTheNameItAlreadyHas() {
        val s = opened()
        assertEquals("Fairy Deck", s.name)
        assertEquals("fairy-deck", s.nextSlug)
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
    fun theNewAddressIsShownBeforeAnythingIsWritten() {
        assertEquals("alela-flyers", opened().typed("Alela Flyers").nextSlug)
    }

    @Test
    fun whitespaceAloneIsNotARename() {
        assertFalse(opened().typed("   ").canSave)
        assertFalse(opened().typed(" Fairy Deck ").canSave, "the same name padded is still the same name")
    }

    @Test
    fun aNameWithNothingToMakeASlugFromIsRefused() {
        val s = opened().typed("!!!")
        assertEquals("", s.nextSlug)
        assertFalse(s.canSave, "a name with no letters or digits was accepted")
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
    fun theSlugFollowsTheSameRuleTheWizardUses() {
        assertEquals(NewDeck.slugify("Kayla's Big Deck"), opened().typed("Kayla's Big Deck").nextSlug)
    }

    @Test
    fun closingItForgetsIt() {
        val app = AppState(rename = opened()).opening(Overlay.RENAME)
        assertNull(app.dismissTop()?.rename, "the rename box was left holding a name")
    }
}
