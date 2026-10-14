package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The order of the Guide's tiles, as they stand on the page.
 *
 * Matt: "reorder the Guide sections such that they stay in order of:
 * rules, game changers, color combinations, color wheel". Read off
 * where each tile is drawn, on a phone and on a tablet both ways.
 * The website's `GuideTileOrderTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class GuideTileOrderTest {

    @get:Rule
    val rule = createComposeRule()

    private val order = listOf("rules", "game-changers", "colours", "wheel")

    private fun guide(sized: @Composable (@Composable () -> Unit) -> Unit) {
        rule.setContent {
            sized {
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
    }

    private fun tilesInOrder(on: String, sized: @Composable (@Composable () -> Unit) -> Unit) {
        guide(sized)
        val tops = order.map { slug ->
            rule.onNodeWithTag("guide-tile-$slug").assertExists("$on the Guide has no $slug tile")
            rule.onNodeWithTag("guide-tile-$slug").getUnclippedBoundsInRoot().top
        }
        order.zip(tops).zipWithNext().forEach { (a, b) ->
            assertTrue(b.second > a.second, "$on the ${b.first} tile is drawn at ${b.second}, not under the ${a.first} tile at ${a.second}")
        }
    }

    /**
     * The wheel is fourth now, under the fold, so a person scrolls to
     * it. Its page has to open at its own top, back button and all,
     * not wherever the tiles were scrolled to.
     */
    private fun aTileFromFurtherDownOpensAtItsTop(on: String, sized: @Composable (@Composable () -> Unit) -> Unit) {
        guide(sized)
        rule.onNodeWithTag("guide-tile-wheel").performScrollTo().performClick()
        rule.waitForIdle()
        // Against the page it scrolls in, not the root: on a device a
        // forced tablet size is drawn scaled, and the root is not.
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val back = rule.onNodeWithTag("guide-back").getUnclippedBoundsInRoot()
        assertTrue(
            back.top >= page.top && back.bottom <= page.bottom,
            "$on the wheel opened scrolled down, with its back button at ${back.top}, off a page ${page.top}..${page.bottom}",
        )
    }

    @Test
    fun aTileFromFurtherDownOpensAtItsTopOnAPhone() =
        aTileFromFurtherDownOpensAtItsTop("on a phone") { it() }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aTileFromFurtherDownOpensAtItsTopOnAFoldingPhoneOpenedOut() =
        aTileFromFurtherDownOpensAtItsTop("on a folding phone opened out") { content -> Tablet.Sized(Tablet.UNFOLDED) { content() } }

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aTileFromFurtherDownOpensAtItsTopOnATabletHeldSideways() =
        aTileFromFurtherDownOpensAtItsTop("on a tablet held sideways") { content -> Tablet.Sized(Tablet.LANDSCAPE) { content() } }

    @Test
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnAPhone() =
        tilesInOrder("on a phone") { it() }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnAFoldingPhoneOpenedOut() =
        tilesInOrder("on a folding phone opened out") { content -> Tablet.Sized(Tablet.UNFOLDED) { content() } }

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnATabletHeldSideways() =
        tilesInOrder("on a tablet held sideways") { content -> Tablet.Sized(Tablet.LANDSCAPE) { content() } }
}
