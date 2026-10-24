package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.Change
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one receipt the entry wizard still stops on, and its way out.
 *
 * Matt: "when a 'entry' flow of any kind finishes, DO NOT fucking ask
 * for more". A write that lands leaves on its own — see
 * `EntryFlowsLeaveWhenDoneTest`. A write the server dropped cards
 * from stays, because the receipt is the only place that names them,
 * and its button is Done, which goes home, not "Enter more".
 *
 * Through the real `AppShell`, on a phone and on both tablet sizes.
 * Sibling of the web's `cardsTheServerDroppedKeepTheReceiptAndDoneLeavesIt`.
 */
@RunWith(AndroidJUnit4::class)
class EntryReceiptLeavesTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: MutableState<AppState>

    /** A write that came back with one card the server could not resolve. */
    private fun aReceiptWithADroppedCard(): AppState {
        val s = AppState(admin = Admin().signIn(Account(key = "matt", role = "admin"), "t")).navigate(View.ENTRY)
        val sol = Change("Sol Ring", "M3C", "409", "nonfoil", 0, 4)
        val entry = MassEntry(direction = Direction.ADD).type("4 Sol Ring\n1 Sol Rong")
            .previewed(Applied(dryRun = true, changes = listOf(sol)))
        val left = s.copy(entry = entry).entryApplied(
            Applied(applied = true, failed = 1, changes = listOf(sol), errors = listOf("Sol Rong: no such card")),
            now = "2026-10-24T17:00:00Z",
        )
        assertEquals(View.ENTRY, left.view, "the fixture left the wizard, so there is no receipt to test")
        return left
    }

    private fun shell(size: DpSize?) {
        val start = aReceiptWithADroppedCard()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = remember { mutableStateOf(start) }
                    val app = @androidx.compose.runtime.Composable {
                        AppShell(
                            state = held.value,
                            onState = { held.value = it },
                            onSearch = {},
                            onOpenDeck = {},
                            onPreviewEntry = {},
                            onApplyEntry = {},
                        )
                    }
                    if (size == null) app() else Tablet.Sized(size) { app() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun says(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun doneGoesHome(size: DpSize?) {
        shell(size)
        assertTrue(says("Sol Rong: no such card"), "the receipt does not name the dropped card")
        assertTrue(!says("Enter more"), "the receipt is still asking for more")

        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(View.LIBRARY, held.value.view, "Done did not leave the wizard")
        assertEquals(MassEntry(), held.value.entry, "the finished run is still in the wizard for next time")
        assertTrue(!says("Sol Rong: no such card"), "the receipt is still on screen")
    }

    @Test
    fun doneOnTheReceiptGoesHome() = doneGoesHome(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun doneOnTheReceiptGoesHomeOnAFoldingPhoneOpenedOut() = doneGoesHome(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun doneOnTheReceiptGoesHomeOnATabletHeldSideways() = doneGoesHome(Tablet.LANDSCAPE)
}
