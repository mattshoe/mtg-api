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
        // The whole tile is the target now, the way the hand-written
        // grid had it — there is no separate Open button to aim at.
        (root.querySelector(".deck-card") as HTMLElement).click()
        settle()
        assertEquals("a", opened?.slug)
    }

    @Test
    fun aDeckTileCanBeReachedAndOpenedFromTheKeyboard() = runTest {
        val root = mount { DecksPage(DecksState().loaded(listOf(deck("a", "matt", "Alela"))), { opened = it }, {}) }
        settle()
        val tile = root.querySelector(".deck-card") as HTMLElement
        // The grid is the only way into a deck, so a tile that Tab
        // cannot reach locks a keyboard user out of the whole app. The
        // card row below it already carries all three.
        assertEquals("button", tile.getAttribute("role"), "no role, so a screen reader reads a div")
        assertEquals("0", tile.getAttribute("tabindex"), "not in the tab order")
        // Enter, the way a real keypress arrives, rather than calling
        // the handler directly — a `tabindex` with no key handler is
        // the more likely half to be missing.
        tile.dispatchEvent(
            org.w3c.dom.events.KeyboardEvent(
                "keydown",
                org.w3c.dom.events.KeyboardEventInit(key = "Enter", bubbles = true),
            ),
        )
        settle()
        assertEquals("a", opened?.slug, "Enter on a focused tile did not open the deck")
    }

    @Test
    fun aDeckDetailCountsCardsAndFlagsWhatIsMissing() = runTest {
        val s = DecksState().loaded(listOf(deck("a", "matt", "Alela"))).opened(
            "a",
            listOf(DeckCard("Sol Ring", 1, null, 1), DeckCard("Mana Crypt", 1, null, 0)),
        )
        val root = mount { DecksPage(s, {}, {}) }
        settle()
        // The figures say it now, and they count copies rather than
        // distinct names — which is why the old tag saying "1 not
        // owned" sat next to a figure saying something else.
        // Uppercase on screen is `text-transform`, which the text
        // content does not carry.
        assertTrue(root.textContent!!.contains("2cards"), root.textContent!!.take(200))
        assertTrue(root.textContent!!.contains("1not owned"))
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
        // Grouped, through the shared `Prices.money` — this test used
        // to pin the web's own bug, asserting the raw `$5046` that
        // `Prices.money` would have caught.
        assertTrue(root.textContent!!.contains("$5,046"))
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
    fun theStatsScopeSwitcherIsASegmentedControl() = runTest {
        val root = mount { StatsPage(StatsState().scopedTo(Owner.MATT).loaded(Totals())) { } }
        settle()
        // `owner-opt` is the new-deck wizard's 130px-minimum choice
        // card. Three of them wrap onto two rows on a phone, which is
        // why `FilterPanel` was moved off it and why the web's own test
        // asserts it stays out of the rest of the app. Stats was the
        // last place still using it.
        assertEquals(0, root.querySelectorAll(".owner-opt").length)
        val seg = root.querySelector(".seg")
        assertTrue(seg != null, "the scope switcher is not a .seg")
        assertEquals(3, root.querySelectorAll(".seg button").length)
        // Inside the control, not merely somewhere on the page.
        val inSeg = root.querySelectorAll(".seg button")
        val labels = (0 until inSeg.length).map { (inSeg[it] as org.w3c.dom.HTMLElement).textContent }
        assertEquals(listOf("Both", "Matt", "Kayla"), labels)
    }

    @Test
    fun anUnpricedCollectionSaysSoRatherThanShowingZero() = runTest {
        val root = mount { StatsPage(StatsState().loaded(Totals(value = null))) { } }
        settle()
        assertTrue(root.textContent!!.contains("unpriced"))
    }
}
