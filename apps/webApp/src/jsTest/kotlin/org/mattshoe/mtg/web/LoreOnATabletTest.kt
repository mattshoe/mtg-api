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
 * The story of Phyrexia on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the story must not run
 * the full width, where a line is too long to read, and the tabs must
 * sit on the screen. The phone's `LoreOnATabletTest` is the same claim.
 */
class LoreOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun theStoryReads(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/phyrexia"))) }
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

        val story = all(".lore-story").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no story")
        assertTrue(story.width >= 320.0, "$on the story is squeezed to ${story.width}px")
        assertTrue(story.width <= 720.0, "$on the story runs ${story.width}px wide, too long a line to read")
        val page = all(".guide").single().getBoundingClientRect()
        assertTrue(
            abs((story.left - page.left) - (page.right - story.right)) < 4.0,
            "$on the story is not centred: ${story.left - page.left}px on the left, ${page.right - story.right}px on the right",
        )

        val tabs = all(".lore-tabs [role=tab]").map { it.getBoundingClientRect() }
        assertTrue(tabs.isNotEmpty(), "$on there are no tabs")
        assertTrue(tabs.first().left >= 0, "$on the first tab is off the left edge")
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val row = all(".lore-tabs").single().getBoundingClientRect()
        assertTrue(row.right <= size.first, "$on the tab row runs off the side: ${row.right}")
    }

    @Test
    fun theStoryReadsOnAFoldingPhoneOpenedOut() = runTest { theStoryReads(Tablet.UNFOLDED) }

    @Test
    fun theStoryReadsOnATabletHeldSideways() = runTest { theStoryReads(Tablet.LANDSCAPE) }
}
