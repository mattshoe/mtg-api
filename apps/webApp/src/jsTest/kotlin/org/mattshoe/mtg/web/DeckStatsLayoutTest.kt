package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
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
 * The deck's charts, measured against the real stylesheet.
 *
 * A chart that renders is not a chart that is right: a bar of the
 * wrong height or one running off the panel is exactly as wrong as a
 * bad number, and neither shows up in a test of the arithmetic.
 */
class DeckStatsLayoutTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(
        name: String,
        type: String?,
        cost: String?,
        cmc: Double?,
        qty: Int = 1,
        produces: String? = null,
        text: String? = null,
        rarity: String = "rare",
    ) = DeckCard(
        name, qty, null, qty,
        nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc,
        producedMana = produces, oracleText = text, colorIdentity = "W", rarity = rarity, price = 1.5,
    )

    private fun deck() = listOf(
        card("Plains", "Basic Land — Plains", null, 0.0, qty = 20, produces = "W"),
        card("Island", "Basic Land — Island", null, 0.0, qty = 4, produces = "U"),
        card("Soldier", "Creature — Human Soldier", "{W}", 1.0, qty = 6),
        card("Knight", "Creature — Human Knight", "{1}{W}", 2.0, qty = 8),
        card("Angel", "Creature — Angel", "{4}{W}{W}", 6.0, qty = 2, text = "Create a 1/1 white Soldier creature token."),
        card("Wrath", "Sorcery", "{2}{W}{W}", 4.0, rarity = "mythic"),
        card("Mystery", null, null, null, qty = 3),
    )

    private fun render(width: Int): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { DeckStatsPanel(DeckAnalysis.of(deck())) }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun styled() = Stylesheet.applied()

    @Test
    fun everySectionIsDrawn() = runTest {
        val frame = render(900)
        settle()
        val titles = frame.all("div.stats-card h3").map { it.textContent.orEmpty() }
        // Tokens are real cards below the deck list now, not a
        // guess from the rules text in a panel up here.
        assertEquals(listOf("Mana curve", "Colour", "Card types", "Creature types", "Rarity"), titles)
        assertTrue(frame.all("div.figure").size >= 5, "no headline numbers")
    }

    @Test
    fun theTallestColumnFillsTheChartAndTheRestAreToScale() = runTest {
        val frame = render(900)
        settle()
        if (!styled()) return@runTest
        val bars = frame.all("div.curve .bar")
        assertTrue(bars.isNotEmpty(), "no curve")
        val tallest = bars.maxOf { it.getBoundingClientRect().height }
        assertTrue(tallest > 40, "the tallest column is only ${tallest}px")
        // Eight of one-drop and six of two: the two-drop column has to
        // be visibly shorter, not merely a different number printed
        // over the same box.
        val one = bars[1].getBoundingClientRect().height
        val two = bars[2].getBoundingClientRect().height
        assertTrue(two > one, "6 at one mana drew taller than 8 at two: $one vs $two")
    }

    @Test
    fun twoColumnsOnlyDrawAlikeWhenTheyAreAlike() = runTest {
        // Fifteen, fifteen and seventeen all drew the same height.
        // The bar was a percentage of the whole column, number label
        // included, so anything near the top overflowed and flexbox
        // shrank every tall bar down to the same ceiling.
        val tall = listOf(
            card("Plains", "Basic Land — Plains", null, 0.0, qty = 37, produces = "W"),
            card("One", "Creature — Human", "{W}", 1.0, qty = 5),
            card("Two", "Creature — Human", "{1}{W}", 2.0, qty = 15),
            card("Three", "Creature — Human", "{2}{W}", 3.0, qty = 15),
            card("Four", "Creature — Human", "{3}{W}", 4.0, qty = 17),
        )
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "900px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { DeckStatsPanel(DeckAnalysis.of(tall)) }
        settle()
        if (!styled()) return@runTest

        val h = frame.all("div.curve .bar").map { it.getBoundingClientRect().height }
        // Columns are nought through seven, so one-drops are index 1.
        val (five, fifteen, alsoFifteen, seventeen) = listOf(h[1], h[2], h[3], h[4])
        assertEquals(fifteen, alsoFifteen, "two columns of fifteen drew differently")
        assertTrue(seventeen > fifteen + 3, "seventeen drew the same as fifteen: $seventeen vs $fifteen")
        assertTrue(fifteen > five + 20, "fifteen drew barely taller than five: $fifteen vs $five")
        // And to scale, not merely ordered.
        val scale = seventeen / 17.0
        assertTrue(kotlin.math.abs(fifteen / 15.0 - scale) < scale * 0.12, "the columns are not to scale: $h")
        assertTrue(kotlin.math.abs(five / 5.0 - scale) < scale * 0.12, "the columns are not to scale: $h")
    }

    @Test
    fun theCountSitsOnItsOwnBar() = runTest {
        // It was a row of numbers along the top of the chart, nowhere
        // near the column each one counted.
        val frame = render(900)
        settle()
        if (!styled()) return@runTest
        frame.all("div.curve .bar").forEach { bar ->
            val n = bar.querySelector("div.n") as? HTMLElement ?: error("a bar has no count")
            if (n.textContent.orEmpty().isBlank()) return@forEach
            val b = bar.getBoundingClientRect()
            val r = n.getBoundingClientRect()
            assertTrue(r.bottom <= b.top + 1, "the count is not above its bar")
            assertTrue(b.top - r.bottom < 10, "the count floated ${b.top - r.bottom}px off its bar")
            // And over the column it counts, not over a neighbour.
            assertTrue(kotlin.math.abs((r.left + r.right) / 2 - (b.left + b.right) / 2) < 2)
        }
    }

    @Test
    fun theTallestBarsCountIsStillOnTheChart() = runTest {
        val frame = render(900)
        settle()
        if (!styled()) return@runTest
        val chart = frame.all("div.curve").first().getBoundingClientRect()
        val tallest = frame.all("div.curve .bar").maxByOrNull { it.getBoundingClientRect().height }!!
        val n = (tallest.querySelector("div.n") as HTMLElement).getBoundingClientRect()
        assertTrue(n.top >= chart.top - 1, "the tallest bar's count is clipped off the top")
    }

    @Test
    fun noColumnOverflowsTheChartItIsIn() = runTest {
        val frame = render(900)
        settle()
        if (!styled()) return@runTest
        // The first `.curve` is the chart; the second is the row of
        // labels under it, and its columns are not in this one.
        val chartEl = frame.all("div.curve").first()
        val chart = chartEl.getBoundingClientRect()
        chartEl.all("div.col").forEach { col ->
            val r = col.getBoundingClientRect()
            assertTrue(r.top >= chart.top - 1, "a column grew out of the top of the chart")
            assertTrue(r.bottom <= chart.bottom + 1, "a column grew out of the bottom")
        }
    }

    @Test
    fun theCurveLeavesTheLandsOut() = runTest {
        // Twenty-four lands in this deck. As nought-drops they would
        // be the whole chart.
        val frame = render(900)
        settle()
        val zero = frame.all("div.curve .col").first()
        assertEquals("", zero.all("div.n").first().textContent?.trim())
    }

    @Test
    fun eachColourShowsWhatItNeedsAgainstWhatItMakes() = runTest {
        val frame = render(900)
        settle()
        val rows = frame.all("div.mana-row")
        // White and blue, and nothing for the three the deck does not touch.
        assertEquals(
            listOf("{W}", "{U}"),
            rows.map { (it.querySelector("img.mana-sym") as HTMLElement).getAttribute("alt").orEmpty() },
        )
        assertEquals(2, rows.first().all("div.mana-track").size, "needs and makes are two bars")
    }

    @Test
    fun aColourWithNoSourceIsSaidOutLoud() = runTest {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            DeckStatsPanel(
                DeckAnalysis.of(
                    listOf(
                        card("Plains", "Land", null, 0.0, produces = "W"),
                        card("Counterspell", "Instant", "{U}{U}", 2.0),
                    ),
                ),
            )
        }
        settle()
        assertTrue(frame.textContent.orEmpty().contains("No source for Blue"), frame.textContent.orEmpty())
    }

    @Test
    fun theCardsNobodyOwnsAreNamedRatherThanFoldedIn() = runTest {
        val frame = render(900)
        settle()
        assertTrue(
            frame.textContent.orEmpty().contains("3 cards in this list have no printing"),
            frame.textContent.orEmpty().takeLast(200),
        )
    }

    @Test
    fun eachColourSplitIsAlsoDrawnAsARing() = runTest {
        // A bar says how many white pips; a ring says what share of
        // the deck's colour is white, which is the question you ask
        // about a splash.
        val frame = render(900)
        settle()
        val pies = frame.all("div.pie")
        assertEquals(3, pies.size, "one for needs, one for makes, one for exact combinations")
        pies.forEach { pie ->
            val bg = pie.getAttribute("style").orEmpty()
            assertTrue(bg.contains("conic-gradient"), "the ring has no slices: $bg")
        }
        val caps = frame.all("span.pie-cap").map { it.textContent.orEmpty() }
        assertTrue(caps.any { it.startsWith("Needs") }, caps.toString())
        assertTrue(caps.any { it.startsWith("Sources per Colour") }, caps.toString())
        assertTrue(caps.any { it.startsWith("Mana Production") }, caps.toString())
        assertTrue(caps.none { it.startsWith("Exactly") || it.startsWith("Makes") }, "a ring still has its old name: " + caps)
    }

    /** The ring whose caption starts with this, measured. */
    private fun HTMLElement.ring(caption: String) = all("div.pie-set")
        .firstOrNull { it.querySelector(".pie-cap")?.textContent.orEmpty().startsWith(caption) }
        ?.querySelector(".pie")?.unsafeCast<HTMLElement>()
        ?.getBoundingClientRect()

    @Test
    fun needsAndMakesShareARowAndExactlyHasALineOfItsOwnBelowThem() = runTest {
        // Matt: "I want the 'exact' chart to be on its own line and
        // bigger than the others".
        assertTrue(styled(), "app.css never loaded, so the layout proves nothing")
        val frame = render(900)
        settle()
        val needs = frame.ring("Needs")
        val makes = frame.ring("Sources per Colour")
        val exactly = frame.ring("Mana Production")
        assertTrue(needs != null && makes != null && exactly != null, "expected three rings")
        assertTrue(abs(needs.top - makes.top) < 1, "needs and makes are not in one row: ${needs.top} vs ${makes.top}")
        assertTrue(
            exactly.top >= needs.bottom,
            "the Exactly ring starts at ${exactly.top}, beside needs and makes rather than below them (they end at ${needs.bottom})",
        )
        // Nothing else on its line: it is the only ring between its own
        // top and bottom.
        val beside = frame.all("div.pie").filter {
            val r = it.getBoundingClientRect()
            r.top < exactly.bottom && r.bottom > exactly.top
        }
        assertEquals(1, beside.size, "the Exactly ring shares its line with another ring")
    }

    @Test
    fun theExactlyRingIsBiggerThanTheOtherTwo() = runTest {
        assertTrue(styled(), "app.css never loaded, so the layout proves nothing")
        val frame = render(900)
        settle()
        val needs = frame.ring("Needs")
        val exactly = frame.ring("Mana Production")
        assertTrue(needs != null && exactly != null, "expected the needs and exactly rings")
        assertTrue(
            exactly.width > needs.width * 1.25,
            "the Exactly ring is ${exactly.width}px across and needs is ${needs.width}px, so it is not bigger",
        )
    }

    @Test
    fun theColourPanelHasNoCaptionUnderTheRings() = runTest {
        // Matt: "Get rid of this text under the charts".
        val frame = render(900)
        settle()
        val text = frame.textContent.orEmpty()
        assertTrue(!text.contains("Pips the deck asks for"), "the caption about pips is still under the rings")
        assertTrue(!text.contains("Hybrid pips count for both halves"), "the hybrid sentence is still under the rings")
    }

    @Test
    fun aDualLandIsItsOwnSliceInTheExactlyRing() = runTest {
        // Matt: "a UR slice would ONLY account for cards that produce
        // EXACTLY UR". Five Mountains, three Islands, two Steam Vents:
        // the makes ring says 7 red and 5 blue; this one says 5 only
        // red, 3 only blue and 2 both.
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "900px"
        document.body!!.appendChild(frame)
        roots += frame
        val cards = listOf(
            card("Mountain", "Basic Land — Mountain", null, 0.0, qty = 5, produces = "R"),
            card("Island", "Basic Land — Island", null, 0.0, qty = 3, produces = "U"),
            card("Steam Vents", "Land", null, 0.0, qty = 2, produces = "UR"),
        )
        renderComposable(root = frame) { DeckStatsPanel(DeckAnalysis.of(cards)) }
        settle()
        val set = frame.all("div.pie-set")
            .firstOrNull { it.querySelector(".pie-cap")?.textContent.orEmpty().startsWith("Mana Production") }
        assertTrue(set != null, "there is no Exactly ring at all")
        assertEquals("Mana Production · 10", set.querySelector(".pie-cap")?.textContent)
        val title = set.querySelector(".pie")?.getAttribute("title").orEmpty()
        assertEquals("U 30%, R 50%, UR 20%", title)
    }

    @Test
    fun aCombinationSliceIsOneBlendedColour() = runTest {
        // Matt: "Slice 3 should be ONE COLOR. Not multiple colors. Do
        // whatever the color mix of the 2 colors is, like purple". The
        // UR slice runs 80% to 100%, and everything the browser paints
        // there is blue and red mixed: 61A3DD and C47063 is 938AA0.
        assertTrue(styled(), "app.css never loaded, so the computed style proves nothing")
        val frame = izzet()
        val pie = frame.all("div.pie-set.wide .pie").singleOrNull()
        assertTrue(pie != null, "there is no Exactly ring at all")
        val painted = window.getComputedStyle(pie).backgroundImage
        val stops = Regex("""(rgba?\([^)]*\))((?:\s+[\d.]+%)+)""").findAll(painted).map { m ->
            m.groupValues[1].replace(" ", "") to
                Regex("""[\d.]+""").findAll(m.groupValues[2]).map { it.value.toDouble() }.toList()
        }.toList()
        assertTrue(stops.isNotEmpty(), "the ring paints no colour stops at all: $painted")
        val inIzzet = stops.filter { (_, at) -> at.any { it > 80.0 } }.map { it.first }.distinct()
        assertEquals(
            listOf("rgb(147,138,160)"),
            inIzzet,
            "the Izzet slice is not painted one blend of blue and red: $painted",
        )
    }

    @Test
    fun theExactlyTablesColumnsLineUp() = runTest {
        // Matt: "The table columns don't line up". Measured off the
        // text the browser lays out, not the cells: a name starts at
        // the same x on every row, and a count and a share end at the
        // same x on every row, whatever symbols sit before them.
        assertTrue(styled(), "app.css never loaded, so the layout proves nothing")
        val frame = izzet()
        val rows = frame.all("div.pie-set.wide table.pie-table tr")
        assertEquals(3, rows.size, "the Exactly table has no rows, so this proves nothing")
        fun text(td: HTMLElement): org.w3c.dom.DOMRect {
            val range = document.createRange()
            range.selectNodeContents(td)
            return range.getBoundingClientRect()
        }
        val cells = rows.map { it.all("td") }
        val names = cells.map { text(it[2]).left }
        val counts = cells.map { text(it[3]).right }
        val shares = cells.map { text(it[4]).right }
        assertTrue(names.max() - names.min() < 1, "the names start at different places: $names")
        assertTrue(counts.max() - counts.min() < 1, "the counts end at different places: $counts")
        assertTrue(shares.max() - shares.min() < 1, "the shares end at different places: $shares")
    }

    /** Five Mountains, three Islands, two Steam Vents, drawn. */
    private suspend fun izzet(): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "900px"
        document.body!!.appendChild(frame)
        roots += frame
        val cards = listOf(
            card("Mountain", "Basic Land — Mountain", null, 0.0, qty = 5, produces = "R"),
            card("Island", "Basic Land — Island", null, 0.0, qty = 3, produces = "U"),
            card("Steam Vents", "Land", null, 0.0, qty = 2, produces = "UR"),
        )
        renderComposable(root = frame) { DeckStatsPanel(DeckAnalysis.of(cards)) }
        settle()
        return frame
    }

    @Test
    fun everyExactlySliceHasItsNumberDrawnInsideIt() = runTest {
        // Matt: "It's not at all clear which slice is which in the
        // exactly chart" and "put symbols on the slices". Blue runs
        // 0-108 degrees, red 108-288, and UR 288-360 — a band of blue
        // then red, so its blue half sits against nothing that says
        // it is not more red. The number on it does.
        assertTrue(styled(), "app.css never loaded, so the layout proves nothing")
        val frame = izzet()
        val pie = frame.all("div.pie-set.wide .pie").singleOrNull()
        assertTrue(pie != null, "there is no Exactly ring at all")
        val box = pie.getBoundingClientRect()
        val cx = box.left + box.width / 2
        val cy = box.top + box.height / 2
        val marks = pie.all(".slice-no")
        assertEquals(listOf("1", "2", "3"), marks.map { it.textContent.orEmpty().trim() }, "the slices are not numbered on the ring")
        val spans = listOf(0.0 to 108.0, 108.0 to 288.0, 288.0 to 360.0)
        marks.zip(spans).forEach { (mark, span) ->
            val r = mark.getBoundingClientRect()
            val dx = r.left + r.width / 2 - cx
            val dy = r.top + r.height / 2 - cy
            val angle = (kotlin.math.atan2(dx, -dy) * 180 / kotlin.math.PI + 360) % 360
            assertTrue(
                angle > span.first && angle < span.second,
                "slice ${mark.textContent}'s number sits at $angle degrees, outside its slice ${span.first}-${span.second}",
            )
            assertTrue(
                kotlin.math.sqrt(dx * dx + dy * dy) < box.width / 2,
                "slice ${mark.textContent}'s number is off the ring",
            )
        }
    }

    @Test
    fun aTableUnderTheExactlyRingSaysWhatEachNumberIs() = runTest {
        // Matt: "This needs a better legend ... perhaps a table".
        val frame = izzet()
        val rows = frame.all("div.pie-set.wide table.pie-table tr").map { tr ->
            tr.all("td").joinToString(" | ") { td ->
                td.all("img.mana-sym").joinToString("") { it.getAttribute("alt").orEmpty() } +
                    td.textContent.orEmpty().trim()
            }
        }
        assertEquals(
            listOf(
                "1 | {U} | Mono-blue | 3 | 30%",
                "2 | {R} | Mono-red | 5 | 50%",
                "3 | {U}{R} | Izzet | 2 | 20%",
            ),
            rows,
            "the Exactly ring has no table naming each numbered slice",
        )
    }

    @Test
    fun theExactlyRingIsBigEnoughToReadItsNumbers() = runTest {
        // Matt: "You can make the chart bigger".
        assertTrue(styled(), "app.css never loaded, so the layout proves nothing")
        val frame = render(900)
        settle()
        val exactly = frame.ring("Mana Production")
        assertTrue(exactly != null, "there is no Exactly ring at all")
        assertTrue(exactly.width >= 170, "the Exactly ring is ${exactly.width}px across, no bigger than before")
    }

    @Test
    fun aRingWithNothingInItIsNotDrawn() = runTest {
        // A colourless deck has no colour split to show.
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            DeckStatsPanel(
                DeckAnalysis.of(listOf(card("Sol Ring", "Artifact", "{1}", 1.0))),
            )
        }
        settle()
        assertEquals(0, frame.all("div.pie").size)
    }

    @Test
    fun nothingRunsOffTheEdgeAtPhoneWidth() = runTest {
        val frame = render(390)
        settle()
        if (!styled()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.all("*")
            .filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
        assertTrue(over.isEmpty(), "off the right edge: $over")
    }

    @Test
    fun theColoursAreNotWashedOut() = runTest {
        // The complaint was pastel. Every pip has to stand off the
        // background it is drawn on, and a saturated one does.
        val frame = render(900)
        settle()
        if (!styled()) return@runTest
        frame.all("span.sym").forEach { sym ->
            val bg = window.getComputedStyle(sym).backgroundColor
            val rgb = Regex("""\d+""").findAll(bg).map { it.value.toInt() }.toList()
            assertTrue(rgb.size >= 3, "no background on a colour pip: $bg")
            val spread = rgb.take(3).max() - rgb.take(3).min()
            val bright = rgb.take(3).max()
            assertTrue(
                spread >= 60 || bright >= 200,
                "${sym.textContent} is washed out: $bg",
            )
        }
    }
}
