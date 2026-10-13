package org.mattshoe.mtg.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Facet
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What keyboard the power and toughness boxes raise (4.9).
 *
 * `KeyboardType.Number` is digits, a dot and a minus. Power and
 * toughness are not numbers — `:core` says so in as many words where
 * it builds the clause: "Power and toughness are text: '*', '1+*',
 * '3'". Tarmogoyf's power is `*` and Mistcutter Hydra's is `1+*`, and
 * on a digits-only keypad there is no key that types either one. The
 * website's box is a plain text input and has never had the problem.
 *
 * `KeyboardType.Phone` is the fix: a keypad, with `*` and `#` on it.
 *
 * Read back through the host view's `EditorInfo` rather than through
 * semantics, because semantics does not carry it — a Compose text
 * field publishes `ImeAction` and `IsEditable` and nothing about the
 * keyboard class. `EditorInfo` is what the platform actually fills in
 * for the focused field and hands to the IME, so it is the resolved
 * answer and not a restatement of the source.
 *
 * Through the real `AppShell`, in the Library's own filter panel.
 * Mounting `FilterSheet` alone is how the panel's facet lists stayed
 * empty on the phone for a release: the test fed it a hand-built
 * `Facets` the shell never passed.
 */
@RunWith(AndroidJUnit4::class)
class StatKeyboardParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The real shell, with the Mana & stats group opened. */
    private fun shellWithStatsOpen() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(AppState())
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("header-${Facet.MANA.id}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    /**
     * The keyboard the platform would raise for whatever has focus.
     *
     * `onCreateInputConnection` is the same call the system makes when
     * it is about to show the IME, so this is the resolved
     * `KeyboardType` and not the one the source asked for.
     */
    private fun focusedKeyboardClass(): Int {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val info = EditorInfo()
        val connection = view.onCreateInputConnection(info)
        assertTrue(connection != null, "nothing has focus, so there is no keyboard to ask about")
        return info.inputType and InputType.TYPE_MASK_CLASS
    }

    private fun assertPhoneKeypad(tag: String, what: String) {
        shellWithStatsOpen()
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
        val cls = focusedKeyboardClass()
        assertEquals(
            InputType.TYPE_CLASS_PHONE,
            cls,
            "$what raises input class $cls, and a keypad with no `*` on it " +
                "cannot type Tarmogoyf's power",
        )
    }

    @Test
    fun thePowerBoxRaisesAKeypadThatHasAStarOnIt() = assertPhoneKeypad("box-pow", "Power")

    @Test
    fun soDoesToughness() = assertPhoneKeypad("box-tou", "Toughness")

    /** Loyalty is the third box built from the same `Stat`, and `X` is a real one. */
    @Test
    fun andLoyalty() = assertPhoneKeypad("box-loy", "Loyalty")

    /**
     * Mana value stays a number, because it is one.
     *
     * Here so "use a phone keypad" does not quietly become the answer
     * for every box in the panel: `cmc` is a real number in the
     * database and `Range` compares it as one.
     */
    @Test
    fun theManaValueBoxesStayOnADigitsKeyboard() {
        shellWithStatsOpen()
        rule.onNodeWithTag("min-cmc").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(
            InputType.TYPE_CLASS_NUMBER,
            focusedKeyboardClass(),
            "mana value is a number and should still get the digits keyboard",
        )
    }

    /**
     * And the box takes a `*` once there is a key for it.
     *
     * Green before this change as well as after — `performTextInput`
     * goes in under the IME, so it was never the keyboard that
     * stopped it. It is here for the other half of the failure: a
     * controlled binding that drops anything it cannot parse, which
     * is what the quantity box next door does on purpose and what
     * this box must not do.
     */
    @Test
    fun theStarSurvivesBeingTypedIntoThePowerBox() {
        shellWithStatsOpen()
        rule.onNodeWithTag("box-pow").performScrollTo().performTextInput("*")
        rule.waitForIdle()
        rule.onNodeWithTag("box-pow").assertTextContains("*")
    }
}
