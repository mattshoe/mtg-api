package org.mattshoe.mtg.web

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mana curve's dropdown, on the real deck page.
 *
 * Matt: "i want a dropdown to select what my mana curve graph shows.
 * it defaults to all cards ... card type, color ... this will mean
 * showing numerous graphs at once". At a phone's size and both tablet
 * sizes. Android's sibling is `DeckCurveSplitParityTest`.
 */
class DeckCurveSplitTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(name: String, type: String, cost: String?, cmc: Double, qty: Int, ci: String, role: String? = null) =
        DeckCard(
            name, qty, role, qty,
            nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc, colorIdentity = ci,
        )

    private fun opened() = DecksState()
        .loaded(listOf(Deck("a", "Gruul", "matt", "Leader (KHC) 2", "RG", 18, null)))
        .opened(
            "a",
            listOf(
                card("Leader", "Legendary Creature — Elf", "{2}{G}", 3.0, 1, "G", role = "commander"),
                card("Bear", "Creature — Bear", "{1}{G}", 2.0, 3, "G"),
                card("Bolt", "Instant", "{R}", 1.0, 2, "R"),
                card("Gold", "Sorcery", "{R}{G}", 2.0, 1, "RG"),
                card("Rock", "Artifact", "{2}", 2.0, 1, ""),
                card("Forest", "Basic Land — Forest", null, 0.0, 10, "G"),
            ),
        )

    private suspend fun mount(size: Pair<Int, Int>): org.w3c.dom.Document {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) { DecksPage(opened(), {}) }
        settle(s.window)
        return s.frame.contentDocument!!
    }

    private fun org.w3c.dom.Document.all(sel: String): List<HTMLElement> {
        val f = querySelectorAll(sel)
        return (0 until f.length).map { f.item(it)!!.unsafeCast<HTMLElement>() }
    }

    private fun org.w3c.dom.Document.curveCard(): HTMLElement = assertNotNull(
        all(".stats-card").firstOrNull { it.querySelector("h3")?.textContent == "Mana curve" },
        "the deck page has no Mana curve chart",
    )

    private fun org.w3c.dom.Document.picker(): HTMLSelectElement = assertNotNull(
        curveCard().querySelector("select"),
        "the Mana curve chart has no dropdown",
    ).unsafeCast<HTMLSelectElement>()

    /** Each graph's title and its non-empty columns, as "label=count". */
    private fun org.w3c.dom.Document.graphs(): List<Pair<String, List<String>>> =
        curveCard().querySelectorAll(".curve-chart").let { n ->
            (0 until n.length).map { i ->
                val g = n.item(i)!!.unsafeCast<HTMLElement>()
                val title = g.querySelector(".curve-title")?.textContent.orEmpty()
                val counts = g.querySelectorAll(".curve .col").let { c ->
                    (0 until c.length).mapNotNull { j ->
                        val col = c.item(j)!!.unsafeCast<HTMLElement>()
                        val v = col.querySelector(".n")?.textContent.orEmpty()
                        if (v.isEmpty()) null else "${col.getAttribute("data-mv")}=$v"
                    }
                }
                title to counts
            }
        }

    private suspend fun org.w3c.dom.Document.pick(slug: String) {
        val p = picker()
        p.value = slug
        p.dispatchEvent(Event("change"))
        settle(screen!!.window)
    }

    @Test
    fun theDropdownOffersAllCardsCardTypeAndColourAndStartsOnAllCards() = runTest {
        val doc = mount(390 to 844)
        val p = doc.picker()
        val options = (0 until p.options.length).map { p.options.item(it)!!.textContent }
        assertEquals(listOf("All cards", "Card type", "Colour"), options)
        assertEquals("all", p.value, "the curve does not start on all cards")
        assertEquals(1, doc.graphs().size, "all cards is one graph, drew ${doc.graphs().map { it.first }}")
    }

    @Test
    fun cardTypeDrawsOneCurvePerTypeWithNoLands() = runTest {
        val doc = mount(390 to 844)
        doc.pick("type")
        assertEquals(
            listOf(
                "Creatures" to listOf("2=3", "3=1"),
                "Instants" to listOf("1=2"),
                "Sorceries" to listOf("2=1"),
                "Artifacts" to listOf("2=1"),
            ),
            doc.graphs(),
        )
    }

    @Test
    fun colourDrawsOneCurvePerColourAndBackToAllCardsIsOneAgain() = runTest {
        val doc = mount(390 to 844)
        doc.pick("colour")
        assertEquals(
            listOf(
                "Red" to listOf("1=2", "2=1"),
                "Green" to listOf("2=4", "3=1"),
                "Colourless" to listOf("2=1"),
            ),
            doc.graphs(),
        )
        doc.pick("all")
        assertEquals(1, doc.graphs().size, "going back to all cards left ${doc.graphs().size} graphs")
    }

    /**
     * Every graph inside its panel, every column drawn, and one scale
     * across them: three creature two-drops stand three times as tall
     * as the sorcery's one.
     */
    private suspend fun fits(size: Pair<Int, Int>) {
        val doc = mount(size)
        doc.pick("type")
        val panel = doc.curveCard().getBoundingClientRect()
        val charts = doc.curveCard().querySelectorAll(".curve-chart").let { n ->
            (0 until n.length).map { n.item(it)!!.unsafeCast<HTMLElement>() }
        }
        assertEquals(4, charts.size, "on a ${size.first} by ${size.second} screen")
        charts.forEach { c ->
            val r = c.getBoundingClientRect()
            assertTrue(r.left >= panel.left - 0.5 && r.right <= panel.right + 0.5, "a graph runs out of its panel: ${r.left}..${r.right} in ${panel.left}..${panel.right}")
            assertTrue(r.right <= size.first + 0.5, "a graph runs off a ${size.first} wide screen at ${r.right}")
        }
        fun bar(chart: Int, mv: Int) =
            charts[chart].querySelector(".curve .col[data-mv='$mv'] .bar")!!.unsafeCast<HTMLElement>().getBoundingClientRect().height
        val creatures = bar(0, 2)
        val sorcery = bar(2, 2)
        assertTrue(creatures > 30, "the tallest column is only ${creatures}px")
        assertTrue(
            kotlin.math.abs(creatures / sorcery - 3.0) < 0.25,
            "the graphs are not on one scale: 3 creatures drew ${creatures}px, 1 sorcery ${sorcery}px",
        )
    }

    @Test
    fun theGraphsFitAPhone() = runTest { fits(390 to 844) }

    @Test
    fun theGraphsFitAFoldingPhoneOpenedOut() = runTest { fits(Tablet.UNFOLDED) }

    @Test
    fun theGraphsFitATabletHeldSideways() = runTest { fits(Tablet.LANDSCAPE) }
}
