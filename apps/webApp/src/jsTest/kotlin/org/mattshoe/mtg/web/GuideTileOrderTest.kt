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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The order of the Guide's tiles, as a person reads them off the page.
 *
 * Matt: "reorder the Guide sections such that they stay in order of:
 * rules, game changers, color combinations, color wheel". The tiles
 * are a grid on a wide screen, so the order is reading order, row by
 * row and left to right, measured, on a phone and on a tablet both
 * ways. The phone's `GuideTileOrderTest` is the same claim.
 */
class GuideTileOrderTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val order = listOf("Rules", "Game Changers", "Colour combinations", "The colour wheel")

    private suspend fun tilesInOrder(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse("#/guide"))) }
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
        val tiles = doc.querySelectorAll("button.guide-tile").let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        val on = "on a ${size.first} by ${size.second} screen"
        val boxes = order.map { title ->
            val tile = tiles.firstOrNull { it.querySelector(".guide-tile-title")?.textContent?.trim() == title }
                ?: error("$on the Guide has no $title tile: ${tiles.map { it.querySelector(".guide-tile-title")?.textContent }}")
            title to tile.getBoundingClientRect()
        }
        boxes.zipWithNext().forEach { (a, b) ->
            val sameRow = abs(a.second.top - b.second.top) < 2.0
            assertTrue(
                b.second.top > a.second.top + 2.0 || (sameRow && b.second.left > a.second.left),
                "$on the ${b.first} tile, at ${b.second.left},${b.second.top}, does not come after the ${a.first} tile at ${a.second.left},${a.second.top}",
            )
        }
    }

    @Test
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnAPhone() = runTest { tilesInOrder(400 to 800) }

    @Test
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnAFoldingPhoneOpenedOut() = runTest { tilesInOrder(Tablet.UNFOLDED) }

    @Test
    fun theTilesGoRulesGameChangersColoursThenTheWheelOnATabletHeldSideways() = runTest { tilesInOrder(Tablet.LANDSCAPE) }
}
