package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import kotlin.test.assertEquals

/**
 * "Reset everything" has to empty the card name box as well.
 *
 * The box is bound to `complete.term` and the search to `filters.q`,
 * and the reset only ever touched the second — so the results came
 * back unfiltered under a name that still looked applied. Mounted
 * through `AppShell`, because the bug was in the wiring above the
 * screen, not in the screen.
 */
@RunWith(AndroidJUnit4::class)
class ResetClearsNameBoxTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shell(): () -> AppState {
        var state = AppState()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = remember { mutableStateOf(AppState()) }
                    state = held.value
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    /** What the name box shows, read back out of the field itself. */
    private fun nameBox(): String =
        rule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun resetEverythingEmptiesTheNameBox() {
        val state = shell()
        rule.onNode(hasSetTextAction()).performTextInput("bolt")
        rule.waitForIdle()
        assertEquals("bolt", nameBox(), "the box did not take the name, so this proves nothing")

        rule.onNodeWithText("Reset everything").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("", state().library.filters.q, "reset left the name in the search")
        assertEquals("", nameBox(), "reset cleared the search and left the name in the box")
    }
}
