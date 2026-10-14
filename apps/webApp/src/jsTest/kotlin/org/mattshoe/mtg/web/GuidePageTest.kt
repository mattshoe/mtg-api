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
        val tile = all("button.guide-tile").firstOrNull { it.textContent.orEmpty().contains("Colour combinations") }
            ?: error("the Guide has no colour combinations tile: ${all("button.guide-tile").map { it.textContent }}")
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
            tiles.any { it.textContent.orEmpty().contains("Colour combinations") },
            "the Guide has no colour combinations tile: ${tiles.map { it.textContent }}",
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
        val names = frame.all(".combo-row .combo-name").map { it.textContent.orEmpty().trim() }
        assertEquals(31, names.size, "every combination from one colour to five is 31; the page shows $names")
        listOf("Izzet", "Dimir", "Azorius", "Esper", "Jeskai", "Glint-Eye", "Five-colour").forEach {
            assertTrue(it in names, "$it is not on the page: $names")
        }
        // The symbols, not just the words: Izzet shows a blue and a red.
        val izzet = frame.all(".combo-row").single { it.querySelector(".combo-name")?.textContent == "Izzet" }
        assertEquals(2, izzet.all("img.mana-sym").size, "Izzet should show two mana symbols")
    }

    @Test
    fun theCombinationsAreOneListInWubrgOrderOneToALine() = runTest {
        // Matt: "just a list of color combos and names, in wubrg order,
        // so they can easily be scanned to find what i need".
        val frame = mount(400)
        settle()
        frame.openColours()
        val rows = frame.all(".combo-row")
        val order = rows.map { it.getAttribute("data-pips").orEmpty() }
        assertEquals(
            listOf("W", "U", "B", "R", "G", "WU", "WB", "WR", "WG", "UB"),
            order.take(10),
            "the list does not start in WUBRG order: $order",
        )
        assertEquals("WUBRG", order.lastOrNull(), "the list does not end with all five: $order")
        rows.zipWithNext().forEach { (a, b) ->
            val ra = a.getBoundingClientRect()
            val rb = b.getBoundingClientRect()
            assertTrue(
                rb.top >= ra.bottom - 0.5,
                "${b.getAttribute("data-pips")} sits beside ${a.getAttribute("data-pips")}, not under it",
            )
        }
        val tallest = rows.maxOf { it.getBoundingClientRect().height }
        assertTrue(tallest <= 48.0, "a row is ${tallest}px tall, so it is a tile, not a line of a list")
    }

    @Test
    fun theGroupsComeAfterTheList() = runTest {
        val frame = mount()
        settle()
        frame.openColours()
        val last = frame.all(".combo-row").lastOrNull() ?: error("the colour page has no list")
        val group = frame.all(".guide-group").firstOrNull() ?: error("the groups are gone from the page")
        assertTrue(
            group.getBoundingClientRect().top >= last.getBoundingClientRect().bottom,
            "the groups start above the end of the list",
        )
        assertTrue(
            group.textContent.orEmpty().contains("Mono-coloured"),
            "the first group lost what it says: ${group.textContent}",
        )
        val fours = frame.all(".guide-group").single { it.textContent.orEmpty().contains("Four colours") }
        assertTrue(fours.textContent.orEmpty().contains("No white"), "the four-colour notes are gone: ${fours.textContent}")
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
        assertTrue(frame.all(".combo-row").isEmpty(), "the colour page is still drawn")
    }

    // ------------------------------------------------------------ the wheel
    // Matt: "i want a guide section on the color wheel. i know nothing
    // about it and it'll be cool to have ... it gets its own dedicated page"

    private suspend fun HTMLElement.openWheel() {
        openGuide()
        val tile = all("button.guide-tile").firstOrNull { it.textContent.orEmpty().contains("wheel", ignoreCase = true) }
            ?: error("the Guide has no colour wheel tile: ${all("button.guide-tile").map { it.textContent }}")
        tile.click()
        settle()
    }

    @Test
    fun theWheelTileOpensAPageOfItsOwn() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        assertEquals("#/guide/wheel", held.value.route.toHash())
        assertTrue(frame.all(".combo-row").isEmpty(), "the wheel page is drawing the colour combinations")
        val names = frame.all(".wheel-colour .wheel-colour-name").map { it.textContent.orEmpty().trim() }
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green"), names, "the five colours on the page")
    }

    @Test
    fun theWheelIsDrawnAsACircleWithWhiteAtTheTopGoingClockwise() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        val spokes = frame.all(".wheel-spoke")
        assertEquals(5, spokes.size, "the wheel has ${spokes.size} colours on it")
        val centres = spokes.map { s ->
            val r = s.getBoundingClientRect()
            Triple(s.getAttribute("data-pip").orEmpty(), r.left + r.width / 2, r.top + r.height / 2)
        }
        val cx = centres.sumOf { it.second } / 5
        val cy = centres.sumOf { it.third } / 5
        val radii = centres.map { kotlin.math.hypot(it.second - cx, it.third - cy) }
        assertTrue(radii.min() > 30.0, "the colours are bunched up, not round a wheel: radii $radii")
        assertTrue(radii.max() - radii.min() < 3.0, "the colours are not on one circle: radii $radii")
        assertEquals("W", centres.minBy { it.third }.first, "white is not at the top")
        // Clockwise from the top, on a screen where y runs down.
        val order = centres.sortedBy { (_, x, y) ->
            val a = kotlin.math.atan2(x - cx, cy - y)
            if (a < -0.01) a + 2 * kotlin.math.PI else a
        }.map { it.first }
        assertEquals(listOf("W", "U", "B", "R", "G"), order, "the colours do not go round clockwise in WUBRG order")
        spokes.forEach { assertEquals(1, it.all("img.mana-sym").size, "a colour on the wheel has no mana symbol") }
    }

    @Test
    fun eachColourNamesItsAlliesAndItsEnemies() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        val white = frame.all(".wheel-colour").firstOrNull { it.getAttribute("data-pip") == "W" }
            ?: error("there is no section for white")
        val allies = white.all(".wheel-allies").singleOrNull()?.textContent.orEmpty()
        val enemies = white.all(".wheel-enemies").singleOrNull()?.textContent.orEmpty()
        assertTrue("Blue" in allies && "Green" in allies, "white's allies should be blue and green: \"$allies\"")
        assertTrue("Black" in enemies && "Red" in enemies, "white's enemies should be black and red: \"$enemies\"")
        frame.all(".wheel-colour").forEach { c ->
            listOf(".wheel-wants", ".wheel-good", ".wheel-bad").forEach { part ->
                assertTrue(
                    c.all(part).singleOrNull()?.textContent.orEmpty().isNotBlank(),
                    "${c.getAttribute("data-pip")} has nothing in $part",
                )
            }
        }
    }

    @Test
    fun theTenPairsAreListedByGuild() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        val names = frame.all(".wheel-pair .wheel-pair-name").map { it.textContent.orEmpty().trim() }
        assertEquals(10, names.size, "the pairs on the page: $names")
        listOf("Azorius", "Gruul", "Izzet", "Orzhov").forEach { assertTrue(it in names, "$it is not on the page: $names") }
    }

    // Matt: "we should add lore inserts for the shards of alara and the
    // tribes of tarkir. information that talks about the land scapes and
    // culture with the missing colors of magic."

    @Test
    fun theWheelTellsTheLoreOfEachShardAndClanAndTheColoursEachLacks() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        val lore = frame.all(".wheel-lore")
        assertEquals(
            listOf("Bant", "Esper", "Grixis", "Jund", "Naya", "Abzan", "Jeskai", "Sultai", "Mardu", "Temur"),
            lore.map { it.all(".wheel-lore-name").singleOrNull()?.textContent.orEmpty().trim() },
            "the shards and clans on the wheel page",
        )
        fun missing(pips: String) = lore.firstOrNull { it.getAttribute("data-pips") == pips }
            ?.all(".wheel-lore-missing")?.singleOrNull()?.textContent.orEmpty()
        val bant = missing("GWU")
        assertTrue("Black" in bant && "Red" in bant, "Bant should say it has no black or red: \"$bant\"")
        val abzan = missing("WBG")
        assertTrue("Blue" in abzan && "Red" in abzan, "Abzan should say it has no blue or red: \"$abzan\"")
        lore.forEach { l ->
            listOf(".wheel-lore-land", ".wheel-lore-people", ".wheel-lore-without").forEach { part ->
                val said = l.all(part).singleOrNull()
                assertTrue(said != null && said.textContent.orEmpty().isNotBlank(), "${l.getAttribute("data-pips")} has nothing in $part")
                assertTrue(said.getBoundingClientRect().height > 0, "${l.getAttribute("data-pips")}'s $part takes up no room on the page")
            }
        }
    }

    @Test
    fun backFromTheWheelIsTheTiles() = runTest {
        val frame = mount()
        settle()
        frame.openWheel()
        frame.all("button.guide-back").singleOrNull()?.click() ?: error("there is no way back to the tiles")
        settle()
        assertEquals("#/guide", held.value.route.toHash())
        assertTrue(frame.all(".wheel-spoke").isEmpty(), "the wheel is still drawn")
    }
}
