package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        // Nothing to assert about an owner: the wizard stopped asking.
        assertFalse(s.canLeaveWhich)
    }

    @Test
    fun aDirectionIsNeededBeforeAnythingElse() {
        val s = MassEntry()
        assertEquals(Step.WHICH, s.goTo(Step.LIST).step)
        assertEquals(Step.WHICH, s.goTo(Step.LIST).step)
        assertEquals(Step.WHICH, s.goTo(Step.REVIEW).step)
    }

    @Test
    fun anEmptyListGoesNoFurther() {
        val s = MassEntry().choose(Direction.ADD)
        assertTrue(s.canLeaveWhich)
        assertFalse(s.canLeaveList)
        assertEquals(Step.LIST, s.goTo(Step.REVIEW).step)
    }

    /**
     * This asserted the opposite until Matt updated the app and hit a
     * 1,000 line limit that had already been taken out of the Worker.
     *
     * `overLimit` was `cardCount > 1000` in :core and it disabled Continue,
     * so the app refused the list before anything was sent. The constant
     * beside it called itself "the API's own cap on one call" — true when
     * written, false the moment that cap came out of the server, and
     * nothing made the two agree. A client must not hold its own copy of a
     * server rule.
     */
    @Test
    fun anEnormousListIsNotRefusedByTheApp() {
        val huge = (1..5000).joinToString("\n") { "1 Card $it" }
        val s = MassEntry().choose(Direction.ADD).type(huge)
        assertEquals(5000, s.cardCount)
        assertTrue(s.canLeaveList, "the app refused a 5000 line list on its own")
        assertEquals(Step.REVIEW, s.goTo(Step.REVIEW).step)
    }

    @Test
    fun aListIsAllTheWizardAsksFor() {
        // There used to be a third question — whose collection this
        // lands in — and a typed list that had not answered it went
        // no further. The page is somebody's collection now, so the
        // list is the whole of it.
        val s = MassEntry().choose(Direction.REMOVE).type(listOfTwo)
        assertTrue(s.canPreview)
        assertEquals(Step.REVIEW, s.goTo(Step.REVIEW).step)
    }

    @Test
    fun applyIsUnreachableUntilTheServerHasSaidWhatItWouldDo() {
        val ready = MassEntry()
            .choose(Direction.ADD)
            .type(listOfTwo)
            

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
        val s = MassEntry().choose(Direction.ADD).type("1 Biterblosom")
            .previewed(Applied(dryRun = true, resolved = 0, errors = listOf("no card named Biterblosom")))
        assertFalse(s.canApply)
    }

    @Test
    fun applyIsNotOfferedTwice() {
        val done = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
            .finished(Applied(applied = true, changes = listOf(change())))
        assertEquals(Step.DONE, done.step)
        assertFalse(done.canApply)
    }

    @Test
    fun editingTheListThrowsAwayTheDryRunItWasTakenAgainst() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        assertTrue(s.canApply)

        val edited = s.type("$listOfTwo\n1 Opt")
        assertNull(edited.preview)
        assertFalse(edited.canApply, "a stale dry run still authorised a write")
    }

    @Test
    fun editingTheListThrowsAwayTheDryRun() {
        // It used to be "changing the owner" as well. There is no
        // owner to change, and the list is the only thing left that a
        // dry run can go stale against.
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        assertNotNull(s.preview)
        val edited = s.type("1 Black Lotus")
        assertNull(edited.preview)
        assertFalse(edited.canApply)
    }

    @Test
    fun steppingBackFromReviewThrowsAwayTheDryRun() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo)
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
        // Review is reachable as soon as there is a list: the step
        // between them asked whose collection it was, and nothing
        // asks that now.
        assertTrue(listed.reachable(Step.REVIEW))
    }

    @Test
    fun aSharedListArrivesWithNothingElseDecided() {
        val s = MassEntry.fromShare("Name,Quantity\nSol Ring,1\nLightning Bolt,2")
        assertEquals(Step.WHICH, s.step)
        assertNull(s.direction)
        // No owner to be unset.
        assertEquals(2, s.cardCount)
        assertTrue(s.isCsv)
    }

    @Test
    fun enterMoreClearsEverything() {
        val again = MassEntry().choose(Direction.REMOVE).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
            .finished(Applied(applied = true))
            .again()
        assertEquals(Step.WHICH, again.step)
        assertNull(again.direction)
        // No owner to forget.
        assertNull(again.preview)
        assertNull(again.result)
        assertEquals("", again.list)
    }

    @Test
    fun aFailureIsHeldWithoutLosingWhereYouWere() {
        val s = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .working("Checking against Scryfall…")
            .failed("admin session expired")
        assertNull(s.busy)
        assertEquals("admin session expired", s.error)
        // The owner is the collection on screen, not a field here.
        assertEquals(listOfTwo, s.list)
    }

    private fun change() = Change("Sol Ring", "M3C", "409", "nonfoil", 0, 1)

    // ------------------------------------------- leaving with work in hand

    @Test
    fun anEmptyBoxHasNothingToLose() {
        assertFalse(MassEntry().unsaved)
    }

    @Test
    fun aTypedListIsUnsavedUntilTheServerHasIt() {
        assertTrue(MassEntry().type("1 Sol Ring").unsaved)
    }

    @Test
    fun aDryRunIsStillUnsaved() {
        // The most dangerous moment to close the tab: it looks done
        // and nothing has been written.
        val s = MassEntry().type("1 Sol Ring").previewed(Applied(resolved = 1))
        assertTrue(s.unsaved)
    }

    @Test
    fun aWriteThatCameBackIsNotUnsaved() {
        val s = MassEntry().type("1 Sol Ring").finished(Applied(applied = true, resolved = 1))
        assertFalse(s.unsaved)
    }

    // -------------------------------------------- DONE, which nothing
    // above happens to reach on its own.

    @Test
    fun doneIsOnlyReachableOnceAResultHasActuallyComeBack() {
        val ready = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        assertFalse(ready.reachable(Step.DONE), "nothing has been applied yet")

        val done = ready.finished(Applied(applied = true, changes = listOf(change())))
        assertTrue(done.reachable(Step.DONE))
    }

    @Test
    fun askingForDoneBeforeAnythingHasBeenAppliedLandsOnReviewInstead() {
        // DONE means "a result came back", not "I asked to go there" —
        // a stale link or a double tap must not be able to fake it.
        val ready = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        val landed = ready.goTo(Step.DONE)
        assertEquals(Step.REVIEW, landed.step)
        assertEquals(ready.preview, landed.preview, "landing on REVIEW keeps the dry run that is already in hand")
    }

    @Test
    fun askingForDoneAfterAResultHasComeBackActuallyGoesThere() {
        val done = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
            .finished(Applied(applied = true, changes = listOf(change())))
        assertEquals(Step.DONE, done.goTo(Step.DONE).step)
    }

    @Test
    fun navigatingToReviewDirectlyLandsThereAndKeepsThePreviewInHand() {
        val previewed = MassEntry().choose(Direction.ADD).type(listOfTwo)
            .previewed(Applied(dryRun = true, changes = listOf(change())))
        val again = previewed.goTo(Step.REVIEW)
        assertEquals(Step.REVIEW, again.step)
        assertEquals(previewed.preview, again.preview)
    }

    @Test
    fun theWizardListsExactlyTheFourAnsweredStepsInOrderNotIncludingDone() {
        // DONE is an ending, not a question the stepper offers to jump
        // back to — the UI on both platforms indexes into this list.
        assertEquals(listOf(Step.WHICH, Step.LIST, Step.REVIEW), Step.wizard)
        assertFalse(Step.DONE in Step.wizard)
    }
}

/**
 * Nothing that writes is offered twice.
 *
 * `busy` was declared on `MassEntry` and never set by either platform,
 * and the gates never asked about it — so "Add 248 printings" stayed
 * live while the add was in flight, and the API gives every call its
 * own idempotency key, which means the second press is a second add
 * rather than a repeat of the first.
 */
class EntryInFlightTest {

    private fun ready() = MassEntry(
        step = Step.REVIEW,
        direction = Direction.ADD,
        list = "4 Sol Ring",
        preview = Applied(
            applied = false,
            resolved = 1,
            failed = 0,
            changes = listOf(Change("Sol Ring", "M3C", "409", "nonfoil", 0, 4)),
            errors = emptyList(),
        ),
    )

    @Test
    fun aListWithAPlanIsOfferedForReal() {
        assertTrue(ready().canApply, "a reviewed list should be appliable")
    }

    @Test
    fun butNotWhileOneIsAlreadyOnItsWay() {
        assertFalse(ready().working("Applying…").canApply, "it offered to apply a second time")
    }

    @Test
    fun andNotASecondDryRunEither() {
        assertFalse(ready().working("Checking…").canPreview, "it offered a second dry run")
    }

    @Test
    fun whatComesBackOpensItAgain() {
        val busy = ready().working("Applying…")
        assertTrue(busy.failed("nope").canPreview, "a failure left the wizard stuck")
    }
}
