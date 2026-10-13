package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.hypot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colour wheel page on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The wheel must stay a circle, sit whole on
 * the screen with room to spare, and the colours under it use the
 * width rather than running down one long column. The phone's
 * `WheelOnATabletTest` is the same claim.
 */
class WheelOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun theWheelFits(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/wheel"))) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        val on = "on a ${size.first} by ${size.second} screen"

        val spokes = all(".wheel-spoke").map { it.getBoundingClientRect() }
        assertEquals(5, spokes.size, "$on the wheel has ${spokes.size} colours")
        val xs = spokes.map { it.left + it.width / 2 }
        val ys = spokes.map { it.top + it.height / 2 }
        val cx = xs.average()
        val cy = ys.average()
        val radii = xs.indices.map { hypot(xs[it] - cx, ys[it] - cy) }
        assertTrue(radii.max() - radii.min() < 3.0, "$on the colours are not on one circle: $radii")
        assertTrue(radii.min() > 60.0, "$on the wheel is shrunk to a stamp: radius ${radii.min()}")

        val wheel = all(".wheel").single().getBoundingClientRect()
        assertTrue(wheel.left >= 0 && wheel.right <= size.first, "$on the wheel runs off the side: ${wheel.left} to ${wheel.right}")
        assertTrue(
            wheel.height <= size.second / 2.0,
            "$on the wheel is ${wheel.height}px tall, more than half the screen, so nothing else fits beside it",
        )
        assertTrue(
            kotlin.math.abs((wheel.left + wheel.right) / 2 - cx) < 3.0,
            "$on the colours are not centred in the wheel",
        )

        val colours = all(".wheel-colour").map { it.getBoundingClientRect() }
        assertEquals(5, colours.size, "$on the five colours are not all there")
        assertEquals(colours[0].top, colours[1].top, "$on blue is under white, so the page is one long column on a wide screen")
        colours.forEach { assertTrue(it.right <= size.first, "$on a colour runs off the side: ${it.right}") }
    }

    @Test
    fun theWheelFitsOnAFoldingPhoneOpenedOut() = runTest { theWheelFits(Tablet.UNFOLDED) }

    @Test
    fun theWheelFitsOnATabletHeldSideways() = runTest { theWheelFits(Tablet.LANDSCAPE) }
}
