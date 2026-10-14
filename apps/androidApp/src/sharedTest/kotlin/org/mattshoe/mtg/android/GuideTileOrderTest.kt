package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private fun tilesInOrder(on: String, sized: @Composable (@Composable () -> Unit) -> Unit) {
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
        val tops = order.map { slug ->
            rule.onNodeWithTag("guide-tile-$slug").assertExists("$on the Guide has no $slug tile")
            rule.onNodeWithTag("guide-tile-$slug").getUnclippedBoundsInRoot().top
        }
        order.zip(tops).zipWithNext().forEach { (a, b) ->
            assertTrue(b.second > a.second, "$on the ${b.first} tile is drawn at ${b.second}, not under the ${a.first} tile at ${a.second}")
        }
    }

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
