package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Facets
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the Library page actually looks like, measured.
 *
 * Karma serves `frontend/css/app.css` — the site's real stylesheet, the
 * same file the browser loads — so these run against the real cascade
 * rather than an unstyled DOM. The page is rendered into a container of
 * a fixed width, which is how a phone is simulated without a phone.
 *
 * Everything here is a number. "Looks like shit" is not testable;
 * "these two controls in the same row have centres 9px apart" is, and
 * it is the same complaint.
 */
class LibraryLayoutTest {

    private val roots = mutableListOf<HTMLElement>()
    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun styled() = Stylesheet.applied()

    private fun card(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 3, printings = 1, free = 1, price = 1.5, value = 1.5,
    )

    /** The page at a fixed width, the way a phone or a laptop sees it. */
    private fun render(width: Int, openFilters: Boolean = false): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        frame.style.top = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            var s by remember {
                mutableStateOf(
                    AppState(facets = Facets(types = listOf("Artifact", "Creature")))
                        .let { it.copy(library = it.library.loaded(List(6) { i -> card("Card $i") }, 250)) },
                )
            }
            AppShell(s, { s = it }, {}, {}, {}, {}, {}, {})
        }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private class Box(val el: HTMLElement) {
        val r = el.getBoundingClientRect()
        val centre get() = r.top + r.height / 2
        val what
            get() = (el.tagName.lowercase() + "." + el.className).take(40) +
                " \"" + el.textContent.orEmpty().trim().take(18) + "\""
    }

    // ------------------------------------------------------------ probes

    @Test
    fun theStylesheetIsActuallyLoaded() = runTest {
        settle()
        assertTrue(styled(), "the stylesheet did not load; every layout test below is meaningless")
    }

    @Test
    fun nothingOverflowsThePageAtPhoneWidth() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.all("*").filter { it.getBoundingClientRect().right > limit }
            .map { Box(it).what + " right=" + Box(it).r.right }
        assertTrue(over.isEmpty(), "these run off the right edge at 390px:\n" + over.joinToString("\n"))
    }

    @Test
    fun theControlRowLinesUp() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        // Filters, Export, the sort dropdown and the direction arrow
        // share a row. Their centres have to agree or the row reads as
        // broken, which is exactly what it was reported as.
        val row = frame.all("div.flex-wrap")
            .firstOrNull { it.textContent.orEmpty().contains("Export") }
            ?: error("could not find the control row")
        val kids = row.all("button, select").map { Box(it) }
        assertTrue(kids.size >= 3, "expected at least Filters, Export and the sort picker")

        val top = kids.minOf { it.centre }
        val off = kids.filter { abs(it.centre - top) > 2 }
        assertTrue(
            off.isEmpty(),
            "the control row is not aligned:\n" +
                kids.joinToString("\n") { "  ${it.what} centre=${it.centre} height=${it.r.height}" },
        )
    }

    @Test
    fun theControlsInThatRowAreTheSameHeight() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val row = frame.all("div.flex-wrap")
            .firstOrNull { it.textContent.orEmpty().contains("Export") }
            ?: error("could not find the control row")
        val kids = row.all("button, select").map { Box(it) }
        val tallest = kids.maxOf { it.r.height }
        val shortest = kids.minOf { it.r.height }
        assertTrue(
            tallest - shortest <= 4,
            "the controls are different sizes — tallest $tallest, shortest $shortest:\n" +
                kids.joinToString("\n") { "  ${it.what} height=${it.r.height}" },
        )
    }

    @Test
    fun thereIsAirBetweenTheRowsOfThePanel() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val body = frame.all("div.panel-body").firstOrNull() ?: error("no panel body")
        val rows = (0 until body.children.length).mapNotNull { body.children[it] as? HTMLElement }
        assertTrue(rows.size >= 2, "expected the name box and the control row")
        rows.zipWithNext().forEach { (a, b) ->
            val gap = b.getBoundingClientRect().top - a.getBoundingClientRect().bottom
            assertTrue(gap >= 6, "only ${gap}px between ${Box(a).what} and ${Box(b).what}")
        }
    }

    @Test
    fun theFilterGroupsAreAllFoldedAndNoneOverflow() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val groups = frame.all("details[data-facet]")
        assertTrue(groups.size == 10, "expected ten groups, saw ${groups.size}")
        assertTrue(groups.none { it.hasAttribute("open") }, "a group started open")

        val limit = frame.getBoundingClientRect().right + 1
        val over = groups.filter { it.getBoundingClientRect().right > limit }.map { Box(it).what }
        assertTrue(over.isEmpty(), "filter groups run off the edge: $over")
    }

    @Test
    fun anOpenGroupKeepsItsControlsInsideThePage() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val bad = mutableListOf<String>()
        org.mattshoe.mtg.core.Facet.entries.forEach { facet ->
            val group = frame.all("details[data-facet=${facet.id}]").first()
            (group.querySelector("summary") as HTMLElement).click()
            settle()
            group.all("input, select, button").forEach {
                if (it.getBoundingClientRect().right > limit) bad += "${facet.title}: ${Box(it).what}"
            }
            (group.querySelector("summary") as HTMLElement).click()
            settle()
        }
        assertTrue(bad.isEmpty(), "controls off the edge at 390px:\n" + bad.joinToString("\n"))
    }

    @Test
    fun theCardGridFitsTwoAcrossOnAPhoneAndMoreOnADesktop() = runTest {
        val phone = render(390)
        settle()
        if (!styled()) return@runTest
        val phoneTops = phone.all("div.card").map { it.getBoundingClientRect().top }.distinct()
        val perRowPhone = phone.all("div.card").count { it.getBoundingClientRect().top == phoneTops.first() }

        val desk = render(1400)
        settle()
        val deskTops = desk.all("div.card").map { it.getBoundingClientRect().top }.distinct()
        val perRowDesk = desk.all("div.card").count { it.getBoundingClientRect().top == deskTops.first() }

        assertTrue(perRowPhone in 2..3, "a phone showed $perRowPhone cards across")
        assertTrue(perRowDesk > perRowPhone, "a desktop showed $perRowDesk, a phone $perRowPhone")
    }

    @Test
    fun everyCardTileHasItsPictureBadgesAndCaption() = runTest {
        val frame = render(1400)
        settle()
        if (!styled()) return@runTest
        frame.all("div.card").forEach { tile ->
            assertTrue(tile.querySelector("img.card-img") != null, "a tile has no picture")
            assertTrue(tile.querySelector(".free-badge") != null, "a tile has no free badge")
            assertTrue(tile.querySelector(".price-badge") != null, "a tile has no price badge")
            assertTrue(tile.querySelector(".card-meta .nm") != null, "a tile has no name")
            val img = tile.querySelector("img.card-img") as HTMLElement
            assertTrue(img.getBoundingClientRect().height > 40, "the picture has no height")
        }
    }

    @Test
    fun theMenuStaysOnScreenAtPhoneWidth() = runTest {
        // The nav lives in the header now, so it is mounted on its
        // own here. Opened, the menu must not run off the side.
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "390px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { AppNav(AppState()) {} }
        settle()
        if (!styled()) return@runTest

        (frame.querySelector("button.nav-burger") as HTMLElement).click()
        settle()
        val menu = frame.all("div.app-menu").first()
        assertTrue(menu.getBoundingClientRect().height > 0, "the menu did not open")
        val limit = frame.getBoundingClientRect().right + 1
        val off = menu.all("button").filter { it.getBoundingClientRect().right > limit }
        assertTrue(off.isEmpty(), "off the edge: ${off.map { Box(it).what }}")
    }
}
