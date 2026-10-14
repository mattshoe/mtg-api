package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Searching the Guide on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the search bar and its
 * hits must not run the full width, where a line is too long to read,
 * and nothing may run off the side. The phone's
 * `GuideSearchOnATabletTest` is the same claim.
 */
class GuideSearchOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun searchReads(size: Pair<Int, Int>) {
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
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"
        val box = all("input.guide-search").singleOrNull()?.unsafeCast<HTMLInputElement>() ?: error("$on the Guide has no search bar")
        box.value = "Sylex"
        box.dispatchEvent(Event("input"))
        settle(s.window)

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val page = all(".guide").single().getBoundingClientRect()
        val hits = all(".guide-hits").singleOrNull()?.getBoundingClientRect() ?: error("$on there are no hits")
        assertTrue(hits.width >= 320.0, "$on the hits are squeezed to ${hits.width}px")
        assertTrue(hits.width <= 720.0, "$on the hits run ${hits.width}px wide, too long a line to read")
        assertTrue(
            abs((hits.left - page.left) - (page.right - hits.right)) < 4.0,
            "$on the hits are not centred: ${hits.left - page.left}px on the left, ${page.right - hits.right}px on the right",
        )
        val bar = box.getBoundingClientRect()
        assertTrue(
            abs(bar.left - hits.left) < 4.0 && abs(bar.right - hits.right) < 4.0,
            "$on the search bar is not over the hits: ${bar.left}..${bar.right} against ${hits.left}..${hits.right}",
        )
    }

    @Test
    fun searchReadsOnAFoldingPhoneOpenedOut() = runTest { searchReads(Tablet.UNFOLDED) }

    @Test
    fun searchReadsOnATabletHeldSideways() = runTest { searchReads(Tablet.LANDSCAPE) }
}
