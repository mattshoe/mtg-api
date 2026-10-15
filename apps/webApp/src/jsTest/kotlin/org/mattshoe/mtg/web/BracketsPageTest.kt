package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Brackets
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's Commander brackets, in a real browser, through the real
 * menu and shell, on a phone and on a tablet both ways.
 *
 * Matt: "each bracket section should have its own tile or section or
 * card or something, and each should be collapsed by default so the
 * use has to open the bracket they're concerned with ... sort them in
 * ascending order". Sibling of `BracketsParityTest` on the phone.
 */
class BracketsPageTest {

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

    private fun text(e: HTMLElement, css: String) = e.all(css).singleOrNull()?.textContent.orEmpty()

    /** Every card one to five, each below the last, every one shut, saying its number, name and tagline. */
    private fun everyBracketShutInOrder(cards: List<HTMLElement>, on: String) {
        assertEquals(listOf("1", "2", "3", "4", "5"), cards.map { text(it, ".bracket-number") }, "$on the brackets are not one to five")
        assertEquals(Brackets.all.map { it.name }, cards.map { text(it, ".bracket-name") }, "$on the brackets are misnamed")
        assertEquals(Brackets.all.map { it.tagline }, cards.map { text(it, ".bracket-tagline") }, "$on a bracket's tagline is missing")
        cards.zipWithNext().forEach { (a, b) ->
            assertTrue(
                b.getBoundingClientRect().top >= a.getBoundingClientRect().bottom,
                "$on bracket ${text(b, ".bracket-number")} is not below ${text(a, ".bracket-number")}",
            )
        }
        val open = cards.filter { it.all(".bracket-rule").isNotEmpty() || it.all(".bracket-head").single().getAttribute("aria-expanded") != "false" }
        assertTrue(open.isEmpty(), "$on these brackets start open: ${open.map { text(it, ".bracket-name") }}")
        cards.forEach {
            assertTrue(it.getBoundingClientRect().height < 120.0, "$on ${text(it, ".bracket-name")} is ${it.getBoundingClientRect().height}px tall shut")
        }
    }

    @Test
    fun theGuideOpensTheBracketsShutInOrderAndOneOpensAlone() = runTest {
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
        val at = tiles.indexOf("Brackets")
        assertTrue(at >= 0, "the Guide has no Brackets tile: $tiles")
        assertEquals("Rules", tiles.getOrNull(at - 1), "the Brackets tile is not right after Rules: $tiles")
        frame.all("button.guide-tile")[at].click()
        settle()
        assertEquals(Guide.BRACKETS, held.value.route.rest, "the tile did not open its own address")

        val cards = frame.all(".bracket-list .bracket-card")
        everyBracketShutInOrder(cards, "on a phone")

        val fourBefore = cards[3].getBoundingClientRect().top
        cards[2].all(".bracket-head").single().click()
        settle()
        val three = frame.all(".bracket-list .bracket-card")[2]
        assertEquals("true", three.all(".bracket-head").single().getAttribute("aria-expanded"), "tapping bracket 3 did not open it")
        val rules = three.all(".bracket-rule")
        assertEquals(Brackets.all[2].rules.map { it.topic }, rules.map { text(it, ".bracket-rule-topic") }, "bracket 3 does not list its rules")
        assertEquals("Up to three", text(rules[0], ".bracket-rule-short"), "bracket 3 does not say up to three Game Changers")
        assertEquals("~", text(rules[0], ".bracket-mark"), "a limit is not marked as a limit")
        assertEquals("✕", text(rules[2], ".bracket-mark"), "no mass land denial is not marked as a no")
        assertEquals(Brackets.all[2].games, text(three, ".bracket-games"))
        val wide = rules.filter { it.getBoundingClientRect().right > frame.getBoundingClientRect().right + 0.5 }
        assertTrue(wide.isEmpty(), "rules run off a 400px screen: ${wide.map { it.textContent }}")
        val fourAfter = frame.all(".bracket-list .bracket-card")[3].getBoundingClientRect().top
        assertTrue(fourAfter > fourBefore + 100, "bracket 3 opened without pushing bracket 4 down: $fourBefore to $fourAfter")
        val others = frame.all(".bracket-list .bracket-card").filterIndexed { i, c -> i != 2 && c.all(".bracket-rule").isNotEmpty() }
        assertTrue(others.isEmpty(), "opening bracket 3 opened others too: ${others.map { text(it, ".bracket-name") }}")

        three.all(".bracket-head").single().click()
        settle()
        assertTrue(frame.all(".bracket-rule").isEmpty(), "tapping bracket 3 again did not shut it")
    }

    private suspend fun cardsRead(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val state = mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.BRACKETS}")))
        renderComposable(root = s.body) {
            AppShell(state = state.value, onState = { state.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val page = all(".guide").single().getBoundingClientRect()
        val list = all(".bracket-list").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no brackets list")
        assertTrue(list.width >= 320.0, "$on the list is squeezed to ${list.width}px")
        assertTrue(list.width <= 720.0, "$on the list runs ${list.width}px wide")
        assertTrue(
            abs((list.left - page.left) - (page.right - list.right)) < 4.0,
            "$on the list is not centred: ${list.left - page.left}px on the left, ${page.right - list.right}px on the right",
        )
        everyBracketShutInOrder(all(".bracket-list .bracket-card"), on)

        all(".bracket-list .bracket-card")[4].all(".bracket-head").single().click()
        settle(s.window)
        val five = all(".bracket-list .bracket-card")[4]
        assertEquals(Brackets.all[4].rules.size, five.all(".bracket-rule").size, "$on bracket 5 did not open")
        val r = five.getBoundingClientRect()
        assertTrue(r.right <= list.right + 0.5 && r.left >= list.left - 0.5, "$on bracket 5 opened wider than the list")
    }

    @Test
    fun theBracketsReadOnAFoldingPhoneOpenedOut() = runTest { cardsRead(Tablet.UNFOLDED) }

    @Test
    fun theBracketsReadOnATabletHeldSideways() = runTest { cardsRead(Tablet.LANDSCAPE) }
}
