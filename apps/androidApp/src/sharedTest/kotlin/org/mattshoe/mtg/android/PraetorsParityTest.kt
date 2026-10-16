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
 * Matt: "Please add the details of the 5 phyrexian praetors and their
 * respective colors. in the elesh norn section, add her work with
 * Atraxa"
 *
 * Through the real shell: the Guide, Lore, the New Phyrexia tile, then
 * its Praetors tab, on a phone and on a tablet. Sibling of
 * `PraetorsPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class PraetorsParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val five = listOf("Five Suns", "Elesh Norn", "Jin-Gitaxias", "Sheoldred", "Urabrask", "Vorinclex", "Mother of Machines")

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

    private fun thePraetorsAreTold(on: String) {
        val parts = texts("story-part-title")
        val at = parts.indexOf("FIVE SUNS")
        assertEquals(five.map { it.uppercase() }, parts.drop(at).take(7), "$on the five praetors are not parts of their own after Five Suns: $parts")
        val story = texts("lore-paragraph").joinToString(" ")
        listOf("Machine Orthodoxy", "Progress Engine", "Steel Thanes", "Quiet Furnace", "Vicious Swarm").forEach {
            assertTrue(it in story, "$on the story never names the $it")
        }
        assertTrue("Atraxa, Grand Unifier" in story, "$on Elesh Norn's part does not tell of Atraxa, Grand Unifier")
    }

    @Test
    fun thePraetorsArticleTellsEachPraetorOnAPhone() {
        shell()
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-lore").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-new-phyrexia").performScrollTo().performClick()
        rule.waitForIdle()
        val tabs = texts("lore-tab")
        val at = tabs.indexOf("Praetors")
        assertTrue(at >= 0, "New Phyrexia has no Praetors tab: $tabs")
        rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)[at].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/new-phyrexia/praetors", held.value.route.toHash(), "the tab is not in the address")
        thePraetorsAreTold("on a phone")
    }

    private fun onATablet(size: DpSize) {
        shell(size, "#/guide/new-phyrexia/praetors")
        val on = "on a ${size.width} by ${size.height} screen"
        thePraetorsAreTold(on)
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
