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
 * The Blind Eternities section, through the real shell and its real bottom bar.
 *
 * Matt: "i want a guide section about the blind eternities, and
 * generally the "topology" of the mtg multiverse/universe/whatever".
 * Sibling of `BlindEternitiesPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class BlindEternitiesParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

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
            if (size == null) body() else Tablet.Sized(size) { body() }
        }
        rule.waitForIdle()
    }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun openTheBlindEternities() {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        // Every story is inside Lore.
        rule.onNodeWithTag("guide-tile-lore").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-blind-eternities").assertExists("the Guide has no Blind Eternities tile")
        rule.onNodeWithTag("guide-tile-blind-eternities").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun theGuideHasABlindEternitiesTileThatOpensOnItsArticlesAsTabs() {
        shell()
        openTheBlindEternities()
        assertEquals("#/guide/blind-eternities", held.value.route.toHash())
        assertEquals(
            listOf("The Multiverse", "The Blind Eternities", "Crossing the Void", "Planes Made and Unmade"),
            texts("lore-tab"),
            "the tabs are not the section's articles",
        )
        val story = texts("lore-paragraph").joinToString(" ")
        assertTrue("plane" in story, "The Multiverse does not tell of planes: ${story.take(200)}")
    }

    @Test
    fun aTabOpensItsArticleAtItsOwnAddress() {
        shell()
        openTheBlindEternities()
        val i = texts("lore-tab").indexOf("Crossing the Void")
        assertTrue(i >= 0, "there is no Crossing the Void tab: ${texts("lore-tab")}")
        rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)[i].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/blind-eternities/crossing-the-void", held.value.route.toHash(), "the tab is not in the address")
        assertTrue("spark" in texts("lore-paragraph").joinToString(" "), "Crossing the Void does not tell of the spark")
    }

    private fun readsOn(size: DpSize) {
        shell(size, "#/guide/blind-eternities/the-blind-eternities")
        val on = "on a ${size.width} by ${size.height} screen"
        rule.onNodeWithTag("lore-story", useUnmergedTree = true).assertExists("$on there is no Blind Eternities story")
        val box = rule.onNodeWithTag("lore-story", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val width = box.right - box.left
        assertTrue(width >= 320.dp && width <= 720.dp, "$on the story is $width wide")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        assertTrue(((box.left - page.left) - (page.right - box.right)).value in -4f..4f, "$on the story is not centred")
        assertEquals(4, rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true).fetchSemanticsNodes().size, "$on the section does not have its four articles as tabs")
        val row = rule.onNodeWithTag("lore-tabs", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(row.right <= size.width, "$on the tab row runs off the side: ${row.right}")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun itReadsOnAFoldingPhoneOpenedOut() = readsOn(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun itReadsOnATabletHeldSideways() = readsOn(Tablet.LANDSCAPE)
}
