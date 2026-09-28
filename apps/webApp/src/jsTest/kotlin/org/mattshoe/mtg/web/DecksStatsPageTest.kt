package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.StatsState
import org.mattshoe.mtg.core.Totals
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decks and Stats in headless Chrome, driven through the shared state. */
class DecksStatsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var opened: Deck? = null
    private var scoped: Owner? = null
    private var scopeCalls = 0

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        opened = null
        scoped = null
        scopeCalls = 0
    }

    private fun mount(block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { block() }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    @Test
    fun decksAreGroupedByOwner() = runTest {
        val s = DecksState().loaded(listOf(deck("a", "matt", "Alela"), deck("b", "kayla", "Bello")))
        val root = mount { DecksPage(s, { opened = it }, {}) }
        settle()
        assertTrue(root.textContent!!.contains("Matt"))
        assertTrue(root.textContent!!.contains("Kayla"))
        assertTrue(root.textContent!!.contains("Alela"))
    }

    @Test
    fun aTileShowsTheCommanderWithoutItsSetAnnotation() = runTest {
        val root = mount { DecksPage(DecksState().loaded(listOf(deck("a", "matt"))), {}, {}) }
        settle()
        assertTrue(root.textContent!!.contains("Alela, Artful Provocateur"))
        assertTrue(!root.textContent!!.contains("(ELD)"), "the set annotation is noise on a tile")
    }

    @Test
    fun openingADeckAsksThroughTheCallback() = runTest {
        val root = mount { DecksPage(DecksState().loaded(listOf(deck("a", "matt", "Alela"))), { opened = it }, {}) }
        settle()
        root.buttons().first { it.textContent == "Open" }.click()
        settle()
        assertEquals("a", opened?.slug)
    }

    @Test
    fun aDeckDetailCountsCardsAndFlagsWhatIsMissing() = runTest {
        val s = DecksState().loaded(listOf(deck("a", "matt", "Alela"))).opened(
            "a",
            listOf(DeckCard("Sol Ring", 1, null, 1), DeckCard("Mana Crypt", 1, null, 0)),
        )
        val root = mount { DecksPage(s, {}, {}) }
        settle()
        assertTrue(root.textContent!!.contains("2 cards"))
        assertTrue(root.textContent!!.contains("1 not owned"))
        assertTrue(root.textContent!!.contains("has 0"))
    }

    @Test
    fun statsShowTheTotals() = runTest {
        val s = StatsState().loaded(
            Totals(printings = 6032, uniques = 3481, physical = 3743, decks = 24, value = 5046.0),
        )
        val root = mount { StatsPage(s) { } }
        settle()
        assertTrue(root.textContent!!.contains("6032"))
        assertTrue(root.textContent!!.contains("3743"))
        assertTrue(root.textContent!!.contains("$5046"))
    }

    @Test
    fun theActiveScopeIsMarkedAndSwitchingAsksForTheOther() = runTest {
        val s = StatsState().scopedTo(Owner.MATT).loaded(Totals())
        val root = mount { StatsPage(s) { scoped = it; scopeCalls++ } }
        settle()
        val matt = root.buttons().first { it.textContent == "Matt" }
        assertTrue(matt.className.contains("on"), matt.className)
        root.buttons().first { it.textContent == "Kayla" }.click()
        settle()
        assertEquals(Owner.KAYLA, scoped)
        assertEquals(1, scopeCalls)
    }

    @Test
    fun anUnpricedCollectionSaysSoRatherThanShowingZero() = runTest {
        val root = mount { StatsPage(StatsState().loaded(Totals(value = null))) { } }
        settle()
        assertTrue(root.textContent!!.contains("unpriced"))
    }
}
