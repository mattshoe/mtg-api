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
 * The story of Magic on a tablet, the website's half.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". On a wide screen the sections and the
 * article must not run the full width, where a line is too long to
 * read, and the tabs must sit on the screen. The phone's
 * `StoryOnATabletTest` is the same claim.
 */
class StoryOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun open(size: Pair<Int, Int>, hash: String): (String) -> List<HTMLElement> {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse(hash))) }
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
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "on a ${size.first} wide screen the page is ${doc.documentElement!!.scrollWidth}px wide")
        return { css -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
    }

    private fun readable(on: String, what: String, box: org.w3c.dom.DOMRect, page: org.w3c.dom.DOMRect) {
        assertTrue(box.width >= 320.0, "$on $what is squeezed to ${box.width}px")
        assertTrue(box.width <= 720.0, "$on $what runs ${box.width}px wide, too long a line to read")
        assertTrue(
            abs((box.left - page.left) - (page.right - box.right)) < 4.0,
            "$on $what is not centred: ${box.left - page.left}px on the left, ${page.right - box.right}px on the right",
        )
    }

    private suspend fun anArticleReads(size: Pair<Int, Int>) {
        val all = open(size, "#/guide/lore/the-war")
        val on = "on a ${size.first} by ${size.second} screen"
        val page = all(".guide").single().getBoundingClientRect()
        readable(on, "the story", all(".lore-story").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no story"), page)
        val tabs = all(".lore-tabs [role=tab]").map { it.getBoundingClientRect() }
        assertTrue(tabs.size >= 2, "$on there are ${tabs.size} tabs")
        assertTrue(tabs.first().left >= 0, "$on the first tab is off the left edge")
        assertTrue(all(".lore-tabs").single().getBoundingClientRect().right <= size.first, "$on the tab row runs off the side")
    }

    private suspend fun theSectionsRead(size: Pair<Int, Int>) {
        val all = open(size, "#/guide/lore")
        val on = "on a ${size.first} by ${size.second} screen"
        val page = all(".guide").single().getBoundingClientRect()
        readable(on, "the sections", all(".story-index").singleOrNull()?.getBoundingClientRect() ?: error("$on there are no sections"), page)
    }

    @Test
    fun anArticleReadsOnAFoldingPhoneOpenedOut() = runTest { anArticleReads(Tablet.UNFOLDED) }

    @Test
    fun anArticleReadsOnATabletHeldSideways() = runTest { anArticleReads(Tablet.LANDSCAPE) }

    @Test
    fun theSectionsReadOnAFoldingPhoneOpenedOut() = runTest { theSectionsRead(Tablet.UNFOLDED) }

    @Test
    fun theSectionsReadOnATabletHeldSideways() = runTest { theSectionsRead(Tablet.LANDSCAPE) }
}
