package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing asks whose collection it is any more.
 *
 * Matt: "Now that we've changed the paradigm, we need to clean up all
 * the 'who' crap all over the app."
 *
 * It was a real question when there were two collections and a
 * password that could write to either: somebody adding cards had to
 * say which pile they were going on. An account owns one collection,
 * the server refuses a write to anybody else's, and the answer is
 * therefore never in doubt — so asking is a step that can only be got
 * wrong.
 *
 * Three places asked. The entry wizard had a whole step for it, the
 * new deck wizard had another, and the stats page had a Both / Matt /
 * Kayla switch. All three are gone, and the collection comes from
 * what is on screen.
 */
class NoMoreWhoseTest {

    @Test
    fun theWizardIsThreeStepsAndNoneOfThemAskWhose() {
        assertEquals(listOf(Step.WHICH, Step.LIST, Step.REVIEW), Step.wizard)
        assertFalse(Step.entries.any { it.label == "Whose" })
    }

    @Test
    fun aListIsPreviewableAsSoonAsItIsAList() {
        // It used to need an owner as well, which was the only thing
        // the third step collected.
        val s = MassEntry().choose(Direction.ADD).type("4 Lightning Bolt")
        assertTrue(s.canPreview)
    }

    @Test
    fun continuingFromTheListGoesStraightToTheReview() {
        val s = MassEntry().choose(Direction.ADD).type("4 Lightning Bolt")
            .previewed(Applied(dryRun = true, resolved = 4))
        assertEquals(Step.REVIEW, s.step)
    }

    @Test
    fun theNewDeckWizardDoesNotAskEither() {
        assertFalse(DeckStep.entries.any { it.label == "Whose" })
        assertFalse(NewDeck().steps.any { it.label == "Whose" })
    }

    @Test
    fun aFormatAndANameIsEnoughToGetToTheCards() {
        val s = NewDeck().pick(Format.MODERN).rename("Burn")
        assertTrue(s.canLeaveName)
        assertEquals(DeckStep.CARDS, s.goTo(DeckStep.CARDS).step)
    }

    @Test
    fun statsAreTheCollectionYouAreLookingAtAndNotAChoice() {
        // The switch said Both / Matt / Kayla. "Both" is not a
        // collection anybody owns, and the other two were a list of
        // the only two people there would ever be.
        val me = Account(key = "e7de0cb1", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t"))
        assertEquals("e7de0cb1", s.statsScope().owner)
        assertEquals("bprh3d2s", s.browsing("bprh3d2s").statsScope().owner)
    }

    @Test
    fun aVisitorWhoNamedNobodyStillSeesEverything() {
        assertEquals(null, AppState().statsScope().owner)
    }

    @Test
    fun anOldStatsLinkThatNamesACollectionStillMeansThatCollection() {
        // `#/stats/kayla` was how the switch spelled itself into the
        // address. A collection is named by its key now, so the same
        // shape of link, `#/stats/<key>`, still names that collection
        // even though nothing offers the choice. (An old bookmark
        // holding a slug names nobody: there are no slugs to match.)
        val me = Account(key = "e7de0cb1", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t"))
            .navigate(Route(View.STATS, "bprh3d2s"))
        assertEquals("bprh3d2s", s.statsScope().owner)
    }

}
