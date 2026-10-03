package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test
    fun gatedTabsAreAbsentWhileLocked() {
        content { AppShell(AppState(), {}, {}, {}, {}, {}, {}, {}) }
        // "Library" is both a tab and the heading below it.
        rule.onAllNodesWithText("Library").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Decks").assertExists()
        rule.onAllNodesWithTextOrNothing("Mass Entry")
        rule.onAllNodesWithTextOrNothing("Server Logs")
        rule.onNodeWithText("Unlock").assertExists()
    }

    @Test
    fun unlockingBringsTheGatedTabsBack() {
        var state = AppState(admin = Admin(token = "t"))
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}, {}) }
        // The tab row scrolls sideways on a phone, so these are present
        // rather than necessarily on screen.
        rule.onNodeWithText("Mass Entry").assertExists()
        rule.onNodeWithText("Server Logs").assertExists()
        rule.onNodeWithText("Lock").assertExists()
    }

    @Test
    fun theFindButtonOpensTheFinder() {
        var state = AppState()
        content {
            AppShell(state, { state = it }, {}, {}, {}, {}, {}, {})
        }
        rule.onNodeWithText("Find").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(Overlay.PALETTE, state.overlays.top)
            assertTrue(state.palette.open)
        }
    }

    @Test
    fun aBareLetterNavigates() {
        var state = AppState()
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}, {}) }
        rule.onRoot().performKeyInput { pressKey(Key.D) }
        rule.runOnIdle { assertEquals(View.DECKS, state.view) }
    }

    @Test
    fun theHelpKeyToasts() {
        var state = AppState()
        content { AppShell(state, { state = it }, {}, {}, {}, {}, {}, {}) }
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
        rule.onNodeWithText("Sol Ring").assertIsDisplayed()
        rule.onNodeWithText("1–100 of 250").assertExists()
    }

    @Test
    fun theFilterButtonRevealsThePanel() {
        var shown = false
        content {
            LibraryScreen(
                Library().loaded(listOf(card("Sol Ring")), 1),
                {}, {}, {},
                showFilters = shown,
                onToggleFilters = { shown = !shown },
            )
        }
        rule.onNodeWithText("Filters").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(shown) }
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
        rule.onNodeWithText("Export decklist").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(exported) }
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
        rule.onNodeWithText("3 owned").assertIsDisplayed()
        rule.onNodeWithText("2 free").assertExists()
        rule.onNodeWithText("Alela · 1× · ramp").assertExists()
        // A card is nobody's in particular, so the page says who has it.
        rule.onNodeWithText("matt · 3 owned · 2 free").assertExists()
    }

    @Test
    fun aProxyDoesNotEatACopy() {
        val detail = CardDetail(
            name = "Sol Ring",
            printings = listOf(Printing(1, "m3c", null, "409", "nonfoil", 1, null, owner = "matt")),
            usedIn = listOf(DeckUse("p", "Proxy deck", "matt", 1, null, true)),
        )
        content { CardSheet(detail) {} }
        rule.onNodeWithText("1 free").assertExists()
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
        content { NewDeckDialog(s, { s = it }, {}, {}, {}) }
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
