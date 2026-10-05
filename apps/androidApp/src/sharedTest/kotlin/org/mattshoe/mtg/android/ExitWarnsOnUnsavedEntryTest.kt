package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals

/**
 * Matt: "if you get to exit early, i want you to alert the user that
 * the changes will not be saved." The web already refuses to close a
 * tab over an unapplied Mass Entry list — `Main.kt`'s `beforeunload`
 * checks `app.entry.unsaved` and blocks the close. Android's
 * `BackHandler` forwarded a null `back()` straight to `onExit()` and
 * never asked, so leaving the app with a pasted list still in the box
 * threw it away in silence.
 *
 * These press the hardware back button through a real `AppShell`,
 * mounted over a `ComponentActivity` so `BackHandler`'s dispatcher is
 * the real one — the same object `MainActivity` registers against —
 * rather than asserting on `AppState.back()` or
 * `AppState.wouldExitWithUnsavedEntry` directly, which would miss the
 * shell never having wired the check in at all.
 */
@RunWith(AndroidJUnit4::class)
class ExitWarnsOnUnsavedEntryTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shell(start: AppState, onExit: () -> Unit): () -> AppState {
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
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onExit = onExit,
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    private fun pressBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    @Test
    fun backWithAnUnappliedListAsksBeforeItExits() {
        var exited = 0
        val start = AppState().copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        shell(start) { exited++ }

        pressBack()

        assertEquals(0, exited, "back left the app without ever asking about the list")
        rule.onNodeWithText("Your list has not been written to the collection yet.").assertExists()
    }

    @Test
    fun choosingToLeaveAnywayExits() {
        var exited = 0
        val start = AppState().copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        shell(start) { exited++ }

        pressBack()
        rule.onNodeWithText("Leave anyway").performClick()
        rule.waitForIdle()

        assertEquals(1, exited, "confirming the discard did not reach onExit")
    }

    @Test
    fun choosingToKeepEditingStaysPutAndKeepsTheList() {
        var exited = 0
        val start = AppState().copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        val read = shell(start) { exited++ }

        pressBack()
        rule.onNodeWithText("Keep editing").performClick()
        rule.waitForIdle()

        assertEquals(0, exited, "keep editing exited anyway")
        assertEquals("1 Sol Ring", read().entry.list, "the list was lost even though editing was kept")
    }

    @Test
    fun backWithNothingUnsavedExitsWithoutAsking() {
        var exited = 0
        shell(AppState()) { exited++ }

        pressBack()

        assertEquals(1, exited, "an empty box still asked before leaving")
    }

    @Test
    fun backAfterASuccessfulApplyExitsWithoutAsking() {
        var exited = 0
        val start = AppState().copy(
            entry = MassEntry().copy(list = "1 Sol Ring", result = Applied(applied = true)),
        )
        shell(start) { exited++ }

        pressBack()

        assertEquals(1, exited, "a list already written to the collection still asked before leaving")
    }

    @Test
    fun movingToAnotherTabDoesNotAskEvenWithAnUnappliedList() {
        // Reported as "leaving the mass entry screen" — switching tabs
        // keeps the state above the wizard alive, the same as the web,
        // so it is not the moment to warn about anything.
        var exited = 0
        val start = AppState(admin = Admin(token = "t"), route = Route(View.ENTRY))
            .copy(entry = MassEntry().copy(list = "1 Sol Ring"))
        val read = shell(start) { exited++ }

        // Behind the hamburger now, at every width (section 5).
        rule.onNodeWithContentDescription("Decks").performClick()
        rule.waitForIdle()

        assertEquals(0, exited, "switching tabs exited the app")
        assertEquals(View.DECKS, read().view, "the tap did not change tabs")
        assertEquals("1 Sol Ring", read().entry.list, "switching tabs lost the list")
    }
}
