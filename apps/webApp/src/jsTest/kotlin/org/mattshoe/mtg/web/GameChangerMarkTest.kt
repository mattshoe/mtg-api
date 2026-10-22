package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game changer is marked on its deck row and in the carousel, the
 * website's half.
 *
 * Matt: "i want a clear indicator in carousels and deck lists for any
 * game changer cards". The phone's `GameChangerMarkParityTest` makes
 * the same claims. The real `AppShell`, on a phone-sized screen and on
 * two tablets.
 */
class GameChangerMarkTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(name: String, type: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
    )

    private val start = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt0001", null, "UB", 3, null)))
                    .opened("alela", listOf(card("Cultivate", "Sorcery"), card("Rhystic Study", "Enchantment"))),
            )
        }

    /** Relative luminance of a computed `rgb(...)`, 0 for black to 1 for white. */
    private fun luminance(css: String): Double {
        val (r, g, b) = Regex("""\d+(\.\d+)?""").findAll(css).take(3).map { it.value.toDouble() / 255 }.toList()
        fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private suspend fun marked(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(start)
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

        // The deck row.
        val rows = all(".deck-line")
        val rhystic = rows.first { it.textContent.orEmpty().contains("Rhystic Study") }
        val cultivate = rows.first { it.textContent.orEmpty().contains("Cultivate") }
        val marks = all(".deck-line .gc-mark")
        assertEquals(1, marks.size, "$on the deck list has ${marks.size} game changer marks for one game changer")
        val mark = marks.single()
        assertTrue(rhystic.contains(mark), "$on the mark is not on Rhystic Study's row")
        assertTrue(!cultivate.contains(mark), "$on Cultivate is marked as a game changer")
        assertEquals("Game Changer", mark.getAttribute("aria-label"), "$on a screen reader is not told the mark means a game changer")
        val box = mark.getBoundingClientRect()
        val row = rhystic.getBoundingClientRect()
        assertTrue(box.width > 0 && box.height > 0, "$on the mark has no size")
        assertTrue(box.top >= row.top - 0.5 && box.bottom <= row.bottom + 0.5, "$on the mark is outside its row")
        assertTrue(box.right <= size.first + 0.5, "$on the mark runs off the right of the screen")
        val style = s.window.getComputedStyle(mark)
        val bg = luminance(style.backgroundColor)
        val ink = luminance(style.color)
        val page = luminance(s.window.getComputedStyle(doc.body!!).backgroundColor)
        // Matt is colourblind: it has to stand out by lightness.
        assertTrue(bg > page + 0.25, "$on the mark's fill (${style.backgroundColor}) is not much lighter than the page")
        assertTrue((bg + 0.05) / (ink + 0.05) >= 4.5, "$on the mark's words (${style.color}) do not read on its fill")

        // The carousel.
        cultivate.click()
        settle(s.window)
        assertEquals("Cultivate", all(".peek-name").single().textContent, "$on the carousel did not open on Cultivate")
        assertEquals(0, all(".peek-sheet .gc-mark").size, "$on Cultivate's carousel sheet is marked as a game changer")
        held.value = held.value.peekAt(held.value.peekRun.indexOfFirst { it.title == "Rhystic Study" })
        settle(s.window)
        assertEquals("Rhystic Study", all(".peek-name").single().textContent)
        val peekMark = all(".peek-sheet .gc-mark").singleOrNull() ?: error("$on Rhystic Study's carousel sheet has no game changer mark")
        assertEquals("Game Changer", peekMark.textContent, "$on the carousel's mark does not say Game Changer")
        val name = all(".peek-name").single().getBoundingClientRect()
        val pm = peekMark.getBoundingClientRect()
        assertTrue(pm.width > 0 && pm.right <= size.first + 0.5, "$on the carousel's mark is off the screen")
        assertTrue(
            abs((pm.top + pm.bottom) / 2 - (name.top + name.bottom) / 2) < name.height,
            "$on the carousel's mark is not level with the card's name",
        )
    }

    @Test
    fun aGameChangerIsMarkedOnAPhone() = runTest { marked(390 to 844) }

    @Test
    fun aGameChangerIsMarkedOnAFoldingPhoneOpenedOut() = runTest { marked(Tablet.UNFOLDED) }

    @Test
    fun aGameChangerIsMarkedOnATabletHeldSideways() = runTest { marked(Tablet.LANDSCAPE) }
}
