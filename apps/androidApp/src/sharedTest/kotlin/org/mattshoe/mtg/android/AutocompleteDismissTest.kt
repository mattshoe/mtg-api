package org.mattshoe.mtg.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View as AndroidView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting rid of the card-name suggestion list.
 *
 * Android parity item 1.4. The Android app had no `onDismiss` anywhere
 * in it: picking a suggestion, or typing back down below two
 * characters, were the only two ways the list ever closed. Tap the
 * sort control, tap a card in the grid, press system Back — the
 * suggestions stayed sitting over the screen. The web had the same bug
 * and fixed it with a document-level `pointerdown` listener that closes
 * the list without calling `preventDefault`, so the tap that closed it
 * still does whatever it was aimed at.
 *
 * These mount a real `AppShell` over a `ComponentActivity`, not
 * `AutocompleteField` on its own, for the same reason
 * `ExitWarnsOnUnsavedEntryTest` does: the bug was never inside the
 * field, it was that nothing above the field ever asked. A test that
 * mounted the field alone would have passed throughout.
 *
 * Arrow keys and Enter are deliberately not covered. They are not
 * implemented — which is why `Completion.active` and the bold styling
 * that reads it are still dead code on the phone. The list is
 * dismissable and pickable, not navigable.
 */
@RunWith(AndroidJUnit4::class)
class AutocompleteDismissTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val exported = mutableListOf<ExportTo>()
    private val looked = mutableListOf<String>()

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    /** A fixture whose name box has a list open over it. */
    private fun listOpen(vararg names: String) = Completion()
        .typed("sol")
        .suggested(names.toList())

    private fun onADeck() = AppState(admin = Admin(token = "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let { it.copy(decks = it.decks.loaded(listOf(deck())).opened("alela", emptyList())) }

    private fun shell(start: AppState): Pair<() -> AppState, (AppState) -> Unit> {
        var state = start
        var push: (AppState) -> Unit = {}
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = remember { mutableStateOf(start) }
                    state = held.value
                    push = { held.value = it; state = it }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onExport = { exported += it },
                        onLookup = { looked += it },
                    )
                }
            }
        }
        rule.waitForIdle()
        return ({ state }) to { next: AppState -> push(next); rule.waitForIdle() }
    }

    private fun pressBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    /**
     * The window the suggestion list is drawn in, which must not be
     * the app's own.
     *
     * Found through a suggestion's text rather than a test tag on
     * purpose: nothing here names anything that only exists once the
     * fix does, so the whole file still compiles and still runs
     * against the broken code. A test that cannot be run red proves
     * nothing about what it is guarding.
     */
    private fun suggestionWindow(suggestion: String): AndroidView {
        val list = rule.onNodeWithText(suggestion).fetchSemanticsNode().root
        val field = rule.onNodeWithText("Card name").fetchSemanticsNode().root
        assertTrue(
            list !== field,
            "the suggestion list is drawn inside the screen rather than in a window of its own, " +
                "so there is nothing the platform can tell about a tap that landed outside it",
        )
        return generateSequence(list as AndroidView) { it.parent as? AndroidView }.last()
    }

    /**
     * One tap that lands somewhere else, both halves of it.
     *
     * A physical tap outside a window flagged `FLAG_NOT_FOCUSABLE |
     * FLAG_WATCH_OUTSIDE_TOUCH` produces two deliveries: an
     * `ACTION_OUTSIDE` to that window, which is what
     * `dismissOnClickOutside` listens for, and the ordinary touch to
     * the window behind it, which is what makes it non-consuming. The
     * Compose test harness only ever injects into one root at a time,
     * so the two halves are issued here by hand — the outside touch
     * into the suggestion list's own window, the press onto the
     * control the tap was aimed at.
     *
     * The flag assertions are load-bearing, not decoration: they are
     * what fails if this ever becomes a focusable popup or a
     * full-screen scrim, either of which would swallow the tap instead
     * of letting it through to what it was aimed at. What they cannot
     * prove on the JVM is the window manager's own half — Robolectric
     * does not route touches between windows, so `ACTION_OUTSIDE`
     * arriving at all is only checked on a device.
     */
    private fun tapOutsideTheList(aimedAt: String, suggestion: String = "Solemn Simulacrum") {
        val window = suggestionWindow(suggestion)
        val flags = (window.layoutParams as WindowManager.LayoutParams).flags
        assertTrue(
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
            "the suggestion list takes input focus, so the box would lose the keyboard",
        )
        assertTrue(
            flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0,
            "the suggestion list is not watching for taps outside it, so nothing can close it",
        )

        outsideTouch(window)
        rule.onNodeWithText(aimedAt).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun outsideTouch(window: AndroidView) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, 1f, 1f, 0)
        window.dispatchTouchEvent(event)
        event.recycle()
        rule.waitForIdle()
    }

    // ------------------------------------------------------- it opens

    @Test
    fun typingACardNameOpensTheList() {
        val (read, set) = shell(AppState())

        rule.onNodeWithText("Card name").performScrollTo().performTextInput("sol")
        rule.waitForIdle()

        assertEquals("sol", looked.lastOrNull(), "typing never asked the caller for suggestions")
        assertFalse(read().complete.open, "a list was open before any names came back")

        // What `MainActivity` does with the answer.
        set(read().typedCardName(read().complete.suggested(listOf("Sol Ring", "Solemn Simulacrum"))))

        assertTrue(read().complete.open)
        rule.onNodeWithText("Solemn Simulacrum").assertExists("the suggestion list is not on screen")
    }

    // ----------------------------------------------- a tap elsewhere

    @Test
    fun aTapOnAnUnrelatedControlClosesTheListAndStillDoesWhatItWasAimedAt() {
        val (read, _) = shell(AppState().typedCardName(listOpen("Sol Ring", "Solemn Simulacrum")))
        rule.onNodeWithText("Solemn Simulacrum").assertExists("the fixture never opened the list")

        tapOutsideTheList(aimedAt = ExportTo.CLIPBOARD.label)

        assertFalse(read().complete.open, "a tap somewhere else left the suggestions on screen")
        assertEquals(
            listOf(ExportTo.CLIPBOARD),
            exported,
            "closing the list ate the tap that closed it — Copy never fired",
        )
    }

    @Test
    fun aTapThatClosesTheListKeepsWhatWasTypedInTheBox() {
        // The whole reason `onDismiss` exists rather than closing
        // through `onState` with a rebuilt `Completion`: a close that
        // carries a term can hand back a term from before the box was
        // last touched. Clear the box, tap elsewhere, and the search
        // you had just cleared comes back.
        val (read, _) = shell(AppState().typedCardName(listOpen("Sol Ring", "Solemn Simulacrum")))

        tapOutsideTheList(aimedAt = ExportTo.CLIPBOARD.label)

        assertEquals("sol", read().complete.term, "closing the list also rewrote the name")
        assertEquals("sol", read().library.filters.q, "closing the list rewrote the name filter")
    }

    @Test
    fun pickingASuggestionStillWorksFromInsideItsOwnWindow() {
        val (read, _) = shell(AppState().typedCardName(listOpen("Sol Ring", "Solemn Simulacrum")))

        rule.onNodeWithText("Solemn Simulacrum").performClick()
        rule.waitForIdle()

        assertEquals("Solemn Simulacrum", read().complete.term, "the tapped name did not land in the box")
        assertFalse(read().complete.open, "picking left the list open")
    }

    @Test
    fun theListIsOverTheScreenRatherThanInsideIt() {
        // Why a `Popup` and not a `Column` under the box. The web's
        // list is `position: absolute`, so suggestions appearing never
        // shoved the sort control and the whole card grid down the
        // screen and then yanked them back. A separate root is the
        // measurable version of that: the list is not in the Library's
        // layout at all — which is also what gives it a window of its
        // own to hear outside taps with.
        shell(AppState().typedCardName(listOpen("Sol Ring", "Solemn Simulacrum")))

        val listRoot = rule.onNodeWithText("Solemn Simulacrum").fetchSemanticsNode().root
        val fieldRoot = rule.onNodeWithText("Card name").fetchSemanticsNode().root
        assertTrue(listRoot !== fieldRoot, "the suggestion list is still inside the Library's own layout")
    }

    // ----------------------------------------------------------- Back

    @Test
    fun backClosesTheListBeforeItTouchesAnythingElse() {
        val (read, _) = shell(onADeck().typedCardName(listOpen("Sol Ring", "Solemn Simulacrum")))
        assertTrue(read().complete.open, "the fixture never opened the list")

        pressBack()

        assertFalse(read().complete.open, "Back left the suggestions on screen")
        assertEquals(
            "alela",
            read().decks.openSlug,
            "Back closed the open deck underneath instead of the list over it",
        )
        assertEquals(View.DECKS, read().view, "Back navigated instead of closing the list")
    }

    @Test
    fun backWithTheListAlreadyClosedStillClosesAnOpenDeck() {
        // The guard on the reordering above: putting the list first in
        // `AppState.back` must not hand it the gesture when there is no
        // list.
        val (read, _) = shell(onADeck())
        assertFalse(read().complete.open, "the fixture opened a list it was not supposed to")

        pressBack()

        assertNull(read().decks.openSlug, "Back no longer comes out of an open deck")
        assertEquals("", read().route.rest, "coming out of the deck left its slug in the address")
    }

    // ------------------------------------------- the wizard's own box

    @Test
    fun theWizardsCommanderBoxCanBeDismissedToo() {
        // The dialog is its own window, and its box is `NewDeck.hint`
        // rather than `AppState.complete`, so it needs its own wiring
        // — the Library's was no help to it at all.
        val hint = Completion().typed("ale")
            .suggested(listOf("Alela, Artful Provocateur", "Alela, Cunning Conqueror"))
        val s = mutableStateOf(
            NewDeck().pick(Format.COMMANDER).assign(Owner.MATT).rename("Test Deck")
                .goTo(DeckStep.COMMANDER).copy(hint = hint),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    NewDeckDialog(
                        state = s.value,
                        onState = { s.value = it },
                        onCheck = {},
                        onCreate = {},
                        onClose = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Alela, Cunning Conqueror").assertExists("the wizard's list is not on screen")

        val list = rule.onNodeWithText("Alela, Cunning Conqueror").fetchSemanticsNode().root
        val box = rule.onNodeWithText("e.g. Alela, Artful Provocateur").fetchSemanticsNode().root
        assertTrue(list !== box, "the wizard's suggestion list has no window of its own either")
        outsideTouch(generateSequence(list as AndroidView) { it.parent as? AndroidView }.last())

        assertFalse(s.value.hint.open, "the wizard's suggestion list cannot be dismissed")
        assertEquals("ale", s.value.hint.term, "dismissing the list rewrote the commander being typed")
    }
}
