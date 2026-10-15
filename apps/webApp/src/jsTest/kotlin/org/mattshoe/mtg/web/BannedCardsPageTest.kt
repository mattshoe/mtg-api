package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Banned
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's banned cards, in a real browser, through the real menu
 * and shell, on a phone and on a tablet both ways.
 *
 * Matt: "add a guide section below game Changers for banned cards.
 * start with Commander at the top, then standard, then whatever order
 * makes sense". Sibling of `BannedCardsParityTest` on the phone.
 */
class BannedCardsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window = window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.nameOf(row: HTMLElement): String = row.all(".t-name").single().textContent.orEmpty()

    private val formats = listOf("Commander", "Standard", "Pioneer", "Modern", "Legacy", "Vintage", "Pauper")

    /** Each format's heading, its count, and the names of its rows, top to bottom. */
    private fun HTMLElement.sections(): List<Triple<String, String, List<String>>> = all(".ban-list .ban-format").map { panel ->
        Triple(
            panel.all(".panel-head h2").single().textContent.orEmpty(),
            panel.all(".panel-head .tag").single().textContent.orEmpty(),
            panel.all(".deck-line").map { nameOf(it) },
        )
    }

    @Test
    fun theGuideOpensEveryBanListCommanderFirstOnAPhone() = runTest {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "400px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = remember { mutableStateOf(AppState()) }
            AppNav(held.value, onState = { held.value = it })
            AppShell(state = held.value, onState = { held.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle()
        frame.all("button.nav-burger").single().click()
        settle()
        (frame.all(".app-menu button.app-tab").firstOrNull { it.textContent?.trim() == "Guide" } ?: error("the menu offers no Guide")).click()
        settle()
        val tiles = frame.all("button.guide-tile").map { it.querySelector(".guide-tile-title")?.textContent.orEmpty() }
        val at = tiles.indexOf("Banned cards")
        assertTrue(at >= 0, "the Guide has no Banned cards tile: $tiles")
        assertEquals("Game Changers", tiles.getOrNull(at - 1), "the Banned cards tile is not right below Game Changers: $tiles")
        frame.all("button.guide-tile")[at].click()
        settle()
        assertEquals(Guide.BANNED, held.value.route.rest, "the tile did not open its own address")

        val sections = frame.sections()
        assertEquals(formats, sections.map { it.first }, "the formats are not Commander, then Standard, then the rest")
        sections.zip(Banned.lists).forEach { (drawn, list) ->
            assertEquals(list.cards.size.toString(), drawn.second, "${drawn.first}'s heading does not say how many it bans")
            assertEquals(list.cards.map { it.name }, drawn.third, "${drawn.first} does not list its ban list")
        }
        assertEquals(Banned.lists.size, frame.all(".ban-list .ban-about").size, "not every format says what it is")

        val rows = frame.all(".ban-list .deck-line")
        val noArt = rows.zip(Banned.pageOrder).filter { (row, card) ->
            val img = row.querySelector("img")?.unsafeCast<HTMLImageElement>()
            img == null || !img.src.contains(card.scryfallId!!)
        }.map { frame.nameOf(it.first) }
        assertTrue(noArt.isEmpty(), "these rows have no art of their own: $noArt")
        val wide = rows.filter { it.getBoundingClientRect().right > frame.getBoundingClientRect().right + 0.5 }
        assertTrue(wide.isEmpty(), "rows run off a 400px screen: ${wide.map { frame.nameOf(it) }}")

        // Black Lotus is Commander's and Legacy's both; tapped in Legacy,
        // the carousel is on Legacy's.
        val legacy = frame.all(".ban-list .ban-format")[formats.indexOf("Legacy")]
        legacy.all(".deck-line").first { frame.nameOf(it) == "Black Lotus" }.click()
        settle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Black Lotus opened no carousel")
        assertEquals(PeekOf.BANNED, held.value.peek.of)
        val sheet = frame.all(".peek-sheet").singleOrNull() ?: error("the carousel is not drawn")
        assertEquals("Black Lotus", sheet.all(".peek-name").single().textContent)
        val row = Banned.lists.takeWhile { it.format != "Legacy" }.sumOf { it.cards.size } +
            Banned.lists.first { it.format == "Legacy" }.cards.indexOfFirst { it.name == "Black Lotus" }
        assertEquals("${row + 1} of ${Banned.pageOrder.size}", sheet.all(".peek-place").single().textContent, "the carousel opened on another format's Black Lotus")
    }

    private suspend fun listReads(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val state = mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.BANNED}")))
        renderComposable(root = s.body) {
            AppShell(state = state.value, onState = { state.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val page = all(".guide").single().getBoundingClientRect()
        val list = all(".ban-list").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no banned cards list")
        assertTrue(list.width >= 320.0, "$on the list is squeezed to ${list.width}px")
        assertTrue(list.width <= 720.0, "$on the list runs ${list.width}px wide")
        assertTrue(
            abs((list.left - page.left) - (page.right - list.right)) < 4.0,
            "$on the list is not centred: ${list.left - page.left}px on the left, ${page.right - list.right}px on the right",
        )
        val heads = all(".ban-list .ban-format .panel-head h2")
        assertEquals(formats, heads.map { it.textContent.orEmpty() }, "$on the formats are out of order")
        heads.zipWithNext().forEach { (a, b) ->
            assertTrue(b.getBoundingClientRect().top > a.getBoundingClientRect().bottom, "$on ${b.textContent} is not below ${a.textContent}")
        }
        assertEquals(Banned.pageOrder.size, all(".ban-list .deck-line").size, "$on not every banned card has a row")

        all(".ban-list .deck-line").first { it.textContent.orEmpty().contains("Mana Crypt") }.click()
        settle(s.window)
        val sheet = all(".peek-sheet").singleOrNull()?.getBoundingClientRect() ?: error("$on tapping Mana Crypt opened no carousel")
        assertEquals("Mana Crypt", all(".peek-name").single().textContent)
        assertTrue(sheet.bottom <= size.second + 0.5, "$on the carousel's sheet runs ${sheet.bottom - size.second}px off the bottom")
    }

    @Test
    fun theListReadsOnAFoldingPhoneOpenedOut() = runTest { listReads(Tablet.UNFOLDED) }

    @Test
    fun theListReadsOnATabletHeldSideways() = runTest { listReads(Tablet.LANDSCAPE) }
}
