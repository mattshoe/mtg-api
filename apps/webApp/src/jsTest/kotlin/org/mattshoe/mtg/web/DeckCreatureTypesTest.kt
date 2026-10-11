package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The deck's creature types, on the real deck page.
 *
 * Matt: "a human warrior would add 1 to human and 1 to warrior. There
 * would be no 'human warrior' type". Most common first, so the tribe
 * the deck is really built around is the top bar.
 */
class DeckCreatureTypesTest {

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

    private fun card(name: String, type: String, qty: Int) = DeckCard(
        name, qty, null, qty,
        nameNorm = name.lowercase(), typeLine = type, manaCost = "{1}{G}", cmc = 2.0, colorIdentity = "G",
    )

    private fun opened() = DecksState()
        .loaded(listOf(Deck("a", "Elves", "matt", "Lathril, Blade of the Elves (KHC) 2", "BG", 3, null)))
        .opened(
            "a",
            listOf(
                card("Elf Druid", "Creature — Elf Druid", 4),
                card("Elf Warrior", "Creature — Elf Warrior", 2),
                card("Human Warrior", "Creature — Human Warrior", 1),
                card("Forest", "Basic Land — Forest", 10),
            ),
        )

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun mount(): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        root.style.width = "900px"
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { DecksPage(opened(), {}, {}) }
        return root
    }

    private fun tribes(root: HTMLElement): HTMLElement? =
        root.all("div.stats-card").firstOrNull { it.querySelector("h3")?.textContent == "Creature types" }

    @Test
    fun eachCreatureTypeIsItsOwnBarMostCommonFirst() = runTest {
        val root = mount()
        settle()
        val card = assertNotNull(tribes(root), "the deck page has no Creature types chart")
        val rows = card.all("div.hbar").map {
            (it.querySelector(".k")?.textContent.orEmpty()) to (it.querySelector(".v")?.textContent.orEmpty())
        }
        assertEquals(
            listOf("Elf" to "6", "Druid" to "4", "Warrior" to "3", "Human" to "1"),
            rows,
            "a Human Warrior is one Human and one Warrior, counted by copies, most first",
        )
        assertTrue(rows.none { it.first.contains(" ") }, "a combined type like \"Human Warrior\" is a bar")
    }

    @Test
    fun theMostCommonTypeDrawsTheLongestBar() = runTest {
        val root = mount()
        settle()
        if (!Stylesheet.applied()) return@runTest
        val widths = tribes(root)!!.all("div.hbar .fill").map { it.getBoundingClientRect().width }
        assertEquals(4, widths.size, "expected a bar for each of four types")
        assertTrue(widths[0] > widths[1] && widths[1] > widths[2] && widths[2] > widths[3], "bars not to scale: $widths")
    }
}
