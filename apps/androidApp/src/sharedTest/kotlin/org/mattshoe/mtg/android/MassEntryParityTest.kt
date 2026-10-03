package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.Fact
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.Change
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Step
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's mass entry wizard, held against the website's.
 *
 * `MassEntryPage.kt` is the reference and its two Karma suites are the
 * written-down version of it. Every fact in here has a sibling over
 * there — "neither owner is preselected", "no write is offered before a
 * dry run", "the done step says Applied only when something actually
 * moved" — and is asserted as the same sentence rather than as
 * something adjacent that happens to pass.
 *
 * This file exists because reading the two implementations and
 * concluding they match is exactly how the drift survived: Android kept
 * its own rulings loop, and the "Applied" title stayed wrong here for
 * weeks after the web was fixed. So nothing here reasons about the
 * source. It mounts the composable, presses it, and reads the semantics
 * tree back — and leaves a screenshot of every step behind so a person
 * can put the two side by side.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MassEntryParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The state, held outside the composable exactly as the shell holds it. */
    private val live = mutableStateOf(MassEntry())
    private var previewAsked = false
    private var applyAsked = false

    private fun wizard(initial: MassEntry = MassEntry()) {
        live.value = initial
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    MassEntryScreen(
                        state = live.value,
                        onState = { live.value = it },
                        onPreview = { previewAsked = true },
                        onApply = { applyAsked = true },
                    )
                }
            }
        }
        rule.waitForIdle()
        // `setContent` returns before the host activity has necessarily
        // finished launching on a cold emulator, and a finder that runs
        // first fails with "No compose hierarchies found" — which reads
        // exactly like a real failure and is not one.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------- reading it

    private fun nodes(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes()

    /** Is that sentence on the screen at all. */
    private fun says(text: String, substring: Boolean = false) =
        nodes(hasText(text, substring = substring)).isNotEmpty()

    private fun howMany(text: String) = nodes(hasText(text)).size

    /**
     * Pressable, or on screen and refused.
     *
     * `Primary` marks a dead button `disabled()` rather than removing
     * it, the same as the web's `disabled` attribute, so "absent" and
     * "refused" are different answers and worth telling apart.
     */
    private fun pressable(text: String): Boolean {
        val node = nodes(hasText(text)).firstOrNull() ?: return false
        return !node.config.contains(SemanticsProperties.Disabled)
    }

    /** `aria-pressed`, in the semantics tree. */
    private fun ticked(label: String): Boolean {
        val node = nodes(hasText(label)).firstOrNull() ?: return false
        return node.config.getOrElse(SemanticsProperties.Selected) { false }
    }

    /** Whether that choice wears the glyph, not just the colour. */
    private fun wearsATick(label: String) =
        nodes(hasText(label) and hasText("✓")).isNotEmpty()

    /** Every option on the step, top to bottom, by its label. */
    private fun options(): List<String> =
        nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .sortedBy { it.boundsInRoot.top }
            .map { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .map { it.text }
                    .first { it != "✓" }
            }

    private fun fact(what: String, holds: () -> Boolean) = Fact(what, holds)

    // ---------------------------------------------------------- fixtures
    //
    // The same lists and the same changes the web suites use, so a
    // disagreement is a disagreement about the screen and not about
    // what it was handed.

    private fun change(i: Int, before: Int = 1, after: Int = 2) = Change(
        name = "A Card With Quite A Long Name Number $i",
        set = "fra",
        collectorNumber = "$i",
        finish = "nonfoil",
        before = before,
        after = after,
    )

    /** Three printings: +2, a new one, and another new one. Five copies, two new. */
    private val threeChanges = listOf(
        change(1, before = 1, after = 3),
        change(2, before = 0, after = 1),
        change(3, before = 0, after = 2),
    )

    private fun reviewed(changes: List<Change> = threeChanges) =
        MassEntry(direction = Direction.ADD)
            .type(changes.indices.joinToString("\n") { "1 A Card With Quite A Long Name Number ${it + 1}" })
            .assign(Owner.MATT)
            .previewed(Applied(dryRun = true, resolved = changes.size, changes = changes))

    private fun done(
        applied: Boolean = true,
        changes: List<Change> = threeChanges,
        failed: Int = 0,
        errors: List<String> = emptyList(),
        direction: Direction = Direction.ADD,
    ) = MassEntry(direction = direction)
        .type("1 A Card With Quite A Long Name Number 1\n1 A Card With Quite A Long Name Number 2")
        .assign(Owner.MATT)
        .previewed(Applied(dryRun = true, resolved = changes.size, changes = changes))
        .finished(
            Applied(
                applied = applied,
                resolved = changes.size,
                failed = failed,
                changes = changes,
                errors = errors,
            ),
        )

    // ====================================================== 1. which

    @Test
    fun theWhichStepAsksTheWebsitesQuestionAndPresumesNothing() {
        wizard()
        rule.onRoot().shoot("mass-entry-1-which")

        Parity.check(
            fact("the first step is headed 'Adding or removing?'") { says("Adding or removing?") },
            fact("the line under the title is the website's") {
                says("Cards in or cards out, from a list or a file.")
            },
            fact("both directions are offered, in the website's order") {
                options() == listOf("Add to the collection", "Remove from the collection")
            },
            fact("'Add' carries the website's line of help") {
                says("Cards you bought, opened or were given.")
            },
            fact("'Remove' carries the website's line of help") {
                says("Cards you sold, traded away or lost.")
            },
            fact("neither direction is preselected") {
                !ticked("Add to the collection") && !ticked("Remove from the collection")
            },
            fact("an unchosen option wears no tick") {
                !wearsATick("Add to the collection") && !wearsATick("Remove from the collection")
            },
            fact("the button says 'Continue →' and is refused") {
                says("Continue →") && !pressable("Continue →")
            },
            fact("the button is never relabelled 'Pick one to continue'") {
                !says("Pick one to continue")
            },
            fact("it says why: 'Nothing is preselected on purpose.'") {
                says("Nothing is preselected on purpose.")
            },
            fact("no count is claimed over an empty box") {
                !says("cards already in the box", substring = true)
            },
        )
    }

    @Test
    fun choosingADirectionTicksItAndFreesContinue() {
        wizard()
        rule.onNodeWithText("Add to the collection").performScrollTo().performClick()
        rule.waitForIdle()

        Parity.check(
            fact("the chosen direction took") { live.value.direction == Direction.ADD },
            fact("it is marked by a glyph, not only by a colour") {
                wearsATick("Add to the collection")
            },
            fact("it reads as pressed in the semantics tree") { ticked("Add to the collection") },
            fact("the other one did not come on too") {
                !ticked("Remove from the collection") && !wearsATick("Remove from the collection")
            },
            fact("exactly one tick is on the screen") { howMany("✓") == 1 },
            fact("'Continue →' is now pressable") { pressable("Continue →") },
            fact("the hint goes away once the question is answered") {
                !says("Nothing is preselected on purpose.")
            },
            fact("the line under the title changes to the website's 'add' line") {
                says("Resolved against Scryfall, then written to the collection.")
            },
        )
    }

    @Test
    fun theBoxIsCountedInCardsAndNotInLines() {
        // `cardCount` counts lines, which is what the request size is
        // limited by. The website says cards here: a CSV of two rows
        // with a quantity column is three cards, and this screen read
        // two off `cardCount` for as long as it existed.
        wizard(MassEntry.fromShare("Name,Quantity\nSol Ring,1\nLightning Bolt,2"))

        Parity.check(
            fact("three cards are claimed, the way the website claims them") {
                says("3 cards already in the box")
            },
            fact("the line count is not passed off as a card count") {
                !says("2 cards already in the box")
            },
        )
    }

    // ======================================================= 2. list

    @Test
    fun theListStepCountsTheBoxThreeWaysAndSaysNothingIsWrittenYet() {
        wizard(
            MassEntry(direction = Direction.ADD)
                .goTo(Step.LIST)
                .type("4 Lightning Bolt\n1 Sol Ring (M3C) 409\n2 Sol Ring"),
        )
        rule.onRoot().shoot("mass-entry-2-list")

        Parity.check(
            fact("the step is headed with the direction's own question") {
                says("What are you adding?")
            },
            fact("the note counts lines, which is what the limit is on") { says("3 lines") },
            fact("seven cards") { howMany("7") == 1 },
            fact("two unique cards") { howMany("2") == 1 },
            fact("three lines, counted again under the box") { howMany("3") == 1 },
            fact("the three figures are labelled the way the website labels them") {
                says("CARDS") && says("UNIQUE") && says("LINES")
            },
            fact("a file only fills the box, and is offered here") { says("Upload a file") },
            fact("there is a way back") { says("← Back") },
            fact("'Continue →' is pressable with a list in the box") { pressable("Continue →") },
            fact("it says out loud that nothing has been written") {
                says("Nothing is written until you press the button on the last step.")
            },
            fact("it does not also tell you to paste a list you have already pasted") {
                !says("Paste a list, or drop a file on the box.")
            },
        )
    }

    @Test
    fun anEmptyListStepSaysWhatToDoAndRefusesToMoveOn() {
        wizard(MassEntry(direction = Direction.REMOVE).goTo(Step.LIST))

        Parity.check(
            fact("the removal question, not the addition one") { says("What are you removing?") },
            fact("nothing in the box is nought lines") { says("0 lines") },
            fact("'Continue →' is on screen and refused") {
                says("Continue →") && !pressable("Continue →")
            },
            fact("it says what to do about it") { says("Paste a list, or drop a file on the box.") },
            fact("it does not promise a write nobody can reach yet") {
                !says("Nothing is written until you press the button on the last step.")
            },
        )
    }

    // ======================================================== 3. who

    @Test
    fun theOwnerStepOffersBothAndPresumesNeither() {
        wizard(MassEntry(direction = Direction.ADD).type("4 Lightning Bolt\n1 Sol Ring").goTo(Step.WHO))
        rule.onRoot().shoot("mass-entry-3-who")

        Parity.check(
            fact("the step is headed 'Whose collection?'") { says("Whose collection?") },
            fact("the note counts cards on the list, not lines") { says("5 cards on the list") },
            fact("the two collections are the two collections, in order") {
                options() == listOf("Matt", "Kayla")
            },
            fact("neither owner is preselected") { !ticked("Matt") && !ticked("Kayla") },
            fact("neither owner wears a tick") { !wearsATick("Matt") && !wearsATick("Kayla") },
            fact("no owner arrived in the state") { live.value.owner == null },
            fact("the button says 'Preview changes →' and is refused") {
                says("Preview changes →") && !pressable("Preview changes →")
            },
            fact("the button is not relabelled when it is dead") { !says("Pick one to continue") },
            fact("it says why: 'Pick whose collection this goes to.'") {
                says("Pick whose collection this goes to.")
            },
            fact("no write is on screen with no dry run behind it") {
                !says("printings", substring = true)
            },
            fact("there is a way back to the box") { says("← Back") },
        )
    }

    @Test
    fun namingAnOwnerMovesTheTickRatherThanAddingOne() {
        wizard(MassEntry(direction = Direction.ADD).type("4 Lightning Bolt\n1 Sol Ring").goTo(Step.WHO))

        rule.onNodeWithText("Matt").performScrollTo().performClick()
        rule.waitForIdle()
        Parity.check(
            fact("Matt took") { live.value.owner == Owner.MATT },
            fact("Matt wears the tick") { ticked("Matt") && wearsATick("Matt") },
            fact("the dry run is now offered") { pressable("Preview changes →") },
            fact("the hint is gone") { !says("Pick whose collection this goes to.") },
        )

        rule.onNodeWithText("Kayla").performScrollTo().performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the second tap took") { live.value.owner == Owner.KAYLA },
            fact("the tick moved rather than multiplied") { howMany("✓") == 1 },
            fact("Kayla is the one marked") { ticked("Kayla") && wearsATick("Kayla") },
            fact("Matt is no longer marked") { !ticked("Matt") && !wearsATick("Matt") },
        )

        rule.onNodeWithText("Preview changes →").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(previewAsked, "the dry run was never asked for") }
    }

    // ===================================================== 4. review

    @Test
    fun noWriteIsOfferedBeforeADryRun() {
        // The rule the whole wizard exists for.
        val ready = MassEntry(direction = Direction.ADD).type("1 Sol Ring").assign(Owner.MATT)
        assertTrue(ready.canPreview, "the fixture cannot even ask for a dry run")
        wizard(ready.goTo(Step.REVIEW))

        Parity.check(
            fact("the step is headed 'Preview — nothing written yet'") {
                says("Preview — nothing written yet")
            },
            fact("whose collection it would write is on the step") { says("matt") },
            fact("it says there is no preview") { says("No preview yet.") },
            fact("the button says 'Nothing to apply' and is refused") {
                says("Nothing to apply") && !pressable("Nothing to apply")
            },
            fact("no write is labelled with a count it cannot have") {
                !says("printings", substring = true)
            },
        )
    }

    @Test
    fun theReviewStepIsRowsAndOffersTheWriteOnceTheDryRunIsBack() {
        wizard(reviewed())
        rule.onRoot().shoot("mass-entry-4-review")

        Parity.check(
            fact("still headed as written nothing") { says("Preview — nothing written yet") },
            fact("three printings would move") { howMany("3") == 1 },
            fact("five copies would move") { howMany("5") == 1 },
            fact("two of them would be new") { howMany("2") == 1 },
            fact("the figures are labelled as the website labels them") {
                says("PRINTINGS") && says("COPIES") && says("NEW")
            },
            fact("a row per printing, named") {
                says("A Card With Quite A Long Name Number 1") &&
                    says("A Card With Quite A Long Name Number 2") &&
                    says("A Card With Quite A Long Name Number 3")
            },
            fact("each row says which printing") { says("FRA 1") },
            fact("a printing nobody owned says so in a word, not a colour") {
                says("FRA 2") && says("new")
            },
            // The web puts "new" in a tag of its own beside the set
            // code. This read "FRA 2 · new" — one grey run-on string
            // with the same words in it and none of the structure,
            // which is how it passed the sentence above while looking
            // nothing like the website.
            fact("what is true of a printing is a tag, not glued onto the set code") {
                !says("·", substring = true)
            },
            fact("a tag apiece, for the two printings nobody owned") {
                howMany("new") == 2
            },
            fact("how many moved is a signed number, not a colour") { says("+2") },
            fact("and where it ended up") { says("1 → 3") && says("0 → 1") },
            fact("the write is offered, labelled the website's way") {
                says("Add 3 printings") && pressable("Add 3 printings")
            },
            fact("the owner is not stapled onto the button") {
                !says("Add 3 printings · matt")
            },
        )

        rule.onNodeWithText("Add 3 printings").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(applyAsked, "the write was never asked for") }
    }

    @Test
    fun aRemovalThatEmptiesAPrintingSaysLastOne() {
        wizard(reviewed(listOf(change(1, before = 1, after = 0))).copy(direction = Direction.REMOVE))

        Parity.check(
            fact("the loss is a signed number") { says("-1") },
            fact("and where it ended up") { says("1 → 0") },
            fact("the collection having none left is said in words, in a tag of its own") {
                says("last one")
            },
            fact("a printing that went away is not also called new") { !says("new") },
            fact("one printing, singular, the way the website counts it") {
                says("PRINTING") && says("COPY")
            },
            fact("the verb on the button is the direction's own") {
                says("Remove 1 printings")
            },
        )
    }

    // ======================================================= 5. done

    @Test
    fun theDoneStepSaysAppliedWhenSomethingActuallyMoved() {
        wizard(done())
        rule.onRoot().shoot("mass-entry-5-done-applied")

        Parity.check(
            fact("the receipt is headed 'Applied'") { says("Applied") },
            fact("it says whose collection it wrote") { says("matt") },
            fact("three printings moved") { howMany("3") == 1 },
            fact("five copies moved") { howMany("5") == 1 },
            fact("two of them were new") { howMany("2") == 1 },
            fact("every printing is on the receipt") {
                says("A Card With Quite A Long Name Number 1") &&
                    says("A Card With Quite A Long Name Number 3")
            },
            fact("the way back into the wizard is offered") {
                says("Enter more") && pressable("Enter more")
            },
            fact("the step is not still offering a write") { !says("Add 3 printings") },
        )
    }

    @Test
    fun anEmptyWriteIsNotCalledApplied() {
        // The server reports `applied` for the call, not for the cards.
        // A removal of printings somebody already removed comes back
        // applied and empty, and "Applied" over nothing is a lie — this
        // is the one the website fixed and the phone did not.
        wizard(done(applied = true, changes = emptyList(), direction = Direction.REMOVE))
        rule.onRoot().shoot("mass-entry-6-done-nothing-applied")

        Parity.check(
            fact("it is headed 'Nothing applied'") { says("Nothing applied") },
            fact("it does not claim to have applied anything") { !says("Applied") },
            fact("nought printings, nought copies, nought new") { howMany("0") == 3 },
            fact("no rows under a receipt with nothing on it") { !says("FRA 1") },
            fact("the way back into the wizard is still offered") { says("Enter more") },
        )
    }

    @Test
    fun whatTheServerCouldNotDoIsSaidOutLoud() {
        wizard(done(failed = 2, errors = listOf("Sol Rong: no such card")))

        Parity.check(
            fact("the cards it could not resolve are counted") {
                says("2 could not be resolved")
            },
            fact("the server's own words are not swallowed") {
                says("Sol Rong: no such card")
            },
        )
    }

    @Test
    fun enterMoreEmptiesTheWizardWithoutLeavingIt() {
        wizard(done())
        rule.onNodeWithText("Enter more").performScrollTo().performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals("", live.value.list, "the old list is still in the box")
            assertNull(live.value.result, "the old receipt is still in the state")
            assertNull(live.value.preview, "the old dry run is still in the state")
            assertEquals(Step.WHICH, live.value.step, "it did not go back to the start")
        }
        Parity.check(
            fact("it is the first step of the wizard again") { says("Adding or removing?") },
            fact("the receipt is off the screen") { !says("Applied") },
            fact("the old rows are gone") { !says("FRA 1") },
            fact("nothing is preselected the second time either") {
                !ticked("Add to the collection") && howMany("✓") == 0
            },
        )
    }

    // ===================================================== the stepper

    @Test
    fun theStepperShowsFourStepsAndRefusesTheUnanswered() {
        wizard()

        Parity.check(
            fact("four steps, numbered, in the website's order") {
                says("1 Which") && says("2 List") && says("3 Who") && says("4 Review")
            },
            fact("the ending is not one of the steps") { !says("Done") && !says("5 Done") },
            fact("nothing is reachable from the stepper before it is answered") {
                !pressable("1 Which") && !pressable("2 List") &&
                    !pressable("3 Who") && !pressable("4 Review")
            },
        )
    }

    @Test
    fun theStepperOpensUpBehindYouAndNotAheadOfYou() {
        wizard(MassEntry(direction = Direction.ADD).type("1 Sol Ring").goTo(Step.WHO))

        Parity.check(
            fact("the steps behind are ticked") { says("✓ Which") && says("✓ List") },
            fact("and pressable") { pressable("✓ Which") && pressable("✓ List") },
            fact("the step you are on is not a way back to itself") { !pressable("3 Who") },
            fact("the step ahead stays shut") { !pressable("4 Review") },
        )

        rule.onNodeWithText("✓ List").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Step.LIST, live.value.step, "the stepper did not go back") }
    }

    @Test
    fun aDryRunIsThrownAwayOnTheWayBack() {
        // A dry run describes one list going to one collection. Carried
        // back to the box it is not an answer to anything, and the core
        // drops it — which only matters if the screen asks the core
        // rather than keeping its own copy.
        wizard(reviewed())
        rule.onNodeWithText("← Back").performScrollTo().performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(Step.WHO, live.value.step, "Back did not go back")
            assertNull(live.value.preview, "the dry run survived being walked away from")
            assertTrue(!live.value.canApply, "a write was still reachable off a stale dry run")
        }
        Parity.check(
            fact("it is the owner step again") { says("Whose collection?") },
            fact("the write is not on screen any more") { !says("Add 3 printings") },
            fact("the owner that was named is still named") { ticked("Matt") },
        )
    }

    // ================================================== while working

    @Test
    fun theWorkingPanelReplacesTheStepAndSaysWhatItIsDoing() {
        wizard(reviewed().working("Adding 3 printings…"))

        Parity.check(
            fact("the panel is headed 'Working', as the website heads it") { says("Working") },
            fact("it says what it is working on") { says("Adding 3 printings…") },
            fact("the step underneath is gone, write and all") { !says("Add 3 printings") },
            fact("nothing can be pressed twice while a write is in flight") {
                !says("Nothing to apply") && !says("Preview changes →")
            },
        )
    }
}
