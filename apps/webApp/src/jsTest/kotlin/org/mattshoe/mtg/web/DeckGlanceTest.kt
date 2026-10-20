package org.mattshoe.mtg.web

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck at a glance, on the real deck page.
 *
 * Matt: "i don't like this 'tile in tile' layout in the deck at a
 * glance section ... i want it to be sleek and stylish". The headline
 * numbers were cells with seams between them and every chart a
 * bordered cell, all inside the bordered panel. Now they stand on the
 * panel. And: "i also want to see median, mode for mana cost". At a
 * phone's size and both tablet sizes. Android's sibling is
 * `DeckGlanceParityTest`.
 */
class DeckGlanceTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(name: String, type: String, cost: String?, cmc: Double, qty: Int, role: String? = null) =
        DeckCard(
            name, qty, role, qty,
            nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc, colorIdentity = "G",
            price = 1.0,
        )

    /** Spells at 1, 1, 2, 2, 2, 2, 2, 3: average 1.88, median 2, mode 2. */
    private fun opened() = DecksState()
        .loaded(listOf(Deck("a", "Gruul", "matt", "Leader (KHC) 2", "RG", 18, null)))
        .opened(
            "a",
            listOf(
                card("Leader", "Legendary Creature — Elf", "{2}{G}", 3.0, 1, role = "commander"),
                card("Bear", "Creature — Bear", "{1}{G}", 2.0, 3),
                card("Bolt", "Instant", "{R}", 1.0, 2),
                card("Gold", "Sorcery", "{R}{G}", 2.0, 1),
                card("Rock", "Artifact", "{2}", 2.0, 1),
                card("Forest", "Basic Land — Forest", null, 0.0, 10),
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

    private suspend fun check(size: Pair<Int, Int>) {
        val doc = mount(size)
        val w = screen!!.window
        val figures = doc.all(".figure")
        val said = figures.map { "${it.querySelector(".n")?.textContent} ${it.querySelector(".k")?.textContent}" }
        assertEquals(
            listOf("18 cards", "10 lands · 56%", "8 spells", "1.88 avg mana", "2 median mana", "2 mode mana", "\$18 value"),
            said,
            "the headline numbers are not the deck's, with median and mode beside the average",
        )

        val row = doc.querySelector(".figure")!!.parentElement!!.unsafeCast<HTMLElement>()
        assertEquals(
            "rgba(0, 0, 0, 0)", w.getComputedStyle(row).backgroundColor,
            "the row of numbers paints a background through gaps between them: a seam around each, a tile in a tile",
        )
        figures.forEach { f ->
            val told = f.textContent
            val box = f.getBoundingClientRect()
            val n = f.querySelector(".n")!!.getBoundingClientRect()
            val inset = maxOf(abs(n.left - box.left), abs(n.top - box.top))
            assertTrue(inset < 1.0, "\"$told\" is a tile inside the panel: its number sits ${inset}px inside a frame of its own")
            assertEquals(
                "rgba(0, 0, 0, 0)", w.getComputedStyle(f).backgroundColor,
                "\"$told\" is painted as a cell of its own",
            )
            assertTrue(box.right <= w.innerWidth + 0.5, "\"$told\" runs off the right of the screen")
        }
        doc.all(".stats-card").forEach { c ->
            val style = w.getComputedStyle(c)
            val title = c.querySelector("h3")?.textContent
            assertEquals("0px", style.borderRightWidth, "the $title chart is a bordered cell inside the panel")
            assertEquals("0px", style.borderTopWidth, "the $title chart is a bordered cell inside the panel")
        }
        assertTrue(doc.all(".stats-card").isNotEmpty(), "the deck page drew no charts, so this proves nothing about them")
    }

    @Test
    fun onAPhoneTheNumbersStandOnThePanelWithMedianAndMode() = runTest { check(390 to 844) }

    @Test
    fun onAFoldingPhoneOpenedOut() = runTest { check(Tablet.UNFOLDED) }

    @Test
    fun onATabletHeldSideways() = runTest { check(Tablet.LANDSCAPE) }
}
