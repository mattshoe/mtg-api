package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.GameChangers
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekOf
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's Game Changers, in a real browser, through the real menu
 * and shell.
 *
 * Matt: "add a guide section for game changers. it should be a list
 * of cards just like a deck page. tapping any of them should pop a
 * carousel like any other cards". Sibling of `GameChangersParityTest`
 * on the phone.
 */
class GameChangersPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    /** A phone's width, so the list is measured where Matt uses it. */
    private fun mount(width: Int = 400): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = remember { mutableStateOf(AppState()) }
            AppNav(held.value, onState = { held.value = it })
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return frame
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** The menu, its Guide, then the Game Changers tile, the way a person gets there. */
    private suspend fun HTMLElement.openGameChangers() {
        all("button.nav-burger").single().click()
        settle()
        val item = all(".app-menu button.app-tab").firstOrNull { it.textContent?.trim() == "Guide" }
            ?: error("the menu offers no Guide")
        item.click()
        settle()
        val tile = all("button.guide-tile").firstOrNull { it.textContent.orEmpty().contains("Game Changers") }
            ?: error("the Guide has no Game Changers tile: ${all("button.guide-tile").map { it.textContent }}")
        tile.click()
        settle()
    }

    private fun HTMLElement.rows(): List<HTMLElement> = all(".gc-list .deck-line")

    private fun HTMLElement.nameOf(row: HTMLElement): String = row.all(".t-name").single().textContent.orEmpty()

    @Test
    fun theTileOpensEveryGameChangerByTypeLikeADeck() = runTest {
        val frame = mount()
        settle()
        frame.openGameChangers()
        assertEquals("game-changers", held.value.route.rest, "the tile did not open its own address")

        val heads = frame.all(".gc-list .panel-head h2").map { it.textContent.orEmpty() }
        assertEquals(
            listOf("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts", "Enchantments", "Lands"),
            heads,
            "the list is not sectioned the way a deck is",
        )
        val rows = frame.rows()
        assertEquals(53, rows.size, "the page does not list the 53 game changers")
        assertEquals("Braids, Cabal Minion", frame.nameOf(rows.first()))
        assertEquals("The Tabernacle at Pendrell Vale", frame.nameOf(rows.last()))

        // Every row a picture, the art of the printing the carousel draws.
        val noArt = rows.zip(GameChangers.pageOrder).filter { (row, card) ->
            val img = row.querySelector("img")?.unsafeCast<HTMLImageElement>()
            img == null || !img.src.contains(card.scryfallId!!)
        }.map { frame.nameOf(it.first) }
        assertTrue(noArt.isEmpty(), "these rows have no art of their own: $noArt")

        val wide = rows.filter { it.getBoundingClientRect().right > frame.getBoundingClientRect().right + 0.5 }
        assertTrue(wide.isEmpty(), "rows run off a 400px screen: ${wide.map { frame.nameOf(it) }}")
    }

    @Test
    fun tappingACardPopsTheCarouselOnThatCard() = runTest {
        val frame = mount()
        settle()
        frame.openGameChangers()
        val study = frame.rows().firstOrNull { frame.nameOf(it) == "Rhystic Study" } ?: error("no Rhystic Study row")
        study.click()
        settle()

        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Rhystic Study opened no carousel")
        assertEquals(PeekOf.GAME_CHANGERS, held.value.peek.of)
        val sheet = frame.all(".peek-sheet").singleOrNull() ?: error("the carousel is not drawn")
        assertTrue(sheet.getBoundingClientRect().height > 0.0, "the carousel's sheet has no height")
        assertEquals("Rhystic Study", sheet.all(".peek-name").single().textContent)
        val at = GameChangers.pageOrder.indexOfFirst { it.name == "Rhystic Study" }
        assertEquals("${at + 1} of 53", sheet.all(".peek-place").single().textContent)
        val tags = sheet.all(".peek-tags .tag").map { it.textContent.orEmpty() }
        assertTrue("EDHREC #46 of all cards" in tags, "the carousel does not say how played it is: $tags")
        assertTrue(frame.all(".peek-card").size == 53, "the rail does not hold every game changer: ${frame.all(".peek-card").size}")
    }
}
