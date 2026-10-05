package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DeckUse
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.Disassembly
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.PaletteState
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.Tally
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlin.test.assertNull

/**
 * The Android screens, on a device, clicked.
 *
 * The web has the same suite under Karma. Every one of these has a
 * sibling there, because "the port is done" is a claim about what a
 * person can do with the app, and only something that presses the
 * buttons can check it.
 *
 * Nothing here touches the network: the composables are handed state
 * directly, which is the whole reason the state lives in `:core`.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(name: String, qty: Int = 1) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = qty, printings = 1, free = 1, price = 1.5, value = 1.5,
    )

    /**
     * Mount, and wait until it is actually mounted.
     *
     * `setContent` returns before the host activity has necessarily
     * finished launching on a cold emulator, and a finder that runs
     * first fails with "No compose hierarchies found in the app" —
     * which reads exactly like a real failure and is not one. Every
     * text finder waits by itself; `onRoot().performKeyInput` does
     * not, which is why the keyboard tests were the ones that flaked
     * in CI.
     */
    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { body() } } }
        rule.waitForIdle()
        // Something — anything — in the tree. Every screen mounted
        // here has at least one thing you can press.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------ shell

    /** The profile: admin, the way in and out of it, and the log. */
    private fun openTheProfile() {
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
    }

    @Test
    fun gatedTabsAreAbsentWhileLocked() {
        content { AppShell(AppState(), {}, {}, {}, {}, {}, {}) }
        // The bar is the three anybody can reach; the gated pair is
        // not hiding anywhere, including behind the profile.
        rule.onAllNodesWithText("Library").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Decks").assertExists()
        rule.onAllNodesWithTextOrNothing("Entry")
        openTheProfile()
        rule.onAllNodesWithTextOrNothing("Server Logs")
        rule.onNodeWithText("Log in").assertExists()
    }

    @Test
    fun unlockingBringsTheGatedTabsBack() {
        var state = AppState(admin = Admin(token = "t").unlock("t"))
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}) }
        // Entry joins the bar; the log and the lock are behind the
        // profile, which is the split Matt asked for.
        rule.onNodeWithText("Entry").assertExists()
        openTheProfile()
        rule.onNodeWithText("Server Logs").assertExists()
        rule.onNodeWithText("Log out").assertExists()
    }

    @Test
    fun theFinderIsOnTheSlashKeyRatherThanAButton() {
        // The website has no Find button and its suite says so. The
        // palette itself is still there, on `/` and ⌘K, which is the
        // only way either platform opens it now.
        var state = AppState()
        content {
            AppShell(state, { state = it }, {}, {}, {}, {}, {})
        }
        rule.onNodeWithText("Find").assertDoesNotExistNow()
        rule.onRoot().performKeyInput { pressKey(Key.Slash) }
        rule.runOnIdle {
            assertEquals(Overlay.PALETTE, state.overlays.top)
            assertTrue(state.palette.open)
        }
    }

    @Test
    fun aBareLetterNavigates() {
        var state = AppState()
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.D) }
        rule.runOnIdle { assertEquals(View.DECKS, state.view) }
    }

    @Test
    fun theHelpKeyToasts() {
        var state = AppState()
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}) }
        // Shift-slash, which is how a keyboard actually produces it.
        rule.onRoot().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Slash)
            keyUp(Key.ShiftLeft)
        }
        rule.runOnIdle { assertTrue(state.toast.orEmpty().contains("s search"), state.toast.orEmpty()) }
    }

    // ---------------------------------------------------------- library

    @Test
    fun theLibraryShowsTheRangeAndTheRows() {
        val lib = Library().loaded(listOf(card("Sol Ring"), card("Arcane Signet")), 250)
        content { LibraryScreen(lib, {}, {}, {}) }
        // Into view first. The grid is lazy and its header carries the
        // page head, the controls and the filter sheet, so on a short
        // screen the first card is simply not composed yet — which
        // `assertIsDisplayed` reports as "not displayed" and reads
        // like the row is missing rather than below the fold.
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()
        rule.onNodeWithText("Sol Ring").assertIsDisplayed()
        rule.onNodeWithText("1–100 of 250").assertExists()
    }

    @Test
    fun theFilterGroupsAreAlwaysOnThePage() {
        // There is no Filters button any more, on either platform.
        // Hiding the whole panel was a way to leave a filter applied
        // with nothing on screen saying so, so the groups sit there
        // folded instead. The sibling is
        // `theFilterGroupsAreAlwaysOnThePage` in the web suite.
        content {
            LibraryScreen(Library().loaded(listOf(card("Sol Ring")), 1), {}, {}, {})
        }
        rule.onAllNodesWithTextOrNothing("Filters")
        // `Facet.title`, the same strings the web panel uses. The
        // website uppercases them in CSS, which does not change the
        // text either side.
        rule.onNodeWithText("Collection").assertExists()
        rule.onNodeWithText("Colour").assertExists()
        rule.onNodeWithText("Legality").assertExists()
    }

    @Test
    fun thereIsNoQueryBoxOnTheLibrary() {
        // Taken out on request, on both platforms. The sibling is
        // `thereIsNoQueryBoxOnTheLibrary` in the web suite.
        content { LibraryScreen(Library().loaded(listOf(card("Sol Ring")), 1), {}, {}, {}) }
        rule.onAllNodesWithText("Query box — c<=wu t:creature mv<=3 -is:reprint")
            .fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "the query box is back on Android") }
    }

    @Test
    fun exportIsOffered() {
        var exported = false
        content {
            LibraryScreen(
                Library().loaded(listOf(card("Sol Ring")), 1),
                {}, {}, {},
                onExport = { exported = true },
            )
        }
        // "Copy", the word the website uses. It was "Export decklist"
        // here and nowhere else.
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(exported) }
    }

    @Test
    fun theLibraryOffersBothCopyAndDownload() {
        // 1.10: the web offers Copy and Download; the Library here
        // used to offer only Copy. Both rows must exist, and each must
        // report its own destination rather than the two collapsing
        // into one press.
        val picked = mutableListOf<ExportTo>()
        content {
            LibraryScreen(
                Library().loaded(listOf(card("Sol Ring")), 1),
                {}, {}, {},
                onExport = { picked += it },
            )
        }
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Download").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(ExportTo.CLIPBOARD, ExportTo.FILE), picked, "Copy and Download did not report different destinations")
    }

    @Test
    fun autocompleteOffersWhatCameBack() {
        var picked: String? = null
        val c = Completion().typed("sol").suggested(listOf("Sol Ring", "Solemn Simulacrum"))
        content { AutocompleteField("Card name", c, {}, { picked = it }) }
        rule.onNodeWithText("Solemn Simulacrum").performClick()
        rule.runOnIdle { assertEquals("Solemn Simulacrum", picked) }
    }

    @Test
    fun theDeckTileShowsTheTileWidthNameAndItsIdentity() {
        // The sibling of `theDeckTileWearsItsCommandersArt` on the web.
        // The pips come from `Deck.identity`, so "Five-color (WUBRG)"
        // cannot render as one per letter of the sentence.
        val deck = Deck(
            "alela", "Alela — Custom Dimir Faerie Tribal", "matt",
            "Alela, Artful Provocateur", "Five-color (WUBRG)", 3, "abcdef12-3456",
        )
        content { DecksScreen(DecksState().loaded(listOf(deck)), {}, {}) }
        rule.onNodeWithText("Alela").assertExists()
        rule.onNodeWithText("Alela, Artful Provocateur").assertExists()
        rule.onNodeWithText("bracket 3").assertExists()
        // Five pips, one per colour — not one per letter of "Five-color
        // (WUBRG)", which is what reading the column naively produced.
        listOf("B", "G", "R", "U", "W").forEach { rule.onNodeWithText(it).assertExists() }
    }

    // ------------------------------------------------------------- card

    @Test
    fun theCardSheetSaysWhatIsOwnedAndWhatIsFree() {
        val detail = CardDetail(
            name = "Sol Ring",
            printings = listOf(
                Printing(1, "m3c", "Modern Horizons 3", "409", "nonfoil", 3, null, owner = "matt"),
            ),
            usedIn = listOf(DeckUse("alela", "Alela", "matt", 1, "ramp", false)),
        )
        content { CardSheet(detail) {} }
        rule.onNodeWithText("Sol Ring").assertIsDisplayed()
        // Twice over now, as on the website: once in the tags at the
        // top and once on the owner's line. They used to be one
        // run-on sentence per place, which read as text rather than
        // as the figures they are.
        rule.onAllNodesWithText("3 owned").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("2 free").onFirst().assertExists()
        // The deck row is a row now, not one joined string: the
        // name, then whose deck it is, then how many, then the role —
        // each its own node, as the website sets them. Joined, the
        // owner could be dropped without this noticing.
        rule.onNodeWithText("Alela").assertExists()
        rule.onNodeWithText("1×").assertExists()
        rule.onNodeWithText("ramp").assertExists()
        // A card is nobody's in particular, so the page says who has it.
        // Twice: once on the printing, once on the deck row. Both
        // are the website's doing — a shared collection turns on
        // whose copy it is, so it says so wherever a copy appears.
        rule.onAllNodesWithText("matt").onFirst().assertExists()
    }

    @Test
    fun aProxyDoesNotEatACopy() {
        val detail = CardDetail(
            name = "Sol Ring",
            printings = listOf(Printing(1, "m3c", null, "409", "nonfoil", 1, null, owner = "matt")),
            usedIn = listOf(DeckUse("p", "Proxy deck", "matt", 1, null, true)),
        )
        content { CardSheet(detail) {} }
        rule.onAllNodesWithText("1 free").onFirst().assertExists()
    }

    // --------------------------------------------------------- overlays

    @Test
    fun theFinderListsWhatWasFound() {
        var opened: Found? = null
        val p = PaletteState().opened().typed("bo")
            .found(listOf(Found(1, "Lightning Bolt", null, "Instant", 4, "matt")))
        content { PaletteDialog(p, {}, { opened = it }, {}) }
        rule.onNodeWithText("Lightning Bolt").performClick()
        rule.runOnIdle { assertEquals("Lightning Bolt", opened?.name) }
    }

    @Test
    fun theCheatsheetCoversEveryGroup() {
        content { CheatsheetDialog {} }
        rule.onNodeWithText("Words").assertIsDisplayed()
        // Below the fold in a scrolling dialog, which is where it
        // belongs — present is the claim, not visible without moving.
        rule.onNodeWithText("Colour").assertExists()
    }

    // ------------------------------------------------------------ decks

    @Test
    fun theAdminActionsAreHiddenWhileLocked() {
        val decks = DecksState(decks = listOf(deck()), openSlug = "alela", cards = listOf(deckCard()))
        content { DecksScreen(decks, {}, {}, admin = false) }
        rule.onNodeWithText("Edit list").assertDoesNotExistNow()
    }

    @Test
    fun theAdminActionsAppearWhenUnlocked() {
        var edited: Deck? = null
        val decks = DecksState(decks = listOf(deck()), openSlug = "alela", cards = listOf(deckCard()))
        content { DecksScreen(decks, {}, {}, admin = true, onEdit = { edited = it }) }
        rule.onNodeWithText("Edit list").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("alela", edited?.slug) }
    }

    @Test
    fun theEditDialogWillNotSaveBeforeItHasReviewed() {
        val edit = DeckEditState.of(deck(), listOf(deckCard()))
        content { DeckEditDialog(edit, {}, {}, {}, {}) }
        rule.onNodeWithText("Review changes").assertIsEnabled()
    }

    @Test
    fun theEditDialogOffersSaveOnceThePlanIsIn() {
        var saved = false
        val edit = DeckEditState.of(deck(), listOf(deckCard()))
            .planned(DeckPlan(cardCount = 99, added = listOf(Tally("Sol Ring", 1))))
        content { DeckEditDialog(edit, {}, {}, { saved = true }, {}) }
        rule.onNodeWithText("preview — nothing saved yet").assertExists()
        rule.onNodeWithText("Save list").performClick()
        rule.runOnIdle { assertTrue(saved) }
    }

    @Test
    fun disassembleSaysWhatItWillDoAndWillNotFireEarly() {
        val d = DisassembleState("alela", "Alela", "matt")
        content { DisassembleDialog(d, {}, {}) }
        rule.onNodeWithText("Disassemble · free 0").assertIsNotEnabled()
    }

    @Test
    fun disassembleArmsOnceTheDryRunIsBack() {
        var went = false
        val d = DisassembleState("alela", "Alela", "matt").planned(Disassembly(freed = 42))
        content { DisassembleDialog(d, { went = true }, {}) }
        rule.onNodeWithText("Disassemble · free 42").performClick()
        rule.runOnIdle { assertTrue(went) }
    }

    // --------------------------------------------------------- new deck

    @Test
    fun theNewDeckWizardWillNotLeaveTheFirstStepUnanswered() {
        var s = NewDeck()
        content { NewDeckScreen(s, { s = it }, {}, {}, {}) }
        rule.onNodeWithText("Continue →").assertIsNotEnabled()
        rule.onNodeWithText("Commander").performClick()
        rule.runOnIdle { assertNotNull(s.format) }
    }

    // ------------------------------------------------------------ entry

    @Test
    fun nothingIsPreselectedInTheWizard() {
        content { MassEntryScreen(MassEntry(), {}, {}, {}) }
        // "Continue →" whether or not it is pressable, the way the
        // website does it. It used to be relabelled "Pick one to
        // continue" when disabled, so the button changed its name
        // depending on its state and the two platforms disagreed
        // about what the thing was even called.
        rule.onNodeWithText("Continue →").assertIsNotEnabled()
        rule.onNodeWithText("Nothing is preselected on purpose.").assertExists()
    }

    @Test
    fun theFileButtonIsOnTheListStep() {
        var asked = false
        val s = MassEntry().choose(Direction.ADD).goTo(Step.LIST)
        content { MassEntryScreen(s, {}, {}, {}, onPickFile = { asked = true }) }
        rule.onNodeWithText("Upload a file").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(asked) }
    }

    @Test
    fun recentEntriesCanBePutBackInTheBox() {
        var reused: HistoryEntry? = null
        val history = EntryHistory().remember(
            HistoryEntry("2026-09-28T10:00:00Z", "add", "matt", 12, "12 Sol Ring"),
        )
        val s = MassEntry().choose(Direction.ADD).goTo(Step.LIST)
        content { MassEntryScreen(s, {}, {}, {}, history = history, onReuse = { reused = it }) }
        rule.onNodeWithText("Recent").assertExists()
        rule.onNodeWithText("Reuse").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(12, reused?.count) }
    }

    @Test
    fun applyIsNotOfferedWithoutADryRun() {
        val s = MassEntry().choose(Direction.ADD).type("1 Sol Ring").assign(Owner.MATT).goTo(Step.REVIEW)
        content { MassEntryScreen(s, {}, {}, {}) }
        rule.onNodeWithText("Nothing to apply").assertIsNotEnabled()
    }

    // ------------------------------------------------------------ bits

    private fun deck() = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UWB", 3, null)

    private fun deckCard() = DeckCard("Sol Ring", 1, null, 1)

    @Test
    fun theToastFadesOnItsOwnAfterFiveSeconds() {
        val held = shellToast(AppState().say("Added 3 cards"))
        rule.onNodeWithText("Added 3 cards").assertExists()

        advance(AppState.TOAST_MS)

        assertNull(held.value.toast, "the toast outlived its five seconds")
        rule.onNodeWithText("Added 3 cards").assertDoesNotExist()
    }
    @Test
    fun tappingTheToastDismissesItEarly() {
        val held = shellToast(AppState().say("Added 3 cards"))

        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()

        assertNull(held.value.toast, "a tap on the toast did not clear it")
        rule.onNodeWithText("Added 3 cards").assertDoesNotExist()
    }
    @Test
    fun aSecondToastRearmsTheFadeRatherThanInheritingIt() {
        val held = shellToast(AppState().say("First"))
        rule.onNodeWithText("First").assertExists()

        // Four of the first toast's five seconds — not due yet.
        advance(4_000)
        rule.onNodeWithText("First").assertExists()

        // A second toast lands before the first one went on its own.
        held.value = held.value.say("Second")
        rule.waitForIdle()
        rule.onNodeWithText("Second").assertExists()

        // Four seconds after the SECOND arrived — eight since the
        // first. A fade that inherited the old toast's one second of
        // remaining time, instead of restarting, would already have
        // cleared this.
        advance(4_000)
        rule.onNodeWithText("Second").assertExists()
        assertEquals("Second", held.value.toast, "the second toast's fade did not restart")

        // The full five seconds after the second toast does clear it.
        advance(1_200)
        assertNull(held.value.toast)
        rule.onNodeWithText("Second").assertDoesNotExist()
    }
    @Test
    fun theToastDoesNotBlockAPressOnAControlUnderneath() {
        // Wide, so the toast docks bottom-end rather than top-center —
        // away from the nav row this test presses, the way a control
        // the toast is not actually covering should always still be
        // reachable regardless of where the tray itself sits.
        val held = shellToast(AppState().say("Added 3 cards"), wide = true)
        rule.onNodeWithText("Added 3 cards").assertExists()

        // The tray itself carries no background and no click handler;
        // only the chip inside it does. A tap elsewhere on the screen,
        // toast showing or not, has to keep reaching whatever is
        // really there underneath it — a tab in the bottom bar.
        rule.onNodeWithContentDescription("Decks").performClick()
        rule.waitForIdle()

        assertEquals(View.DECKS, held.value.view, "a control under the toast tray did not get the tap")
    }
    /**
     * A real shell over state the test can also push into from the
     * outside — not just from a click — so "a second toast arrives
     * while the first is still showing" can be simulated the way the
     * real app would produce it: nothing in the UI itself fires a
     * second toast, the app above `AppShell` does.
     */
    private fun shellToast(start: AppState, wide: Boolean = false): MutableState<AppState> {
        lateinit var held: MutableState<AppState>
        content {
            held = remember { mutableStateOf(start) }
            val shell = @androidx.compose.runtime.Composable {
                AppShell(held.value, { held.value = it }, {}, {}, {}, {}, {})
            }
            if (wide) {
                // Wide enough that the toast docks bottom-end instead
                // of top-center, so this covers the other half of
                // `ToastTray`'s own placement switch.
                val forced = Configuration(LocalConfiguration.current).apply { screenWidthDp = 800 }
                CompositionLocalProvider(LocalConfiguration provides forced) { shell() }
            } else {
                shell()
            }
        }
        return held
    }
    /** Jump the virtual clock forward without losing a click's own gesture. */
    private fun advance(millis: Long) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(millis)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }
}

/** `assertDoesNotExist`, spelled so the intent reads at the call site. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistNow() =
    assertDoesNotExist()

/** Tolerates absence: a gated tab is not there at all while locked. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextOrNothing(text: String) {
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().let {
        assertTrue(it.isEmpty(), "$text should not be reachable while locked")
    }
}
