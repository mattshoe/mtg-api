package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The story of Phyrexia on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the story must not run
 * the full width, where a line is too long to read, and the tabs must
 * sit on the screen. The website's `LoreOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class LoreOnATabletTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun theStoryReads(size: DpSize) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse("#/guide/phyrexia")))
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

        val story = rule.onNodeWithTag("lore-story", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val width = story.right - story.left
        assertTrue(width >= 320.dp, "$on the story is squeezed to $width")
        assertTrue(width <= 720.dp, "$on the story runs $width wide, too long a line to read")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val left = story.left - page.left
        val right = page.right - story.right
        assertTrue((left - right).value in -4f..4f, "$on the story is not centred: $left on the left, $right on the right")

        val tabs = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)
        val first = tabs[0].getUnclippedBoundsInRoot()
        assertTrue(first.left.value >= 0f, "$on the first tab is off the left edge")
        val row = rule.onNodeWithTag("lore-tabs", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(row.right <= size.width, "$on the tab row runs off the side: ${row.right}")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theStoryReadsOnAFoldingPhoneOpenedOut() = theStoryReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theStoryReadsOnATabletHeldSideways() = theStoryReads(Tablet.LANDSCAPE)
}
