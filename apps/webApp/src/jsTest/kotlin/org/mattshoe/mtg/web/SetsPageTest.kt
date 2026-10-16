package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.MagicStory
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.Window
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "i want to see Guide section on all the modern sets from that
 * few years. it should be similar to the root Lore section ... each set
 * gets is own page ... with logical tabs".
 *
 * In a real browser through the real shell: the Guide, the Sets tile
 * after Lore, every set's tile, then Bloomburrow's page and its tabs,
 * and the way back out, on a phone's width, a folding phone opened out
 * and a tablet held sideways. Sibling of `SetsParityTest` on the phone.
 */
class SetsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: MutableState<AppState>

    private val blb get() = MagicStory.sets.first { it.slug == "blb" }

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun shell(root: HTMLElement) = renderComposable(root = root) {
        held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse("#/guide"))) }
        AppShell(
            state = held.value,
            onState = { held.value = it },
            onSearch = {}, onOpenDeck = {},
            onPreviewEntry = {}, onApplyEntry = {},
        )
    }

    private suspend fun everySetHasAPage(size: Pair<Int, Int>?) {
        val (body, w, width) = if (size == null) {
            val frame = document.createElement("div") as HTMLElement
            frame.style.width = "400px"
            frame.style.position = "absolute"
            frame.style.left = "0px"
            document.body!!.appendChild(frame)
            roots += frame
            Triple(frame, window, 400)
        } else {
            val s = Tablet.open(size).also { screen = it }
            Triple(s.body, s.window, size.first)
        }
        shell(body)
        settle(w)
        val on = size?.let { "on a ${it.first} by ${it.second} screen" } ?: "on a phone"
        val all = { css: String -> body.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val titles = { all("button.guide-tile .guide-tile-title").map { it.textContent.orEmpty().trim() } }
        val tile = { title: String -> all("button.guide-tile").firstOrNull { it.querySelector(".guide-tile-title")?.textContent?.trim() == title } ?: error("$on there is no $title tile: ${titles()}") }
        val back = { all("button.guide-back").singleOrNull() ?: error("$on there is no way back") }
        val tabs = { all(".lore-tabs [role=tab]") }
        val parts = { all(".story-part-title").map { it.textContent.orEmpty().trim() } }

        val front = titles()
        assertEquals(listOf("Lore", "Sets"), front.takeLast(2), "$on Sets is not the tile after Lore: $front")

        tile("Sets").click()
        settle(w)
        assertEquals("#/guide/sets", held.value.route.toHash())
        assertEquals(MagicStory.sets.map { it.title }, titles(), "$on Sets does not hold every set, oldest first")
        assertTrue("Guide" in back().textContent.orEmpty(), "$on Sets' way back does not say Guide: ${back().textContent}")
        all("button.guide-tile").forEach { t ->
            val r = t.getBoundingClientRect()
            assertTrue(r.right <= width + 0.5, "$on ${t.textContent} runs off the side: ${r.right}")
            assertTrue(r.width >= 200.0, "$on ${t.textContent} is squeezed to ${r.width}px")
        }

        tile(blb.title).click()
        settle(w)
        assertEquals("#/guide/blb", held.value.route.toHash())
        assertEquals(blb.articles.map { it.title }, tabs().map { it.textContent.orEmpty().trim() }, "$on Bloomburrow's tabs are not its articles")
        assertEquals(listOf("true") + List(blb.articles.size - 1) { "false" }, tabs().map { it.getAttribute("aria-selected") }, "$on Bloomburrow does not open on its first tab")
        assertEquals(blb.articles.first().tabs.map { it.tab }, parts(), "$on the first tab does not tell its parts")
        assertTrue("Sets" in back().textContent.orEmpty(), "$on a set's way back does not say Sets: ${back().textContent}")

        val last = blb.articles.last()
        tabs().last().click()
        settle(w)
        assertEquals("#/guide/blb/${last.slug}", held.value.route.toHash(), "$on the tab is not in the address")
        assertEquals(last.tabs.map { it.tab }, parts(), "$on ${last.title} does not tell its parts")
        if (size != null) {
            val doc = screen!!.frame.contentDocument!!
            assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
            val box = all(".lore-story").single().getBoundingClientRect()
            val guide = all(".guide").single().getBoundingClientRect()
            assertTrue(box.width in 320.0..720.0, "$on the set's page is ${box.width}px wide")
            assertTrue(abs((box.left - guide.left) - (guide.right - box.right)) < 4.0, "$on the set's page is not centred")
        }

        back().click()
        settle(w)
        assertEquals("#/guide/sets", held.value.route.toHash(), "$on back from a set skipped Sets")
        back().click()
        settle(w)
        assertEquals("#/guide", held.value.route.toHash(), "$on back from Sets is not the Guide")
    }

    @Test
    fun everySetHasAPageOnAPhone() = runTest { everySetHasAPage(null) }

    @Test
    fun everySetHasAPageOnAFoldingPhoneOpenedOut() = runTest { everySetHasAPage(Tablet.UNFOLDED) }

    @Test
    fun everySetHasAPageOnATabletHeldSideways() = runTest { everySetHasAPage(Tablet.LANDSCAPE) }
}
