package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every story tile, in one Lore tile, through the real shell.
 *
 * Matt: "collapse all the Guide sections on lore (not the color stuff)
 * into a single top-level one called Lore or something. then just move
 * the current tiles into that one but keep them sorted in roughly
 * chronological order as much as possible". On a phone, a folding
 * phone opened out and a tablet held sideways. Sibling of
 * `GuideLorePageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class GuideLoreParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun shell(size: DpSize?) {
        rule.setContent {
            val app: @Composable () -> Unit = {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember {
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
            if (size == null) app() else Tablet.Sized(size) { app() }
        }
        rule.waitForIdle()
    }

    /** Every Guide tile on screen, by slug, top to bottom. */
    private fun tiles(): List<String> = rule.onAllNodes(
        SemanticsMatcher("a Guide tile") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("guide-tile-") == true },
    ).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .map { it.config[SemanticsProperties.TestTag].removePrefix("guide-tile-") }

    private fun back(): String = rule.onNodeWithTag("guide-back").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private fun loreHoldsTheStory(size: DpSize?) {
        shell(size)
        val on = size?.let { "on a ${it.width} by ${it.height} screen" } ?: "on a phone"
        assertEquals(
            listOf(Guide.COLOURS, Guide.WHEEL, Guide.RULES, Guide.GAME_CHANGERS, Guide.LORE),
            tiles(),
            "$on the Guide's front page still carries the story tiles one by one",
        )

        rule.onNodeWithTag("guide-tile-${Guide.LORE}").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertEquals(Guide.lore.map { it.slug }, tiles(), "$on Lore does not hold every story tile, oldest first")
        assertTrue("Guide" in back(), "$on Lore's way back does not say Guide: ${back()}")
        val width = size?.width ?: Float.MAX_VALUE.dp
        Guide.lore.forEach { t ->
            val box = rule.onNodeWithTag("guide-tile-${t.slug}").getUnclippedBoundsInRoot()
            assertTrue(box.right <= width, "$on ${t.title} runs off the side: ${box.right}")
            assertTrue(box.right - box.left >= 200.dp, "$on ${t.title} is squeezed to ${box.right - box.left}")
        }

        rule.onNodeWithTag("guide-tile-brothers-war").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/brothers-war", held.value.route.toHash())
        assertTrue("Lore" in back(), "$on a story's way back does not say Lore: ${back()}")
        rule.onNodeWithTag("guide-back").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore", held.value.route.toHash(), "$on back from a story skipped Lore")

        rule.onNodeWithTag("guide-back").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide", held.value.route.toHash(), "$on back from Lore is not the Guide")
    }

    @Test
    fun loreHoldsTheStoryOnAPhone() = loreHoldsTheStory(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun loreHoldsTheStoryOnAFoldingPhoneOpenedOut() = loreHoldsTheStory(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun loreHoldsTheStoryOnATabletHeldSideways() = loreHoldsTheStory(Tablet.LANDSCAPE)
}
