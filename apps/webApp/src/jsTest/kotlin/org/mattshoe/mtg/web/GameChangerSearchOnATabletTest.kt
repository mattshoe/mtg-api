package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the Game Changers on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The search bar sits over the list, as wide
 * as it and no wider, and what it found is still centred. The phone's
 * `GameChangerSearchOnATabletTest` is the same claim.
 */
class GameChangerSearchOnATabletTest {

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
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.GAME_CHANGERS}"))) }
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
        val box = all("input.gc-search").singleOrNull()?.unsafeCast<HTMLInputElement>() ?: error("$on the Game Changers page has no search bar")
        box.value = "tutor"
        box.dispatchEvent(Event("input"))
        settle(s.window)

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        assertEquals(5, all(".gc-list .deck-line").size, "$on searching tutor did not leave the five tutors")
        val page = all(".guide").single().getBoundingClientRect()
        val list = all(".gc-list").single().getBoundingClientRect()
        assertTrue(list.width in 320.0..720.0, "$on the list is ${list.width}px wide")
        assertTrue(
            abs((list.left - page.left) - (page.right - list.right)) < 4.0,
            "$on the list is not centred: ${list.left - page.left}px on the left, ${page.right - list.right}px on the right",
        )
        val bar = box.getBoundingClientRect()
        assertTrue(
            abs(bar.left - list.left) < 4.0 && abs(bar.right - list.right) < 4.0,
            "$on the search bar is not over the list: ${bar.left}..${bar.right} against ${list.left}..${list.right}",
        )
    }

    @Test
    fun searchReadsOnAFoldingPhoneOpenedOut() = runTest { searchReads(Tablet.UNFOLDED) }

    @Test
    fun searchReadsOnATabletHeldSideways() = runTest { searchReads(Tablet.LANDSCAPE) }
}
