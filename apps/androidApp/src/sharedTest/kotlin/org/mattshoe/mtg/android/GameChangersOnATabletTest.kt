package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Game Changers on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the list must not run the
 * full width, nothing may run off the side, and a tapped card's
 * carousel must fit the screen. The website's
 * `GameChangersOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class GameChangersOnATabletTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun listReads(size: DpSize) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember {
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
        assertTrue(rule.onAllNodes(hasTestTag("gc-list")).fetchSemanticsNodes().isNotEmpty(), "$on there is no Game Changers list")

        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("gc-list").getUnclippedBoundsInRoot()
        val width = list.right - list.left
        assertTrue(width >= 320.dp, "$on the list is squeezed to $width")
        assertTrue(width <= 720.dp, "$on the list runs $width wide")
        val left = list.left - page.left
        val right = page.right - list.right
        assertTrue((left - right).value in -4f..4f, "$on the list is not centred: $left on the left, $right on the right")
        assertTrue(list.right <= size.width, "$on the list runs off the side: ${list.right}")
        assertEquals(53, rule.onAllNodes(hasTestTag("gc-row")).fetchSemanticsNodes().size, "$on the page does not list the 53")

        rule.onAllNodes(hasTestTag("gc-row") and hasText("Cyclonic Rift"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(PeekOf.GAME_CHANGERS, held.value.peek.of, "$on tapping Cyclonic Rift opened no carousel over the list")
        val sheet = rule.onNodeWithTag("carousel-sheet").getUnclippedBoundsInRoot()
        assertTrue(sheet.bottom <= size.height + 0.5.dp, "$on the carousel's sheet runs ${sheet.bottom - size.height} off the bottom")
        val pager = rule.onNodeWithTag("carousel-pager").getUnclippedBoundsInRoot()
        assertTrue(pager.top >= 0.dp, "$on the carousel's card runs ${-pager.top} off the top")
        assertTrue(pager.right <= size.width + 0.5.dp, "$on the carousel's card runs off the side")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theListReadsOnAFoldingPhoneOpenedOut() = listReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theListReadsOnATabletHeldSideways() = listReads(Tablet.LANDSCAPE)
}
