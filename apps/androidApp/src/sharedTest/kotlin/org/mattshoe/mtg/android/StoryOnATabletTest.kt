package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
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
 * The story of Magic on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the sections and the
 * article must not run the full width, where a line is too long to
 * read, and the tabs must sit on the screen. The website's
 * `StoryOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class StoryOnATabletTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(size: DpSize, hash: String) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse(hash)))
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
    }

    private fun readable(on: String, what: String, box: DpRect) {
        val width = box.right - box.left
        assertTrue(width >= 320.dp, "$on $what is squeezed to $width")
        assertTrue(width <= 720.dp, "$on $what runs $width wide, too long a line to read")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val left = box.left - page.left
        val right = page.right - box.right
        assertTrue((left - right).value in -4f..4f, "$on $what is not centred: $left on the left, $right on the right")
    }

    private fun anArticleReads(size: DpSize) {
        open(size, "#/guide/lore/the-war")
        val on = "on a ${size.width} by ${size.height} screen"
        readable(on, "the story", rule.onNodeWithTag("lore-story", useUnmergedTree = true).getUnclippedBoundsInRoot())
        val tabs = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)
        assertTrue(tabs.fetchSemanticsNodes().size >= 2, "$on The War has fewer than two tabs")
        assertTrue(tabs[0].getUnclippedBoundsInRoot().left.value >= 0f, "$on the first tab is off the left edge")
        val row = rule.onNodeWithTag("lore-tabs", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(row.right <= size.width, "$on the tab row runs off the side: ${row.right}")
    }

    private fun theSectionsRead(size: DpSize) {
        open(size, "#/guide/lore")
        val on = "on a ${size.width} by ${size.height} screen"
        readable(on, "the sections", rule.onNodeWithTag("story-index", useUnmergedTree = true).getUnclippedBoundsInRoot())
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun anArticleReadsOnAFoldingPhoneOpenedOut() = anArticleReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun anArticleReadsOnATabletHeldSideways() = anArticleReads(Tablet.LANDSCAPE)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theSectionsReadOnAFoldingPhoneOpenedOut() = theSectionsRead(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theSectionsReadOnATabletHeldSideways() = theSectionsRead(Tablet.LANDSCAPE)
}
