package org.mattshoe.mtg.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onSiblings
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NameCheck
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Validation
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The new deck wizard on Android, against the website step for step.
 *
 * Every fact asserted here is one the web wizard establishes —
 * `NewDeckPage.kt`, `NewDeck.kt` and the web's own suites — and every
 * one of them is checked by rendering the real Android composable and
 * reading what it put on the screen. Reading the two files side by
 * side and deciding they look alike is how this screen drifted in the
 * first place.
 *
 * A failing test names every broken fact at once rather than the first
 * one: a port that is five facts behind should take one run to find
 * out, not five.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NewDeckParityTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------- the verdict

    private val broken = mutableListOf<String>()

    /** One fact about the screen. Checked, recorded, and carried on past. */
    private fun fact(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            val why = e.message.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            broken += "$what\n      ($why)"
        }
    }

    @After
    fun everyBrokenFactAtOnce() {
        if (broken.isNotEmpty()) {
            fail(
                "the Android wizard is ${broken.size} fact(s) behind the website:\n  - " +
                    broken.joinToString("\n  - ") + "\n  screenshots: $shots",
            )
        }
    }

    // ------------------------------------------------------------ mounting

    /**
     * Mount, and wait until it is really mounted.
     *
     * `setContent` returns before the host activity has necessarily
     * finished launching on a cold emulator, and a finder that runs
     * first fails with "No compose hierarchies found" — which reads
     * exactly like a real failure and is not one.
     */
    private fun content(body: @Composable () -> Unit) {
        // `MtgTheme`, not Material's own dark scheme. The screenshots
        // are the only evidence anybody has that this looks like the
        // website, and under `darkColorScheme()` every one of them
        // came back in Material's default purple — a picture of a
        // screen the app never shows.
        rule.setContent { MtgTheme { body() } }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The real dialog, holding real state, so a press actually redraws. */
    private fun wizard(
        initial: NewDeck,
        onCheck: () -> Unit = {},
        onCreate: () -> Unit = {},
        onClose: () -> Unit = {},
        onCommanderTyped: (Completion) -> Unit = {},
        onPickFile: () -> Unit = {},
    ): MutableState<NewDeck> {
        val s = mutableStateOf(initial)
        content {
            NewDeckDialog(
                state = s.value,
                onState = { s.value = it },
                onCheck = onCheck,
                onCreate = onCreate,
                onClose = onClose,
                onCommanderTyped = onCommanderTyped,
                onPickFile = onPickFile,
            )
        }
        return s
    }

    // --------------------------------------------------------- the screen

    /** Everything the screen says, labels and typed text alike. */
    private fun words(): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text) != null ||
                    it.config.getOrNull(SemanticsProperties.EditableText) != null
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { n ->
            (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }

    private fun says(text: String) =
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun somewhere(part: String) = words().any { part in it }

    private fun shown(what: String, text: String) =
        fact("$what — the screen should say \"$text\"") {
            assertTrue(says(text), "it says: ${words()}")
        }

    private fun absent(what: String, text: String) =
        fact("$what — the screen should NOT say \"$text\"") {
            assertTrue(!says(text), "it says: ${words()}")
        }

    /** Not anywhere, in any longer line either. */
    private fun nowhere(what: String, part: String) =
        fact("$what — nothing should mention \"$part\"") {
            assertTrue(!somewhere(part), "it says: ${words()}")
        }

    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()

    /** The one node whose own text is exactly this, ignoring merging. */
    private fun node(text: String): SemanticsNodeInteraction =
        rule.onNode(hasText(text), useUnmergedTree = true)

    /**
     * These words, and these, sitting in one row of the layout.
     *
     * The web draws the review as cells — `.tally-cell` is a figure
     * and its label, `.plan-row` is a quantity, a name and a tag —
     * and a flat check that the words are all somewhere on the
     * screen passes just as happily when they have been glued into
     * one sentence. Siblings is the difference between the two.
     */
    private fun grouped(what: String, anchor: String, vararg withIt: String) =
        fact("$what — \"$anchor\" should sit in a row with ${withIt.joinToString(", ")}") {
            val row = node(anchor).onSiblings()
            withIt.forEach { row.assertAny(hasText(it)) }
        }

    // ------------------------------------------------------- screenshots

    private val shots = mutableListOf<String>()

    /**
     * A picture of the step, named, on the device's own storage.
     *
     * The dialog's own window, through the shared kit, rather than
     * the whole device through `uiAutomation`. A full-screen grab is
     * a picture of whatever else the emulator is doing: on a box
     * running five of them an "isn't responding" dialog sat over the
     * middle of every shot in the last run, and two came back with
     * nothing of the wizard in them at all.
     */
    private fun shoot(name: String) {
        rule.waitForIdle()
        val out = runCatching {
            with(Parity) { rule.onNode(isDialog()).shoot("newdeck-$name").absolutePath }
        }
        shots += out.getOrElse { "newdeck-$name.png FAILED: ${it.message}" }
    }

    // ------------------------------------------------------------- states

    private val list = "1 Sol Ring\n1 Arcane Signet"

    private fun named() = NewDeck().pick(Format.COMMANDER).assign(Owner.MATT).rename("Test Deck")

    private fun carded() = named().setCommander("Alela, Cunning Conqueror").type(list)

    /** Sol Ring held, Arcane Signet not — one line of each kind. */
    private fun checkedOk() = Validation(
        ok = true,
        checked = 3,
        unknown = 0,
        cards = listOf(
            NameCheck(name = "Sol Ring", nameNorm = "sol ring", ok = true, source = "collection"),
            NameCheck(name = "Arcane Signet", nameNorm = "arcane signet", ok = true, source = "scryfall"),
        ),
    )

    // -------------------------------------------------------- 1 · format

    @Test
    fun theFormatStepOffersEveryFormatAndWillNotBeSkipped() {
        val s = wizard(NewDeck())

        shown("the title names the step", "New deck · Format")
        Format.entries.forEach { f -> shown("every format the web offers", f.label) }
        fact("Continue is refused until a format is chosen") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }
        // The stepper is numbered on the web, and a step you cannot
        // reach yet is disabled rather than hidden.
        shown("the stepper numbers its steps", "1 Format")
        shown("the stepper names the owner step", "2 Whose")
        fact("an unreachable step is disabled, not missing") {
            rule.onNodeWithText("2 Whose").assertIsNotEnabled()
        }
        // `.step.on`. Seven identical pills said nothing about where
        // you were, and the one thing that did was the title.
        fact("the stepper marks the step you are on") {
            rule.onNodeWithText("1 Format").assertIsSelected()
        }
        fact("and marks no other") {
            rule.onNodeWithText("2 Whose").assertIsNotSelected()
            rule.onNodeWithText("3 Name").assertIsNotSelected()
        }
        // No commander step until a format that wants one is picked,
        // so Cards is the fourth step rather than the fifth.
        shown("no commander step before a format is chosen", "4 Cards")
        absent("nothing is preselected", "✓ Commander")
        shoot("format")

        tap("Commander")
        fact("the chosen format is marked with a tick, not a colour") {
            assertTrue(says("✓ Commander"), "it says: ${words()}")
        }
        // `aria-pressed`, which the web sets on every one of these.
        fact("and the choice is announced, not only drawn") {
            rule.onNodeWithText("✓ Commander").assertIsSelected()
            rule.onNodeWithText("Standard").assertIsNotSelected()
        }
        fact("choosing a format arms Continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
        fact("the commander step appears for a format that wants one") {
            assertTrue(says("4 Commander") && says("5 Cards"), "it says: ${words()}")
        }
        fact("Continue goes to the owner") {
            rule.onNodeWithText("Continue →").performClick()
            rule.runOnIdle { assertEquals(DeckStep.OWNER, s.value.step) }
        }
    }

    @Test
    fun aFormatThatWantsNoCommanderHasNoCommanderStep() {
        wizard(NewDeck().pick(Format.STANDARD))
        shown("Standard keeps its tick", "✓ Standard")
        absent("Standard has no commander step", "4 Commander")
        shown("so Cards is its fourth step", "4 Cards")
    }

    // --------------------------------------------------------- 2 · owner

    @Test
    fun theOwnerStepAsksWhoseAndAssumesNeither() {
        val s = wizard(NewDeck().pick(Format.COMMANDER).goTo(DeckStep.OWNER))

        shown("the title names the step", "New deck · Whose")
        Owner.entries.forEach { o -> shown("both owners are offered", o.label) }
        absent("neither owner is assumed", "✓ Matt")
        fact("Continue is refused until one is chosen") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }
        shoot("owner")

        tap("Matt")
        shown("the chosen owner is ticked", "✓ Matt")
        fact("choosing an owner arms Continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
        fact("it was recorded") { rule.runOnIdle { assertEquals(Owner.MATT, s.value.owner) } }
    }

    // ---------------------------------------------------------- 3 · name

    @Test
    fun theNameStepSaysWhereTheDeckWillLive() {
        val s = wizard(NewDeck().pick(Format.COMMANDER).assign(Owner.MATT).goTo(DeckStep.NAME))

        shown("the box is labelled the way the web's is", "Deck name")
        absent("no address before there is a name", "It will live at #/decks/test-deck")
        fact("a nameless deck goes no further") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }

        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Test Deck")
        rule.waitForIdle()
        shown("the slug is shown as it is typed", "It will live at #/decks/test-deck")
        fact("a named deck may continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
        fact("the name was taken as written") {
            rule.runOnIdle { assertEquals("Test Deck", s.value.name) }
        }
        shoot("name")
    }

    // ----------------------------------------------------- 4 · commander

    @Test
    fun theCommanderBoxSuggestsCardNames() {
        // The web's box is `AutocompleteField` backed by `NewDeck.hint`.
        // A bare text box left the one name that has to be spelt
        // exactly right as the one name with no help spelling it.
        val hint = Completion().typed("alela")
            .suggested(listOf("Alela, Artful Provocateur", "Alela, Cunning Conqueror"))
        var typed: Completion? = null
        val s = wizard(named().goTo(DeckStep.COMMANDER).copy(hint = hint), onCommanderTyped = { typed = it })

        shown("the title names the step", "New deck · Commander")
        shown("the web's own placeholder", "e.g. Alela, Artful Provocateur")
        absent("not the old bare box", "Commander")
        shown("and it says why", "A Commander deck needs one, and the server checks it too.")
        fact("an empty commander goes no further") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }
        shown("the suggestions are on screen", "Alela, Cunning Conqueror")
        shoot("commander")

        tap("Alela, Cunning Conqueror")
        fact("a tapped suggestion becomes the commander") {
            rule.runOnIdle { assertEquals("Alela, Cunning Conqueror", s.value.commander) }
        }
        fact("a named commander may continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
        fact("typing asks the caller for names, the way the web does") {
            rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("x")
            rule.runOnIdle { assertTrue(typed != null, "nothing was asked for") }
        }
    }

    // --------------------------------------------------------- 5 · cards

    @Test
    fun theCardsStepTakesAFileAndCanLiftTheCommanderOffTheList() {
        var asked = false
        val s = wizard(
            named().setCommander("Alela, Cunning Conqueror").goTo(DeckStep.CARDS).type(list),
            onPickFile = { asked = true },
        )

        shown("the title names the step", "New deck · Cards")
        shown("it counts the list against the format", "2 cards · Commander wants 100")
        // A deck you already have written down is in a file. The web
        // grew a drop zone here; Android had paste and nothing else.
        shown("a file is offered, not only pasted text", "Upload a file")
        shown("and the first card can be taken as the commander", "First card is the commander (Sol Ring)")
        fact("a list with cards in it may continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
        shoot("cards")

        tap("Upload a file")
        fact("the file button asks for a file") { rule.runOnIdle { assertTrue(asked) } }

        tap("First card is the commander (Sol Ring)")
        fact("the first card becomes the commander and leaves the list") {
            rule.runOnIdle {
                assertEquals("Sol Ring", s.value.commander)
                assertEquals("1 Arcane Signet", s.value.list)
            }
        }
    }

    @Test
    fun anEmptyCardListOffersNoFirstCardAndNoWayOn() {
        wizard(named().setCommander("Alela, Cunning Conqueror").goTo(DeckStep.CARDS))
        shown("it counts nothing", "0 cards · Commander wants 100")
        shown("the file is still offered", "Upload a file")
        nowhere("there is no first card to offer", "First card is the commander")
        fact("an empty list goes no further") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }
    }

    @Test
    fun aFormatWithNoCommanderIsNotOfferedTheFirstCard() {
        wizard(
            NewDeck().pick(Format.STANDARD).assign(Owner.MATT).rename("Mono Red")
                .goTo(DeckStep.CARDS).type(list),
        )
        nowhere("Standard has no commander to lift", "First card is the commander")
        shown("but it still takes a file", "Upload a file")
    }

    // --------------------------------------------------------- 6 · check

    @Test
    fun theCheckStepWillNotPassUncheckedNames() {
        var checked = false
        wizard(carded().goTo(DeckStep.CHECK), onCheck = { checked = true })

        shown("the title names the step", "New deck · Check")
        shown("it says what the check does", "Every name is checked against the collection first, then Scryfall.")
        shown("the check is offered", "Check the names")
        fact("an unchecked list goes no further") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }
        shoot("check")

        tap("Check the names")
        fact("pressing it asks for the check") { rule.runOnIdle { assertTrue(checked) } }
    }

    @Test
    fun aGoodCheckOpensTheWayOnAndStillOffersAnother() {
        // The web keeps both buttons on this step. Swapping one for
        // the other left a verdict you had no way to ask for twice.
        wizard(carded().validated(checkedOk()).goTo(DeckStep.CHECK))
        shown("the verdict", "All 3 names are real cards")
        shown("another check is still offered", "Check again")
        fact("and the check can actually be repeated") {
            rule.onNodeWithText("Check again").assertIsEnabled()
        }
        fact("a checked list may continue") {
            rule.onNodeWithText("Continue →").assertIsEnabled()
        }
    }

    @Test
    fun aFailedCheckOffersTheSpellingAndFixesTheCommander() {
        // Two of them, not one. With a single bad name the old screen
        // joined the list with ", " and the join never showed, so
        // "which one" passed against a line that glues every unknown
        // name into one run of prose the moment there are two.
        val bad = Validation(
            ok = false,
            checked = 3,
            unknown = 2,
            cards = listOf(
                NameCheck(
                    name = "Kardur Doomscourge", nameNorm = "kardur doomscourge",
                    ok = false, suggestion = "Kardur, Doomscourge",
                ),
                NameCheck(
                    name = "Sol Rng", nameNorm = "sol rng",
                    ok = false, suggestion = "Sol Ring",
                ),
            ),
        )
        val s = wizard(
            named().setCommander("Kardur Doomscourge").type(list).validated(bad)
                .copy(step = DeckStep.CHECK),
        )
        shown("how many did not land", "2 not found")
        // `.chips`: a chip each, so the name you have to find is in
        // its own box rather than in the middle of a sentence.
        shown("which one", "Kardur Doomscourge")
        shown("and the other one", "Sol Rng")
        nowhere("the unknown names are not run together", "Kardur Doomscourge, Sol Rng")
        shown("what to do about it", "Tap one to use it")
        shown("the spelling on offer", "Kardur Doomscourge → Kardur, Doomscourge")
        fact("a failed check goes no further") {
            rule.onNodeWithText("Continue →").assertIsNotEnabled()
        }

        tap("Kardur Doomscourge → Kardur, Doomscourge")
        fact("taking the spelling fixes the commander and asks for a new check") {
            rule.runOnIdle {
                assertEquals("Kardur, Doomscourge", s.value.commander)
                assertTrue(s.value.checked == null, "it kept a verdict about the old spelling")
            }
        }
    }

    // -------------------------------------------------------- 7 · review

    @Test
    fun theReviewIsOneLinePerCardWithNothingToAnswer() {
        var created = false
        val s = wizard(
            carded().validated(checkedOk()).goTo(DeckStep.REVIEW),
            onCreate = { created = true },
        )

        shown("the title names the step", "New deck · Review")
        shown("what is being made", "Commander · Matt · 2 cards · Alela, Cunning Conqueror")
        // The tally is two figures, the way `.tally-cell` draws them:
        // the count big and what it counts small and uppercase under
        // it, each pair in its own cell. Written out as the sentence
        // "1 from bulk · 1 added to bulk" the words were all there
        // and the glance was not, and no assertion noticed.
        shown("the first figure is labelled", "FROM BULK")
        shown("the second figure is labelled", "ADDED TO BULK")
        grouped("the figure from bulk", "FROM BULK", "1")
        grouped("the figure added to bulk", "ADDED TO BULK", "1")
        nowhere("the tally is not a sentence", "1 from bulk · 1 added to bulk")
        // One line per card: the quantity, the name as it was written,
        // and a tag for what will happen to it. Three cells of a
        // `.plan-row`, not one line of prose with an em dash in it.
        grouped("the card the collection holds", "Sol Ring", "1", "From bulk")
        grouped("the card it does not", "Arcane Signet", "1", "Add to bulk")
        nowhere("a held card is not a sentence", "Sol Ring — From bulk")
        nowhere("an added card is not a sentence", "Arcane Signet — Add to bulk")
        shown("and what that means", "1 card the collection does not hold yet will be added to bulk.")
        fact("no per-card source buttons") {
            val pickers = rule.onAllNodes(
                hasClickAction() and hasText("bulk", substring = true),
            ).fetchSemanticsNodes()
            assertTrue(pickers.isEmpty(), "${pickers.size} source buttons are back on the review")
        }
        fact("nothing anywhere offers to buy anything") {
            val buy = words().filter { "buy" in it.lowercase() }
            assertTrue(buy.isEmpty(), "it still says: $buy")
        }
        fact("a checked list may be created") {
            rule.onNodeWithText("Create Test Deck").assertIsEnabled()
        }
        shoot("review")

        rule.onNodeWithText("Create Test Deck").performClick()
        fact("pressing it creates the deck") { rule.runOnIdle { assertTrue(created) } }
        fact("the wizard did not move itself off the review") {
            rule.runOnIdle { assertEquals(DeckStep.REVIEW, s.value.step) }
        }
    }

    @Test
    fun nothingIsOfferedTwiceWhileACreateIsInFlight() {
        // `canCreate` carries `busy == null`. The panel showing
        // "Creating…" is not the same as the button being refused.
        wizard(carded().validated(checkedOk()).goTo(DeckStep.REVIEW).working("Creating…"))
        shown("it says what it is doing", "Creating…")
        fact("and will not create a second deck") {
            rule.onNodeWithText("Create Test Deck").assertIsNotEnabled()
        }
        fact("the plan is not sitting there to be pressed either") {
            assertTrue(!somewhere("From bulk"), "it says: ${words()}")
        }
    }

    // ---------------------------------------------------------- 8 · done

    @Test
    fun theDoneStepSaysWhereTheDeckWentAndNothingElse() {
        var closed = false
        wizard(carded().validated(checkedOk()).finished(), onClose = { closed = true })

        shown("the title names the step", "New deck · Done")
        shown("the web's own word for it", "Created")
        shown("and where it lives", "Test Deck is at #/decks/test-deck")
        fact("there is nothing left to create") {
            assertTrue(!somewhere("Create Test Deck"), "it says: ${words()}")
        }
        shoot("done")

        rule.onNodeWithText("Done").performClick()
        fact("Done closes the wizard") { rule.runOnIdle { assertTrue(closed) } }
    }
}
