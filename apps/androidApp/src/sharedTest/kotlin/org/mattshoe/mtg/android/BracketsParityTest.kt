package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import org.mattshoe.mtg.core.Brackets
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's Commander brackets, through the real shell, on a phone
 * and on a tablet both ways.
 *
 * Matt: "each bracket section should have its own tile or section or
 * card or something, and each should be collapsed by default so the
 * use has to open the bracket they're concerned with ... sort them in
 * ascending order". Sibling of `BracketsPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class BracketsParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun shell(start: AppState, sized: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        rule.setContent {
            sized {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                        AppShell(state = held.value, onState = { held.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rules(n: Int): List<String> =
        rule.onAllNodes(hasTestTag("bracket-rule-$n")).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        }

    private fun card(n: Int) = rule.onNodeWithTag("bracket-card-$n").getUnclippedBoundsInRoot()

    /** Every card one to five, each below the last, and every one of them shut. */
    private fun everyBracketShutInOrder(on: String) {
        val drawn = (1..5).filter { rule.onAllNodes(hasTestTag("bracket-card-$it")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(1, 2, 3, 4, 5), drawn, "$on the brackets are not one to five")
        (1..5).zipWithNext().forEach { (a, b) ->
            assertTrue(card(b).top >= card(a).bottom, "$on bracket $b at ${card(b).top} is not below bracket $a at ${card(a).bottom}")
        }
        val open = (1..5).filter { rules(it).isNotEmpty() }
        assertTrue(open.isEmpty(), "$on these brackets start open: $open")
        (1..5).forEach { assertTrue(card(it).bottom - card(it).top < 120.dp, "$on bracket $it is ${card(it).bottom - card(it).top} tall shut") }
    }

    @Test
    fun theGuideOpensTheBracketsShutInOrderAndOneOpensAlone() {
        shell(AppState())
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        val rules = rule.onNodeWithTag("guide-tile-${Guide.RULES}").getUnclippedBoundsInRoot()
        val brackets = rule.onNodeWithTag("guide-tile-${Guide.BRACKETS}").getUnclippedBoundsInRoot()
        val gc = rule.onNodeWithTag("guide-tile-${Guide.GAME_CHANGERS}").getUnclippedBoundsInRoot()
        assertTrue(brackets.top > rules.top && brackets.top < gc.top, "the Brackets tile is not between Rules and Game Changers")
        rule.onNodeWithTag("guide-tile-${Guide.BRACKETS}").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Guide.BRACKETS, held.value.route.rest, "the tile did not open its own address")
        everyBracketShutInOrder("on a phone")

        val fourBefore = card(4).top
        rule.onNodeWithTag("bracket-head-3").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(
            Brackets.all[2].rules.map { "${it.allowed.mark} ${it.topic}: ${it.short}" },
            rules(3),
            "tapping bracket 3 did not open its rules",
        )
        assertTrue(rules(3).first().endsWith("Up to three"), "bracket 3 does not say up to three Game Changers")
        assertTrue(card(4).top > fourBefore + 100.dp, "bracket 3 opened without pushing bracket 4 down: $fourBefore to ${card(4).top}")
        val others = listOf(1, 2, 4, 5).filter { rules(it).isNotEmpty() }
        assertTrue(others.isEmpty(), "opening bracket 3 opened others too: $others")

        rule.onNodeWithTag("bracket-head-3").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(rules(3).isEmpty(), "tapping bracket 3 again did not shut it")
    }

    private fun cardsRead(size: DpSize) {
        shell(AppState().navigate(Route.parse("#/guide/${Guide.BRACKETS}"))) { content -> Tablet.Sized(size) { content() } }
        val on = "on a ${size.width} by ${size.height} screen"
        assertTrue(rule.onAllNodes(hasTestTag("bracket-list")).fetchSemanticsNodes().isNotEmpty(), "$on there is no brackets list")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("bracket-list").getUnclippedBoundsInRoot()
        val width = list.right - list.left
        assertTrue(width >= 320.dp, "$on the list is squeezed to $width")
        assertTrue(width <= 720.dp, "$on the list runs $width wide")
        val left = list.left - page.left
        val right = page.right - list.right
        assertTrue((left - right).value in -4f..4f, "$on the list is not centred: $left on the left, $right on the right")
        everyBracketShutInOrder(on)

        rule.onNodeWithTag("bracket-head-5").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Brackets.all[4].rules.size, rules(5).size, "$on bracket 5 did not open")
        val five = card(5)
        assertTrue(five.right <= list.right + 0.5.dp && five.left >= list.left - 0.5.dp, "$on bracket 5 opened wider than the list")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theBracketsReadOnAFoldingPhoneOpenedOut() = cardsRead(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theBracketsReadOnATabletHeldSideways() = cardsRead(Tablet.LANDSCAPE)
}
