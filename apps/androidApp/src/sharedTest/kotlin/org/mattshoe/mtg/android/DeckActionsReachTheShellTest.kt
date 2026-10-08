package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.RenameState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The deck screen's actions, pressed on a device, reaching the shell.
 *
 * `DecksScreen` grew Rename, Add a card, the per-card "⋯" and the
 * share menu, and every one of them arrived at `AppShell` and stopped:
 * the call site never passed `onRename`, `onAddCard`, `onTweak` or
 * `onShare`, so the parameters took their no-op defaults. The screen
 * tests were green because they hand `DecksScreen` its own callbacks
 * and never go through the shell — exactly the seam the bug lived in.
 *
 * `DeckTweakSheet` and `RenameDialog` had the same shape of problem
 * from the other end: both were written and neither was ever
 * rendered, so the overlays they belong to could be opened and
 * nothing appeared.
 *
 * So these press the real control inside a real `AppShell` and assert
 * the shell did something. A compile cannot catch a parameter left at
 * its default; this can.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DeckActionsReachTheShellTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun deck() = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        // Signed in as the owner of these decks, because the edit
        // buttons ask whether you own *this* collection now and a
        // password answers nothing about that.
        admin = Admin().signIn(Account("matt"), session = "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(card("Alela, Artful Provocateur", "commander"), card("Sol Ring")),
        ),
    )

    /**
     * Mount a real shell over mutable state, the way the activity
     * does, so a press that the shell routes back into state is
     * visible on the next frame.
     */
    private fun shell(
        start: AppState = opened(),
        onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
        onAskRename: (String) -> Unit = {},
        onAddCard: () -> Unit = {},
        onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
        onSaveRename: () -> Unit = {},
    ): () -> AppState {
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    val live = held.value
                    state = live
                    AppShell(
                        state = live,
                        onState = { held.value = it; state = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onShare = onShare,
                        onAskRename = onAskRename,
                        onSaveRename = onSaveRename,
                        onAddCard = onAddCard,
                        onTweak = onTweak,
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    // ------------------------------------------------------------ share

    @Test
    fun theShareMenuReachesTheShell() {
        val picked = mutableListOf<Pair<ShareWhat, ExportTo>>()
        shell(onShare = { what, where -> picked += what to where })

        rule.onNodeWithContentDescription("Share this deck").performClick()
        rule.waitForIdle()
        // "Copy" appears under both groups; the first is the link.
        rule.onAllNodesWithText(ExportTo.CLIPBOARD.label).onFirst().performClick()
        rule.waitForIdle()

        assertEquals(
            listOf(ShareWhat.LINK to ExportTo.CLIPBOARD),
            picked,
            "the share menu was pressed and the shell heard nothing",
        )
    }

    @Test
    fun everyOptionInTheShareMenuIsOfferedOnThePhone() {
        shell()
        rule.onNodeWithContentDescription("Share this deck").performClick()
        rule.waitForIdle()
        ShareWhat.entries.forEach { what ->
            rule.onAllNodesWithText(what.label).onFirst().assertExists()
        }
        // Two groups, so each `ExportTo` label appears twice.
        ExportTo.entries.forEach { where ->
            assertEquals(
                2,
                rule.onAllNodesWithText(where.label).fetchSemanticsNodes().size,
                "${where.label} is not offered for both the link and the list",
            )
        }
    }

    // ----------------------------------------------------------- rename

    @Test
    fun renameReachesTheShell() {
        val asked = mutableListOf<String>()
        shell(onAskRename = { asked += it })
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()
        assertEquals(listOf("alela"), asked, "Rename was pressed and the shell heard nothing")
    }

    @Test
    fun theRenameDialogIsDrawnWhenItsOverlayIsOpen() {
        val saved = mutableListOf<Unit>()
        val start = opened()
            .copy(rename = RenameState(slug = "alela", was = "Alela"))
            .opening(Overlay.RENAME)
        shell(start = start, onSaveRename = { saved += Unit })

        rule.onNodeWithText("Rename deck").assertExists()
        // The address moves with the name, and the dialog says so
        // before anything is written.
        rule.onNodeWithText("Deck name").performTextReplacement("Alela Reborn")
        rule.waitForIdle()
        rule.onNodeWithText("It will live at #/decks/alela-reborn").assertExists()

        rule.onNodeWithTag("rename-save").performClick()
        rule.waitForIdle()
        assertEquals(1, saved.size, "Rename in the dialog did not reach the shell")
    }

    @Test
    fun theRenameDialogWillNotSaveANameThatHasNotChanged() {
        val saved = mutableListOf<Unit>()
        val start = opened()
            .copy(rename = RenameState(slug = "alela", was = "Alela"))
            .opening(Overlay.RENAME)
        shell(start = start, onSaveRename = { saved += Unit })

        rule.onNodeWithTag("rename-save").performClick()
        rule.waitForIdle()
        assertTrue(saved.isEmpty(), "it offered to rename a deck to the name it already has")
    }

    // ------------------------------------------------------------ tweak

    @Test
    fun addACardReachesTheShell() {
        var added = 0
        shell(onAddCard = { added++ })
        rule.onNodeWithText("+ Add a card").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, added, "Add a card was pressed and the shell heard nothing")
    }

    @Test
    fun theTweakSheetIsDrawnWhenItsOverlayIsOpen() {
        val start = opened().let { s ->
            s.copy(
                deckTweak = org.mattshoe.mtg.core.DeckTweak.on(
                    s.decks.open!!, "Alela, Artful Provocateur", card("Sol Ring"), Tweak.SWAP,
                ),
            ).opening(Overlay.DECK_TWEAK)
        }
        shell(start = start)
        rule.onNodeWithText(Tweak.SWAP.title).assertExists()
    }

    @Test
    fun backTakesTheTweakSheetOffBeforeItLeavesTheDeck() {
        val start = opened().let { s ->
            s.copy(
                deckTweak = org.mattshoe.mtg.core.DeckTweak.on(
                    s.decks.open!!, "Alela, Artful Provocateur", card("Sol Ring"), Tweak.SWAP,
                ),
            ).opening(Overlay.DECK_TWEAK)
        }
        val read = shell(start = start)
        assertNotNull(read().dismissTop(), "back would leave the deck with the sheet still up")
        assertEquals(
            Overlay.DECK_TWEAK,
            read().overlays.top,
            "the sheet is not the thing back would take off first",
        )
    }
}
