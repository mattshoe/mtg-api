package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Game Changers on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the list must not run the
 * full width, where a name sits a long way from its picture, nothing
 * may run off the side, and a tapped card's carousel must fit the
 * screen. The phone's `GameChangersOnATabletTest` is the same claim.
 */
class GameChangersOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun listReads(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.GAME_CHANGERS}")))
        renderComposable(root = s.body) {
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
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val page = all(".guide").single().getBoundingClientRect()
        val list = all(".gc-list").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no Game Changers list")
        assertTrue(list.width >= 320.0, "$on the list is squeezed to ${list.width}px")
        assertTrue(list.width <= 720.0, "$on the list runs ${list.width}px wide")
        assertTrue(
            abs((list.left - page.left) - (page.right - list.right)) < 4.0,
            "$on the list is not centred: ${list.left - page.left}px on the left, ${page.right - list.right}px on the right",
        )
        val rows = all(".gc-list .deck-line")
        assertEquals(53, rows.size, "$on the page does not list the 53 game changers")

        rows.first { it.textContent.orEmpty().contains("Cyclonic Rift") }.click()
        settle(s.window)
        val sheet = all(".peek-sheet").singleOrNull()?.getBoundingClientRect() ?: error("$on tapping Cyclonic Rift opened no carousel")
        assertEquals("Cyclonic Rift", all(".peek-name").single().textContent)
        assertTrue(sheet.bottom <= size.second + 0.5, "$on the carousel's sheet runs ${sheet.bottom - size.second}px off the bottom")
        val shown = all(".peek-card").map { it.firstElementChild!!.getBoundingClientRect() }
            .filter { it.left >= -0.5 && it.right <= size.first + 0.5 }
        assertTrue(shown.isNotEmpty(), "$on no card of the carousel is on screen")
        shown.forEach { face ->
            assertTrue(face.bottom <= sheet.top + 0.5, "$on the card runs ${face.bottom - sheet.top}px behind the sheet")
            assertTrue(face.top >= -0.5, "$on the card runs ${-face.top}px off the top")
        }
    }

    @Test
    fun theListReadsOnAFoldingPhoneOpenedOut() = runTest { listReads(Tablet.UNFOLDED) }

    @Test
    fun theListReadsOnATabletHeldSideways() = runTest { listReads(Tablet.LANDSCAPE) }
}
