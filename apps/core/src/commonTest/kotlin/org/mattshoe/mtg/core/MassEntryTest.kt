package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules, checked once and enforced on every platform.
 *
 * These run on the JVM, on Android, in a browser and on iOS from this one
 * file. A UI that lets you reach Apply early does not fail here — it
 * cannot happen, because the UI does not decide. What these protect is
 * the decision itself.
 */
class MassEntryTest {

    private val listOfTwo = "1 Sol Ring\n2 Lightning Bolt (2X2) 117"

    @Test
    fun startsWithNothingChosen() {
        val s = MassEntry()
        assertEquals(Step.WHICH, s.step)
        assertNull(s.direction)
        assertNull(s.owner)
        assertFalse(s.canLeaveWhich)
    }

    @Test
    fun aDirectionIsNeededBeforeAnythingElse() {
        val s = MassEntry()
        assertEquals(Step.WHICH, s.goTo(Step.LIST).step)
        assertEquals(Step.WHICH, s.goTo(Step.WHO).step)
        assertEquals(Step.WHICH, s.goTo(Step.REVIEW).step)
    }

    @Test
    fun anEmptyListGoesNoFurther() {
        val s = MassEntry().choose(Direction.ADD)
        assertTrue(s.canLeaveWhich)
        assertFalse(s.canLeaveList)
        assertEquals(Step.LIST, s.goTo(Step.WHO).step)
    }

    @Test
    fun anOversizeListGoesNoFurther() {
        val huge = (1..MassEntry.MAX_CARDS + 1).joinToString("\n") { "1 Card $it" }
        val s = MassEntry().choose(Direction.ADD).type(huge)
        assertTrue(s.overLimit)
        assertFalse(s.canLeaveList)
        assertEquals(Step.LIST, s.goTo(Step.WHO).step)
    }

    @Test
    fun neitherOwnerIsAssumed() {
        val s = MassEntry().choose(Direction.REMOVE).type(listOfTwo)
        assertNull(s.owner)
        assertFalse(s.canPreview)
        assertEquals(Step.WHO, s.goTo(Step.REVIEW).step)
    }

    @Test
    fun applyIsUnreachableUntilTheServerHasSaidWhatItWouldDo() {
        val ready = MassEntry()
            .choose(Direction.ADD)
            .type(listOfTwo)
            .assign(Owner.MATT)

        assertTrue(ready.canPreview)
        assertFalse(ready.canApply, "a write was reachable with no dry run behind it")

        val previewed = ready.previewed(
            Applied(dryRun = true, resolved = 2, changes = listOf(change())),
        )
        assertTrue(previewed.canApply)
        assertEquals(Step.REVIEW, previewed.step)
    }

    @Test
    fun aPreviewThatResolvedNothingOffersNoWrite() {
        val s = MassEntry().choose(Direction.ADD).type("1 Biterblosom").assign(Owner.MATT)
            .previewed(Applied(dryRun = true, resolved = 0, errors = listOf("no card named Biterblosom")))
        assertFalse(s.canApply)
    }

    @Test
    fun applyIsNotOfferedTwice() {
        val done = MassEntry().choose(Direction.ADD).type(listOfTwo).assign(Owner.KAYLA)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
            .finished(Applied(applied = true, changes = listOf(change())))
        assertEquals(Step.DONE, done.step)
        assertFalse(done.canApply)
    }

    @Test
    fun editingTheListThrowsAwayTheDryRunItWasTakenAgainst() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo).assign(Owner.MATT)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        assertTrue(s.canApply)

        val edited = s.type("$listOfTwo\n1 Opt")
        assertNull(edited.preview)
        assertFalse(edited.canApply, "a stale dry run still authorised a write")
    }

    @Test
    fun changingTheOwnerThrowsAwayTheDryRunToo() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo).assign(Owner.MATT)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        val moved = s.assign(Owner.KAYLA)
        assertNull(moved.preview)
        assertFalse(moved.canApply)
    }

    @Test
    fun steppingBackFromReviewThrowsAwayTheDryRun() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo).assign(Owner.MATT)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        val back = s.goTo(Step.LIST)
        assertEquals(Step.LIST, back.step)
        assertNull(back.preview)
    }

    @Test
    fun theStepperOnlyOffersStepsAlreadyAnswered() {
        val fresh = MassEntry()
        assertTrue(fresh.reachable(Step.WHICH))
        assertFalse(fresh.reachable(Step.LIST))
        assertFalse(fresh.reachable(Step.REVIEW))

        val listed = fresh.choose(Direction.ADD).type(listOfTwo)
        assertTrue(listed.reachable(Step.LIST))
        assertTrue(listed.reachable(Step.WHO))
        assertFalse(listed.reachable(Step.REVIEW))
    }

    @Test
    fun aSharedListArrivesWithNothingElseDecided() {
        val s = MassEntry.fromShare("Name,Quantity\nSol Ring,1\nLightning Bolt,2")
        assertEquals(Step.WHICH, s.step)
        assertNull(s.direction)
        assertNull(s.owner)
        assertEquals(2, s.cardCount)
        assertTrue(s.isCsv)
    }

    @Test
    fun enterMoreClearsEverything() {
        val again = MassEntry().choose(Direction.REMOVE).type(listOfTwo).assign(Owner.MATT)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
            .finished(Applied(applied = true))
            .again()
        assertEquals(Step.WHICH, again.step)
        assertNull(again.direction)
        assertNull(again.owner)
        assertNull(again.preview)
        assertNull(again.result)
        assertEquals("", again.list)
    }

    @Test
    fun aFailureIsHeldWithoutLosingWhereYouWere() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo).assign(Owner.MATT)
            .working("Checking against Scryfall…")
            .failed("admin session expired")
        assertNull(s.busy)
        assertEquals("admin session expired", s.error)
        assertEquals(Owner.MATT, s.owner)
        assertEquals(listOfTwo, s.list)
    }

    private fun change() = Change("Sol Ring", "M3C", "409", "nonfoil", 0, 1)
}
