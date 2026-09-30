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
        assertEquals(2, pies.size, "one for needs, one for makes")
        pies.forEach { pie ->
            val bg = pie.getAttribute("style").orEmpty()
            assertTrue(bg.contains("conic-gradient"), "the ring has no slices: $bg")
        }
        val caps = frame.all("span.pie-cap").map { it.textContent.orEmpty() }
        assertTrue(caps.any { it.startsWith("Needs") }, caps.toString())
        assertTrue(caps.any { it.startsWith("Makes") }, caps.toString())
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
