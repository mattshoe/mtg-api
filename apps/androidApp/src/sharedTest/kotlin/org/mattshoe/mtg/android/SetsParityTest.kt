package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.MagicStory
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "i want to see Guide section on all the modern sets from that
 * few years. it should be similar to the root Lore section ... each set
 * gets is own page ... with logical tabs".
 *
 * Through the real shell: the Guide, the Sets tile after Lore, every
 * set's tile, then Bloomburrow's page and its tabs, and the way back
 * out, on a phone, a folding phone opened out and a tablet held
 * sideways. Sibling of `SetsPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class SetsParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val blb get() = MagicStory.sets.first { it.slug == "blb" }

    private fun shell(size: DpSize?) {
        rule.setContent {
            val app: @Composable () -> Unit = {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AppState()) }
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
            if (size == null) app() else Tablet.Sized(size) { app() }
        }
        rule.waitForIdle()
    }

    private fun tiles(): List<String> = rule.onAllNodes(
        SemanticsMatcher("a Guide tile") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("guide-tile-") == true },
    ).fetchSemanticsNodes()
        .sortedBy { it.positionInRoot.y }
        .map { it.config[SemanticsProperties.TestTag].removePrefix("guide-tile-") }

    private fun back(): String = rule.onNodeWithTag("guide-back").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun selected(): List<Boolean> = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Selected) ?: false
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun everySetHasAPage(size: DpSize?) {
        shell(size)
        val on = size?.let { "on a ${it.width} by ${it.height} screen" } ?: "on a phone"
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        val front = tiles()
        assertTrue(Guide.SETS in front, "$on the Guide has no Sets tile: $front")
        assertEquals(listOf(Guide.LORE, Guide.SETS), front.takeLast(2), "$on Sets is not the tile after Lore: $front")

        click("guide-tile-${Guide.SETS}")
        assertEquals("#/guide/sets", held.value.route.toHash())
        assertEquals(MagicStory.sets.map { it.slug }, tiles(), "$on Sets does not hold every set, oldest first")
        assertTrue("Guide" in back(), "$on Sets' way back does not say Guide: ${back()}")
        val width = size?.width ?: Float.MAX_VALUE.dp
        Guide.sets.forEach { t ->
            val box = rule.onNodeWithTag("guide-tile-${t.slug}").getUnclippedBoundsInRoot()
            assertTrue(box.right <= width, "$on ${t.title} runs off the side: ${box.right}")
            assertTrue(box.right - box.left >= 200.dp, "$on ${t.title} is squeezed to ${box.right - box.left}")
        }

        click("guide-tile-blb")
        assertEquals("#/guide/blb", held.value.route.toHash())
        assertEquals(blb.articles.map { it.title }, texts("lore-tab"), "$on Bloomburrow's tabs are not its articles")
        assertEquals(listOf(true) + List(blb.articles.size - 1) { false }, selected(), "$on Bloomburrow does not open on its first tab")
        assertEquals(blb.articles.first().tabs.map { it.tab.uppercase() }, texts("story-part-title"), "$on the first tab does not tell its parts")
        assertTrue("Sets" in back(), "$on a set's way back does not say Sets: ${back()}")

        val last = blb.articles.last()
        rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)[blb.articles.size - 1].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/blb/${last.slug}", held.value.route.toHash(), "$on the tab is not in the address")
        assertEquals(last.tabs.map { it.tab.uppercase() }, texts("story-part-title"), "$on ${last.title} does not tell its parts")
        if (size != null) {
            val box = rule.onNodeWithTag("lore-story", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(box.right - box.left in 320.dp..720.dp, "$on the set's page is ${box.right - box.left} wide")
            val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
            assertTrue(((box.left - page.left) - (page.right - box.right)).value in -4f..4f, "$on the set's page is not centred")
        }

        click("guide-back")
        assertEquals("#/guide/sets", held.value.route.toHash(), "$on back from a set skipped Sets")
        click("guide-back")
        assertEquals("#/guide", held.value.route.toHash(), "$on back from Sets is not the Guide")
    }

    @Test
    fun everySetHasAPageOnAPhone() = everySetHasAPage(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun everySetHasAPageOnAFoldingPhoneOpenedOut() = everySetHasAPage(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun everySetHasAPageOnATabletHeldSideways() = everySetHasAPage(Tablet.LANDSCAPE)
}
