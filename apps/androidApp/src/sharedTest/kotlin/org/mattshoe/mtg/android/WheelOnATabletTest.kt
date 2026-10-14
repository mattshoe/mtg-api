package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colour wheel page on a tablet.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The wheel must stay a circle, sit whole on
 * the screen with room to spare, and the colours under it use the
 * width rather than running down one long column. The website's
 * `WheelOnATabletTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class WheelOnATabletTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun theWheelFits(size: DpSize) {
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse("#/guide/wheel")))
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

        val spokes = "WUBRG".map { rule.onNodeWithTag("wheel-spoke-$it", useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val xs = spokes.map { ((it.left + it.right) / 2).value }
        val ys = spokes.map { ((it.top + it.bottom) / 2).value }
        val cx = xs.average()
        val cy = ys.average()
        val radii = xs.indices.map { hypot(xs[it] - cx, ys[it] - cy) }
        // Within 5% of the radius: a tablet forced onto the emulator's screen
        // runs below one pixel per dp, so each symbol lands on the nearest
        // whole pixel and the radii spread by about 3dp in 100.
        assertTrue(radii.max() - radii.min() < radii.max() * 0.05, "$on the colours are not on one circle: $radii")
        assertTrue(radii.min() > 60.0, "$on the wheel is shrunk to a stamp: radius ${radii.min()}")

        val wheel = rule.onNodeWithTag("wheel", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            wheel.left.value >= 0f && wheel.right <= size.width,
            "$on the wheel runs off the side: ${wheel.left} to ${wheel.right}",
        )
        assertTrue(
            wheel.bottom - wheel.top <= size.height / 2,
            "$on the wheel is ${wheel.bottom - wheel.top} tall, more than half the screen",
        )
        assertTrue(abs((wheel.left + wheel.right).value / 2 - cx) < radii.max() * 0.05, "$on the colours are not centred in the wheel")

        val w = rule.onNodeWithTag("wheel-colour-W", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val u = rule.onNodeWithTag("wheel-colour-U", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(w.top, u.top, "$on blue is under white, so the page is one long column on a wide screen")
        assertTrue(u.right <= size.width, "$on blue runs off the side: ${u.right}")

        // The lore of the shards and clans, under the pairs: two across
        // on a wide screen, as the five colours are, and on the screen.
        val bant = rule.onNodeWithTag("wheel-lore-GWU", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val esper = rule.onNodeWithTag("wheel-lore-WUB", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(bant.top, esper.top, "$on Esper is under Bant, so the lore is one long column on a wide screen")
        assertTrue(bant.left.value >= 0f && esper.right <= size.width, "$on the lore runs off the side: ${bant.left} to ${esper.right}")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theWheelFitsOnAFoldingPhoneOpenedOut() = theWheelFits(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theWheelFitsOnATabletHeldSideways() = theWheelFits(Tablet.LANDSCAPE)
}
