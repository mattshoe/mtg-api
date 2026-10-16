package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "please add the background lore for the Wilds of eldraine into
 * the Omenppaths lore section."
 *
 * Through the real shell: the Guide, Lore, the Omenpath Era tile, then
 * its Wilds of Eldraine tab, on a phone and on a tablet. Sibling of
 * `WildsOfEldrainePageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class WildsOfEldraineParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val parts = listOf("The Wicked Slumber", "Talion's Bargain", "Agatha's Cauldron", "The Frozen Heart", "The Ardent Queen", "Broken Oaths")

    private fun shell(size: DpSize? = null, hash: String? = null) {
        rule.setContent {
            val body = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(hash?.let { AppState().navigate(Route.parse(it)) } ?: AppState())
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
            if (size != null) Tablet.Sized(size) { body() } else body()
        }
        rule.waitForIdle()
    }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun selected(): List<Boolean> = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Selected) ?: false
    }

    private fun theArticleIsTold(on: String) {
        assertEquals(texts("lore-tab").map { it == "Wilds of Eldraine" }, selected(), "$on Wilds of Eldraine is not the one open tab: ${texts("lore-tab")}")
        assertEquals(parts.map { it.uppercase() }, texts("story-part-title"), "$on the article does not tell its parts")
        val story = texts("lore-paragraph").joinToString(" ")
        assertTrue("Wicked Slumber" in story && "Kellan" in story, "$on the story does not tell of the Wicked Slumber and Kellan: ${story.take(200)}")
    }

    @Test
    fun theOmenpathEraHasAWildsOfEldraineTabAfterTheOmenpaths() {
        shell()
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-lore").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-omenpaths").performScrollTo().performClick()
        rule.waitForIdle()
        val tabs = texts("lore-tab")
        val at = tabs.indexOf("Wilds of Eldraine")
        assertTrue(at > 0, "the Omenpath Era has no Wilds of Eldraine tab: $tabs")
        assertEquals("The Omenpaths", tabs[at - 1], "Wilds of Eldraine does not follow The Omenpaths: $tabs")
        assertEquals("Thunder Junction", tabs[at + 1], "Wilds of Eldraine does not come before Thunder Junction: $tabs")
        rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)[at].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/omenpaths/wilds-of-eldraine", held.value.route.toHash(), "the tab is not in the address")
        theArticleIsTold("on a phone")
    }

    private fun onATablet(size: DpSize) {
        shell(size, "#/guide/omenpaths/wilds-of-eldraine")
        val on = "on a ${size.width} by ${size.height} screen"
        theArticleIsTold(on)
        val box = rule.onNodeWithTag("lore-story", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val width = box.right - box.left
        assertTrue(width in 320.dp..720.dp, "$on the story is $width wide")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        assertTrue(((box.left - page.left) - (page.right - box.right)).value in -4f..4f, "$on the story is not centred")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun itReadsOnAFoldingPhoneOpenedOut() = onATablet(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun itReadsOnATabletHeldSideways() = onATablet(Tablet.LANDSCAPE)
}
