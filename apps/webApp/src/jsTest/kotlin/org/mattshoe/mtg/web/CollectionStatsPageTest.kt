package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.StatCard
import org.mattshoe.mtg.core.StatTab
import org.mattshoe.mtg.core.Totals
import org.mattshoe.mtg.core.View
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Stats page's tabs, filters and charts, in the real `AppShell`.
 *
 * Matt: "the stats page is so barren and not useful. i want TONS of
 * data and i want to be able to filter on like colors and types ...
 * a tab for every card type ... pie charts, 'top' lists". The phone's
 * `CollectionStatsParityTest` is the same claims.
 *
 * Mounted in a `Tablet` frame at a phone's size and at both tablet
 * sizes, with the real stylesheet, and every claim is read off what
 * the browser laid out or the state the page wrote back.
 */
class CollectionStatsPageTest {

    private var screen: Tablet.Screen? = null
    private lateinit var held: MutableState<AppState>

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(
        name: String,
        qty: Int,
        type: String,
        identity: String,
        cmc: Double,
        rarity: String,
        price: Double?,
        set: String = "Bloomburrow",
    ) = StatCard(name, qty, false, cmc, type, identity, rarity, set, "2024", "Somebody", price)

    private val pile = listOf(
        card("Llanowar Elves", 4, "Creature — Elf Druid", "G", 1.0, "common", 0.25),
        card("Lightning Bolt", 3, "Instant", "R", 1.0, "uncommon", 2.0),
        card("Sol Ring", 2, "Artifact", "", 1.0, "uncommon", 3.0, set = "Commander"),
        card("Forest", 10, "Basic Land — Forest", "G", 0.0, "common", null),
        card("Atraxa, Praetors' Voice", 1, "Legendary Creature — Phyrexian Angel Horror", "WUBG", 4.0, "mythic", 30.0, set = "Commander"),
        card("Kolaghan's Command", 2, "Instant", "BR", 4.0, "uncommon", 0.5),
    )

    private fun onStats() = AppState(admin = Admin().signIn(Account("matt"), "t"))
        .navigate(Route(View.STATS))
        .let { it.copy(stats = it.stats.scopedTo("matt").loaded(Totals(printings = 6, physical = 22), pile)) }

    private suspend fun mount(size: Pair<Int, Int>): org.w3c.dom.Document {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            held = androidx.compose.runtime.remember { mutableStateOf(onStats()) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onTweak = { _, _ -> },
            )
        }
        settle(s.window)
        return s.frame.contentDocument!!
    }

    private fun org.w3c.dom.Document.all(sel: String): List<HTMLElement> {
        val f = querySelectorAll(sel)
        return (0 until f.length).map { f[it]!!.unsafeCast<HTMLElement>() }
    }

    private fun org.w3c.dom.Document.tab(title: String): HTMLElement =
        assertNotNull(all(".stats-tabs button").firstOrNull { it.textContent!!.startsWith(title) }, "no $title tab")

    private fun org.w3c.dom.Document.headings(): List<String> =
        all(".stats-card > h3").map { it.textContent!!.trim().lowercase() }

    @Test
    fun theLandingTabIsTheWholeCollectionAndEveryTypeWithCardsHasATab() = runTest {
        val doc = mount(390 to 844)
        val tabs = doc.all(".stats-tabs button").map { it.textContent!! }
        assertTrue(tabs.isNotEmpty(), "no tabs on the Stats page")
        assertTrue(tabs.first().startsWith("Everything"), "the first tab is ${tabs.first()}")
        assertTrue(tabs.first().contains("22"), "the landing tab does not say how many cards: ${tabs.first()}")
        assertTrue(tabs.any { it.startsWith("Instants") && it.contains("5") }, "no Instants tab with its five: $tabs")
        assertTrue(tabs.none { it.startsWith("Battles") }, "a tab with nothing in it: $tabs")
        assertEquals("true", doc.tab("Everything").getAttribute("aria-pressed"), "the landing tab is not the one on")
    }

    @Test
    fun theLandingPageHasEveryChart() = runTest {
        val doc = mount(390 to 844)
        val have = doc.headings()
        listOf(
            "colour", "colour identity", "card types", "mana curve", "rarity", "creature types",
            "price per card", "value by colour", "value by rarity", "top sets", "release years",
            "top artists", "most valuable",
        ).forEach { assertTrue(it in have, "no \"$it\" chart; the charts are $have") }
        // The rings are real rings: a conic gradient the browser resolved.
        val rings = doc.all(".stats-card .pie")
        assertTrue(rings.size >= 2, "expected the colour and identity rings, found ${rings.size}")
        val paint = screen!!.window.getComputedStyle(rings.first()).backgroundImage
        assertTrue(paint.contains("conic-gradient"), "the ring is not painted: $paint")
        assertTrue(doc.body!!.textContent!!.contains("Atraxa, Praetors' Voice"), "the most valuable card is not listed")
    }

    @Test
    fun aTabNarrowsEveryNumberToItsType() = runTest {
        val doc = mount(390 to 844)
        doc.tab("Instants").click()
        settle(screen!!.window)
        assertEquals(StatTab.INSTANT, held.value.stats.tab, "the tab was not picked")
        assertEquals("true", doc.tab("Instants").getAttribute("aria-pressed"))
        val copies = assertNotNull(doc.querySelector(".stats-figures .figure .n"), "no figures").textContent
        assertEquals("5", copies, "the first figure is not the Instants' five copies")
        assertTrue(!doc.body!!.textContent!!.contains("Llanowar Elves"), "a creature is still listed on the Instants tab")
    }

    @Test
    fun aColourNarrowsEveryNumberAndTheEmptyCaseSaysSo() = runTest {
        val doc = mount(390 to 844)
        val red = assertNotNull(doc.querySelector(".stats-filters button[aria-label='Red']"), "no red filter").unsafeCast<HTMLElement>()
        red.click()
        settle(screen!!.window)
        assertEquals("true", red.getAttribute("aria-pressed"), "the red filter does not say it is on")
        assertEquals("5", doc.querySelector(".stats-figures .figure .n")?.textContent, "red is Bolt and Command, five copies")
        // Red creatures: there are none.
        doc.tab("Creatures").click()
        settle(screen!!.window)
        assertTrue(
            doc.body!!.textContent!!.contains("No cards match"),
            "an empty filter draws nothing and says nothing",
        )
        val clear = assertNotNull(doc.querySelector(".stats-filters button[aria-label='Clear the filters']"), "no way back")
        clear.unsafeCast<HTMLElement>().click()
        settle(screen!!.window)
        assertTrue(held.value.stats.filter.isEmpty, "clearing did not clear")
        assertEquals("5", doc.querySelector(".stats-figures .figure .n")?.textContent, "the Creatures tab's five copies")
    }

    /** Nothing past the right edge, and on a wide screen the charts sit side by side. */
    private suspend fun fits(size: Pair<Int, Int>, columns: Int) {
        val doc = mount(size)
        val width = size.first.toDouble()
        val cards = doc.all(".stats-card")
        assertTrue(cards.size >= 10, "only ${cards.size} charts drawn")
        val wide = (doc.all(".stats-card") + doc.all(".stats-tabs") + doc.all(".stats-filters"))
            .map { it to it.getBoundingClientRect() }
            .filter { (_, r) -> r.right > width + 0.5 }
            .map { (e, r) -> "${(e as Element).className} ends at ${r.right}" }
        assertTrue(wide.isEmpty(), "on a ${size.first} by ${size.second} screen: $wide")
        val firstRow = cards.map { it.getBoundingClientRect().top }.count { it == cards.first().getBoundingClientRect().top }
        assertTrue(
            firstRow >= columns,
            "on a ${size.first} by ${size.second} screen the first row of charts holds $firstRow, expected at least $columns",
        )
    }

    @Test
    fun theChartsFitAPhone() = runTest { fits(390 to 844, 1) }

    @Test
    fun theChartsSitSideBySideOnAFoldingPhoneOpenedOut() = runTest { fits(Tablet.UNFOLDED, 2) }

    @Test
    fun theChartsSitSideBySideOnATabletHeldSideways() = runTest { fits(Tablet.LANDSCAPE, 3) }
}
