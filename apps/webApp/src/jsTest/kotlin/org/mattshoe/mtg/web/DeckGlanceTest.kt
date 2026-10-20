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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The top of the deck at a glance, on the real deck page.
 *
 * Matt, after the borders came off and nothing else changed: "i asked
 * you to make the fucking at a glance view more stylish and attractive
 * and you just removed the borders!!!!". The numbers were one ragged
 * row at one size, wrapping wherever they ran out of room. Now they
 * are laid out: the cards and their worth big, lands against spells as
 * one split bar, and the mana value's average, median and mode in
 * three equal columns. At a phone's size and both tablet sizes.
 * Android's sibling is `DeckGlanceParityTest`.
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

    /** 10 lands and 8 spells, the spells at 1, 1, 2, 2, 2, 2, 2, 3: average 1.88, median 2, mode 2. */
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

    /** The figure whose title, its accessible name, is [told]. */
    private fun org.w3c.dom.Document.figure(told: String): HTMLElement =
        assertNotNull(all(".figure").firstOrNull { it.title == told }, "no figure \"$told\" on the deck page")

    private fun HTMLElement.said() = "${querySelector(".n")?.textContent} ${querySelector(".k")?.textContent}"

    private suspend fun check(size: Pair<Int, Int>) {
        val doc = mount(size)
        val w = screen!!.window
        fun px(e: HTMLElement) = w.getComputedStyle(e.querySelector(".n")!!.unsafeCast<HTMLElement>()).fontSize.removeSuffix("px").toDouble()

        // Lands against spells, one bar split by share.
        val bar = assertNotNull(
            doc.querySelector(".glance-split .split-bar")?.unsafeCast<HTMLElement>(),
            "no bar splitting the deck's lands from its spells",
        ).getBoundingClientRect()
        val spells = doc.querySelector(".glance-split .split-bar .spells")!!.getBoundingClientRect()
        val share = spells.width / bar.width
        assertTrue(abs(share - 8.0 / 18.0) < 0.02, "the spells take ${share * 100}% of the split bar, not 8 of 18 cards")
        assertEquals(
            listOf("8 spells", "10 lands · 56%"),
            doc.all(".glance-split .figure").map { it.said() },
            "the split bar's ends are not named",
        )

        // Avg, median and mode: three equal columns on one line, under one caption.
        val trio = listOf("1.88 avg mana", "2 median mana", "2 mode mana").map { doc.figure(it) }
        assertEquals(listOf("1.88 avg", "2 median", "2 mode"), trio.map { it.said() }, "the mana trio's labels")
        val a = trio.first().getBoundingClientRect()
        trio.drop(1).forEach { f ->
            val b = f.getBoundingClientRect()
            assertTrue(abs(b.top - a.top) < 1.0, "\"${f.title}\" is not on the line with the average")
            assertTrue(abs(b.width - a.width) < 1.0, "\"${f.title}\" is ${b.width}px wide and the average ${a.width}px: a ragged row, not three columns")
        }
        val spread = trio.last().getBoundingClientRect().right - a.left
        assertTrue(spread > bar.width * 0.9, "the mana trio spans ${spread}px of a ${bar.width}px panel: bunched to one side")

        // The cards and their worth are the headline, so they are big.
        val big = px(doc.figure("18 cards"))
        val mana = px(trio.first())
        assertTrue(big > mana * 1.25, "the deck's card count is set at ${big}px beside the average's ${mana}px: nothing leads")
        assertEquals(big, px(doc.figure("\$18 value")), "the deck's value is not set as big as its card count")

        doc.all(".figure").forEach { f ->
            assertTrue(f.getBoundingClientRect().right <= w.innerWidth + 0.5, "\"${f.title}\" runs off the right of the screen")
        }
        // Still no tile in a tile: the charts are sections of the panel.
        doc.all(".stats-card").forEach { c ->
            assertEquals("0px", w.getComputedStyle(c).borderRightWidth, "the ${c.querySelector("h3")?.textContent} chart is a bordered cell inside the panel")
        }
    }

    @Test
    fun theGlanceIsLaidOutOnAPhone() = runTest { check(390 to 844) }

    @Test
    fun theGlanceOnAFoldingPhoneOpenedOut() = runTest { check(Tablet.UNFOLDED) }

    @Test
    fun theGlanceOnATabletHeldSideways() = runTest { check(Tablet.LANDSCAPE) }
}
