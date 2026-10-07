package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The new deck wizard's commander box and "Upload a file" button,
 * pressed on a device, reaching the shell.
 *
 * `AppShell` had no `onCommanderTyped` parameter at all, so the
 * dialog took its no-op default and the commander box never
 * suggested anything. "Upload a file" had the same shape of problem
 * from the other end: `AppShell`'s call to `NewDeckDialog` simply
 * omitted `onPickFile`, even though the shell already had one — it
 * was wired to mass entry and never forwarded here.
 *
 * A component handed its own callback cannot catch either bug —
 * `NewDeckDialog` already declares both parameters and works fine in
 * isolation. Only a real `AppShell`, wired the way the activity wires
 * it, shows that neither callback ever arrives.
 */
@RunWith(AndroidJUnit4::class)
class NewDeckWizardReachesTheShellTest {

    @get:Rule
    val rule = createComposeRule()

    // On the Entry tab, because that is where the wizard lives now:
    // it is the other half of the entry wizard's first question, and
    // the shell composes it in place of the entry wizard rather than
    // floating it over whatever tab you happened to be on.
    private fun opened(step: DeckStep) = AppState(
        admin = Admin().signIn(Account(slug = "matt", role = "admin"), "t"),
        route = Route(View.ENTRY),
        newDeck = NewDeck(
            step = step,
            format = Format.COMMANDER,
            name = "Test Deck",
            commander = if (step == DeckStep.CARDS) "Alela, Artful Provocateur" else "",
        ),
    ).opening(Overlay.NEW_DECK)

    private fun shell(
        start: AppState,
        onCommanderTyped: (Completion) -> Unit = {},
        onPickFile: () -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onCommanderTyped = onCommanderTyped,
                        onPickFile = onPickFile,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theCommanderBoxReachesTheShell() {
        val typed = mutableListOf<String>()
        shell(opened(DeckStep.COMMANDER), onCommanderTyped = { c -> typed += c.term })

        rule.onNodeWithText("e.g. Alela, Artful Provocateur").performTextInput("Alela")
        rule.waitForIdle()

        assertTrue(typed.isNotEmpty(), "the commander box was typed into and the shell heard nothing")
        assertEquals("Alela", typed.last())
    }

    @Test
    fun uploadAFileReachesTheShell() {
        var picked = 0
        shell(opened(DeckStep.CARDS), onPickFile = { picked++ })

        rule.onNodeWithText("Upload a file").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(1, picked, "Upload a file was pressed and the shell heard nothing")
    }
}
