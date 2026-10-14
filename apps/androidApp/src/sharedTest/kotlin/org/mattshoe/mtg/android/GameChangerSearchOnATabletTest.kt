package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the Game Changers on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The search bar sits over the list, as wide
 * as it and no wider, and what it found is still centred. The
 * website's `GameChangerSearchOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class GameChangerSearchOnATabletTest {

    @get:Rule
    val rule = createComposeRule()

    private fun searchReads(size: DpSize) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.GAME_CHANGERS}")))
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
        }
        rule.waitForIdle()
        val on = "on a ${size.width} by ${size.height} screen"
        rule.onNodeWithTag("gc-search").assertExists("$on the Game Changers page has no search bar")
        rule.onNodeWithTag("gc-search").performTextInput("tutor")
        rule.waitForIdle()

        assertEquals(5, rule.onAllNodes(hasTestTag("gc-row")).fetchSemanticsNodes().size, "$on searching tutor did not leave the five tutors")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("gc-list").getUnclippedBoundsInRoot()
        val width = list.right - list.left
        assertTrue(width >= 320.dp && width <= 720.dp, "$on the list is $width wide")
        val left = list.left - page.left
        val right = page.right - list.right
        assertTrue((left - right).value in -4f..4f, "$on the list is not centred: $left on the left, $right on the right")
        assertTrue(list.right <= size.width, "$on the list runs off the side: ${list.right}")

        val bar = rule.onNodeWithTag("gc-search").getUnclippedBoundsInRoot()
        assertTrue(
            (bar.left - list.left).value in -4f..4f && (bar.right - list.right).value in -4f..4f,
            "$on the search bar is not over the list: ${bar.left}..${bar.right} against ${list.left}..${list.right}",
        )
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun searchReadsOnAFoldingPhoneOpenedOut() = searchReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun searchReadsOnATabletHeldSideways() = searchReads(Tablet.LANDSCAPE)
}
