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
        assertEquals(listOf("Mana curve", "Colour", "Card types", "Rarity"), titles)
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
        assertTrue(caps.any { it.startsWith("Makes") }, caps.toString())
        assertTrue(caps.any { it.startsWith("Exactly") }, caps.toString())
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
            .firstOrNull { it.querySelector(".pie-cap")?.textContent.orEmpty().startsWith("Exactly") }
        assertTrue(set != null, "there is no Exactly ring at all")
        assertEquals("Exactly · 10", set.querySelector(".pie-cap")?.textContent)
        // Every slice is named by its symbols, so a dual reads {U}{R}
        // rather than being told apart by hue.
        val keys = set.all(".pie-key .k").map { k ->
            k.all("img.mana-sym").joinToString("") { it.getAttribute("alt").orEmpty() } +
                " " + k.textContent.orEmpty().trim()
        }
        assertEquals(listOf("{U} 30%", "{R} 50%", "{U}{R} 20%"), keys, "the key should name each exact combination")
        val title = set.querySelector(".pie")?.getAttribute("title").orEmpty()
        assertEquals("U 30%, R 50%, UR 20%", title)
        // The UR slice runs 80% to 100%, and is drawn as a band of
        // blue and a band of red rather than a colour of its own.
        val bg = set.querySelector(".pie")?.getAttribute("style").orEmpty()
        assertTrue(bg.contains("var(--u) 80% 90%") && bg.contains("var(--r) 90% 100%"), "the UR slice is not drawn in its two colours: $bg")
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
