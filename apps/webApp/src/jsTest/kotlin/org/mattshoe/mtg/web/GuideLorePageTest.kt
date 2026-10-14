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
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.Window
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every story tile, in one Lore tile, in a real browser through the real shell.
 *
 * Matt: "collapse all the Guide sections on lore (not the color stuff)
 * into a single top-level one called Lore or something. then just move
 * the current tiles into that one but keep them sorted in roughly
 * chronological order as much as possible". On a phone's width, a
 * folding phone opened out and a tablet held sideways. Sibling of
 * `GuideLoreParityTest` on the phone.
 */
class GuideLorePageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: MutableState<AppState>

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

    private suspend fun loreHoldsTheStory(size: Pair<Int, Int>?) {
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
        val tile = { title: String -> all("button.guide-tile").first { it.querySelector(".guide-tile-title")?.textContent?.trim() == title } }
        val back = { all("button.guide-back").singleOrNull() ?: error("$on there is no way back") }

        assertEquals(
            listOf(Guide.COLOURS, Guide.WHEEL, Guide.RULES, Guide.GAME_CHANGERS, Guide.LORE).map { Guide.tile(it)!!.title },
            titles(),
            "$on the Guide's front page still carries the story tiles one by one",
        )

        tile("Lore").click()
        settle(w)
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertEquals(Guide.lore.map { it.title }, titles(), "$on Lore does not hold every story tile, oldest first")
        assertTrue("Guide" in back().textContent.orEmpty(), "$on Lore's way back does not say Guide: ${back().textContent}")
        // Read in order: each tile starts below the one before it, or level with it and to its right.
        all("button.guide-tile").map { it.getBoundingClientRect() }.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue(b.top > a.top + 1 || (kotlin.math.abs(b.top - a.top) <= 1 && b.left > a.left), "$on ${Guide.lore[i + 1].title} comes before ${Guide.lore[i].title}")
        }
        all("button.guide-tile").forEach { t ->
            val r = t.getBoundingClientRect()
            assertTrue(r.right <= width + 0.5, "$on ${t.textContent} runs off the side: ${r.right}")
            assertTrue(r.width >= 200.0, "$on ${t.textContent} is squeezed to ${r.width}px")
        }

        tile("Brothers' War").click()
        settle(w)
        assertEquals("#/guide/brothers-war", held.value.route.toHash())
        assertTrue("Lore" in back().textContent.orEmpty(), "$on a story's way back does not say Lore: ${back().textContent}")
        back().click()
        settle(w)
        assertEquals("#/guide/lore", held.value.route.toHash(), "$on back from a story skipped Lore")

        back().click()
        settle(w)
        assertEquals("#/guide", held.value.route.toHash(), "$on back from Lore is not the Guide")
    }

    @Test
    fun loreHoldsTheStoryOnAPhone() = runTest { loreHoldsTheStory(null) }

    @Test
    fun loreHoldsTheStoryOnAFoldingPhoneOpenedOut() = runTest { loreHoldsTheStory(Tablet.UNFOLDED) }

    @Test
    fun loreHoldsTheStoryOnATabletHeldSideways() = runTest { loreHoldsTheStory(Tablet.LANDSCAPE) }
}
