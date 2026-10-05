package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Going backwards through the entry wizard.
 *
 * Matt: "the back button doesn't work after starting the flow. The
 * back button needs to take you to previous steps in the flow as you
 * would expect. I entered some cards, navigated back then forward,
 * then deleted the cards, and suddenly the back stopped working. The
 * back-nav in that whole flow was really wonky and doing weird things
 * a lot."
 *
 * Two separate faults, both here rather than in either UI.
 *
 * `goTo` clamps every move to "the earliest step still unanswered",
 * which is the right rule for a jump forwards and is nonsense applied
 * to a step back. Asking for WHICH from LIST with an empty box landed
 * on LIST, because the box was empty; asking for WHICH from WHO with
 * a full box landed on WHO, because nobody had been named. So the
 * "← Back" button either did nothing or moved forward, and which of
 * the two depended on what you had just deleted.
 *
 * And `AppState.back()` had never heard of the wizard at all: the
 * system back gesture on the Entry screen went straight to the
 * Library from any step.
 */
class EntryBackTest {

    private fun onList() = MassEntry().choose(Direction.ADD).goTo(Step.LIST)

    private fun onWho() = onList().type("4 Lightning Bolt").goTo(Step.WHO)

    // ------------------------------------------------- the wizard's own back

    @Test
    fun backFromTheListReachesTheFirstQuestion() {
        assertEquals(Step.WHICH, onList().goTo(Step.WHICH).step)
    }

    @Test
    fun backStillWorksAfterEmptyingTheBox() {
        // The exact sequence Matt described: cards in, forward, back,
        // cards deleted. The empty box used to pin you to the List
        // step because the clamp read it as the unanswered question.
        val emptied = onWho().goTo(Step.LIST).type("")
        assertEquals(Step.LIST, emptied.step, "the fixture is not where this test thinks")
        assertEquals(Step.WHICH, emptied.goTo(Step.WHICH).step, "back stopped working")
    }

    @Test
    fun backFromWhoReachesTheList() {
        assertEquals(Step.LIST, onWho().goTo(Step.LIST).step)
    }

    @Test
    fun backFromReviewReachesWhoEvenWithNoDryRunLeft() {
        val reviewing = onWho().assign(Owner.MATT).previewed(Applied())
        assertEquals(Step.REVIEW, reviewing.step)
        assertEquals(Step.WHO, reviewing.goTo(Step.WHO).step)
    }

    @Test
    fun aJumpForwardIsStillClampedToWhatIsMissing() {
        // The rule that made the clamp worth having. Asking for the
        // last step with nothing filled in lands on the first thing
        // actually missing, rather than on a screen with no answers
        // behind it.
        assertEquals(Step.WHICH, MassEntry().goTo(Step.REVIEW).step)
        assertEquals(Step.LIST, onList().goTo(Step.REVIEW).step)
        assertEquals(Step.WHO, onWho().goTo(Step.REVIEW).step)
    }

    @Test
    fun steppingBackThrowsAwayTheDryRun() {
        val reviewing = onWho().assign(Owner.MATT).previewed(Applied())
        assertNotNull(reviewing.preview)
        assertNull(reviewing.goTo(Step.LIST).preview, "a stale dry run survived a step back")
    }

    // ------------------------------------------- the system back gesture

    @Test
    fun theBackGestureStepsTheWizardRatherThanLeavingIt() {
        val s = AppState(admin = Admin(token = "t").unlock("t"))
            .navigate(View.ENTRY)
            .let { it.copy(entry = it.entry.choose(Direction.ADD).goTo(Step.LIST)) }
        val back = s.back()
        assertNotNull(back, "back walked out of the app from inside the wizard")
        assertEquals(View.ENTRY, back.view, "back left the Entry screen mid-wizard")
        assertEquals(Step.WHICH, back.entry.step)
    }

    @Test
    fun theBackGestureWalksAllTheWayOutOneStepAtATime() {
        var s = AppState(admin = Admin(token = "t").unlock("t"))
            .navigate(View.ENTRY)
            .let { it.copy(entry = it.entry.choose(Direction.ADD).type("4 Bolt").goTo(Step.WHO)) }
        s = assertNotNull(s.back()); assertEquals(Step.LIST, s.entry.step)
        s = assertNotNull(s.back()); assertEquals(Step.WHICH, s.entry.step)
        // Only once there is no step left does it leave the screen.
        s = assertNotNull(s.back()); assertEquals(View.DEFAULT, s.view)
    }

    @Test
    fun theFirstStepIsNotATrap() {
        val s = AppState(admin = Admin(token = "t").unlock("t")).navigate(View.ENTRY)
        assertEquals(View.DEFAULT, assertNotNull(s.back()).view)
    }

    @Test
    fun backOffTheReceiptLeavesRatherThanReopeningTheWizard() {
        // DONE is an ending, not a step: going "back" into Review
        // from a receipt would offer to apply a list that has
        // already been applied.
        val s = AppState(admin = Admin(token = "t").unlock("t"))
            .navigate(View.ENTRY)
            .let { it.copy(entry = it.entry.choose(Direction.ADD).finished(Applied())) }
        assertEquals(View.DEFAULT, assertNotNull(s.back()).view)
    }

    @Test
    fun anOverlayStillGoesFirst() {
        val s = AppState(admin = Admin(token = "t").unlock("t"))
            .navigate(View.ENTRY)
            .let { it.copy(entry = it.entry.choose(Direction.ADD).goTo(Step.LIST)) }
            .opening(Overlay.PALETTE)
        val back = assertNotNull(s.back())
        assertEquals(Step.LIST, back.entry.step, "back stepped the wizard under an open overlay")
    }
}
