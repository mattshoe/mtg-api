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
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Searching the Guide on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the search bar and its
 * hits must not run the full width, where a line is too long to read,
 * and nothing may run off the side. The website's
 * `GuideSearchOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class GuideSearchOnATabletTest {

    @get:Rule
    val rule = createComposeRule()

    private fun searchReads(size: DpSize) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse("#/guide")))
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
        rule.onNodeWithTag("guide-search").assertExists("$on the Guide has no search bar")
        rule.onNodeWithTag("guide-search").performTextInput("Sylex")
        rule.waitForIdle()

        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val hits = rule.onNodeWithTag("guide-hits").getUnclippedBoundsInRoot()
        val width = hits.right - hits.left
        assertTrue(width >= 320.dp, "$on the hits are squeezed to $width")
        assertTrue(width <= 720.dp, "$on the hits run $width wide, too long a line to read")
        val left = hits.left - page.left
        val right = page.right - hits.right
        assertTrue((left - right).value in -4f..4f, "$on the hits are not centred: $left on the left, $right on the right")
        assertTrue(hits.right <= size.width, "$on the hits run off the side: ${hits.right}")

        val bar = rule.onNodeWithTag("guide-search").getUnclippedBoundsInRoot()
        assertTrue(
            (bar.left - hits.left).value in -4f..4f && (bar.right - hits.right).value in -4f..4f,
            "$on the search bar is not over the hits: ${bar.left}..${bar.right} against ${hits.left}..${hits.right}",
        )
        assertTrue(rule.onAllNodes(hasTestTag("guide-hit")).fetchSemanticsNodes().isNotEmpty(), "$on there are no hits")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun searchReadsOnAFoldingPhoneOpenedOut() = searchReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun searchReadsOnATabletHeldSideways() = searchReads(Tablet.LANDSCAPE)
}
