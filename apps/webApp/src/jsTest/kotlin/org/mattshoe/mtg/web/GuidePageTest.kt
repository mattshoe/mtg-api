package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide, in a real browser, through the real menu and shell.
 *
 * Matt: "when you tap the tab on bottom nav, you land on a new page,
 * which is a collection of very attractive and stylish 'tiles' ... a
 * reference to all the names for all the color combinations like
 * izzet, dimir, etc etc. all of them all the way through wubrg."
 * Sibling of `GuideParityTest` on the phone.
 */
class GuidePageTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    /** A phone's width, so the grid is measured where Matt uses it. */
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

    /** Into the Guide the way a person gets there: the menu, then its item. */
    private suspend fun HTMLElement.openGuide() {
        all("button.nav-burger").single().click()
        settle()
        val item = all(".app-menu button.app-tab").firstOrNull { it.textContent?.trim() == "Guide" }
            ?: error("the menu offers no Guide: ${all(".app-menu button.app-tab").map { it.textContent }}")
        item.click()
        settle()
    }

    private suspend fun HTMLElement.openColours() {
        openGuide()
        val tile = all("button.guide-tile").firstOrNull() ?: error("the Guide has no tiles")
        tile.click()
        settle()
    }

    @Test
    fun theMenuTakesYouToAPageOfTiles() = runTest {
        val frame = mount()
        settle()
        frame.openGuide()
        assertEquals(View.GUIDE, held.value.view)
        val tiles = frame.all("button.guide-tile")
        assertTrue(tiles.isNotEmpty(), "the Guide page has no tiles")
        assertTrue(
            tiles.first().textContent.orEmpty().contains("Colour", ignoreCase = true),
            "the first tile is not the colour combinations: ${tiles.first().textContent}",
        )
    }

    @Test
    fun aTileIsARaisedSurfaceWithTheColoursAcrossItsTop() = runTest {
        val frame = mount()
        settle()
        frame.openGuide()
        val tile = frame.all("button.guide-tile").first()
        val look = window.getComputedStyle(tile)
        assertTrue(look.borderTopLeftRadius != "0px", "the tile has square corners, so it is not styled")
        assertTrue(look.boxShadow != "none", "the tile has no shadow, so it sits flat on the page")
        val band = tile.all(".guide-band").singleOrNull() ?: error("the tile has no colour strip")
        assertTrue(
            window.getComputedStyle(band).backgroundImage.contains("gradient"),
            "the strip is not painted: ${window.getComputedStyle(band).backgroundImage}",
        )
        assertTrue(band.getBoundingClientRect().height >= 3.0, "the strip is too thin to see")
    }

    @Test
    fun theColourTileListsEveryCombinationByName() = runTest {
        val frame = mount()
        settle()
        frame.openColours()
        assertEquals("#/guide/colours", held.value.route.toHash())
        val names = frame.all(".combo .combo-name").map { it.textContent.orEmpty().trim() }
        assertEquals(31, names.size, "every combination from one colour to five is 31; the page shows $names")
        listOf("Izzet", "Dimir", "Azorius", "Esper", "Jeskai", "Glint-Eye", "Five-colour").forEach {
            assertTrue(it in names, "$it is not on the page: $names")
        }
        // The symbols, not just the words: Izzet shows a blue and a red.
        val izzet = frame.all(".combo").single { it.querySelector(".combo-name")?.textContent == "Izzet" }
        assertEquals(2, izzet.all("img.mana-sym").size, "Izzet should show two mana symbols")
    }

    @Test
    fun atAPhonesWidthTheCombinationsSitTwoToARow() = runTest {
        val frame = mount(400)
        settle()
        frame.openColours()
        val pairs = frame.all(".combo-grid").first().all(".combo")
        val a = pairs[0].getBoundingClientRect()
        val b = pairs[1].getBoundingClientRect()
        assertEquals(a.top, b.top, "the second tile is not beside the first, so the page is one long column")
        assertTrue(b.left >= a.right, "the two tiles overlap: ${a.right} vs ${b.left}")
        assertTrue(a.width >= 140.0, "a tile is ${a.width}px wide, too narrow for a name like Selesnya")
    }

    @Test
    fun theGroupsSayWhatTheyAre() = runTest {
        val frame = mount()
        settle()
        frame.openColours()
        val titles = frame.all(".guide-group-title").map { it.textContent.orEmpty() }
        assertEquals(7, titles.size, "groups on the page: $titles")
        frame.all(".guide-group-about").forEach {
            assertTrue(it.textContent.orEmpty().isNotBlank(), "a group has no line saying what it is")
        }
    }

    @Test
    fun backFromTheColoursIsTheTiles() = runTest {
        val frame = mount()
        settle()
        frame.openColours()
        frame.all("button.guide-back").singleOrNull()?.click() ?: error("there is no way back to the tiles")
        settle()
        assertEquals("#/guide", held.value.route.toHash())
        assertTrue(frame.all("button.guide-tile").isNotEmpty(), "back did not show the tiles again")
        assertTrue(frame.all(".combo").isEmpty(), "the colour page is still drawn")
    }
}
