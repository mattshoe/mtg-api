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
        assertTrue(tabs.first().startsWith("Overview"), "the first tab is ${tabs.first()}")
        assertTrue(tabs.first().contains("22"), "the landing tab does not say how many cards: ${tabs.first()}")
        assertTrue(tabs.any { it.startsWith("Instants") && it.contains("5") }, "no Instants tab with its five: $tabs")
        assertTrue(tabs.none { it.startsWith("Battles") }, "a tab with nothing in it: $tabs")
        assertEquals("true", doc.tab("Overview").getAttribute("aria-selected"), "the landing tab is not the one on")
    }

    @Test
    fun theLandingPageHasEveryChartAboutTheWholeCollection() = runTest {
        val doc = mount(390 to 844)
        val have = doc.headings()
        listOf(
            "colour", "colour identity", "card types", "mana curve", "rarity",
            "price per card", "value by colour", "value by rarity", "top sets", "release years",
            "top artists", "most valuable",
        ).forEach { assertTrue(it in have, "no \"$it\" chart; the charts are $have") }
        assertTrue("creature types" !in have, "the creature types are on the overview: $have")
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
        assertEquals("true", doc.tab("Instants").getAttribute("aria-selected"))
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

    /**
     * The second query failing, the way `intoPage` reports it: the
     * spinner goes, the error says what happened, no stale chart is
     * left under it, and a pull brings the charts back.
     */
    @Test
    fun aLoadThatFailsSaysSoAndAPullBringsTheChartsBack() = runTest {
        val doc = mount(390 to 844)
        held.value = held.value.refreshed()
        settle(screen!!.window)
        assertTrue(doc.body!!.textContent!!.contains("Loading…"), "a pull does not show it is loading")
        held.value = held.value.fetchFailed("500 from /query", View.STATS)
        settle(screen!!.window)
        val text = doc.body!!.textContent!!
        assertTrue(!text.contains("Loading…"), "the spinner outlived the failure")
        assertTrue(text.contains("Could not load stats: 500 from /query"), "the failure is not said: $text")
        assertEquals(0, doc.all(".stats-tabs").size, "tabs from the last load sit under the error")
        assertEquals(0, doc.all(".stats-card").size, "charts from the last load sit under the error")
        held.value = held.value.refreshed()
        held.value = held.value.copy(stats = held.value.stats.loaded(Totals(), pile))
        settle(screen!!.window)
        assertTrue(doc.all(".stats-card").size >= 10, "a retry did not bring the charts back")
        assertTrue(!doc.body!!.textContent!!.contains("Could not load"), "the error outlived the retry")
    }

    /*
     * Matt, on the first version: "i asked for a tabbed fucking page
     * where the landing tab is overall stats about the whole
     * collection, then a series of other tabs about each card type ...
     * this is so fucking discombobulated and crowded". The tabs were a
     * wrapped row of chips, the same chips as the filters under them,
     * over eight totals, six figures and thirteen charts in one grid.
     */

    @Test
    fun theTabsAreATabBarOnOneLineAndNotMoreChips() = runTest {
        val doc = mount(390 to 844)
        val w = screen!!.window
        val bar = assertNotNull(doc.querySelector(".stats-tabs"), "no tab bar").unsafeCast<HTMLElement>()
        assertEquals("tablist", bar.getAttribute("role"), "the tab bar is not a tablist")
        val tabs = doc.all(".stats-tabs [role='tab']")
        assertTrue(tabs.size >= 4, "only ${tabs.size} tabs")
        val tops = tabs.map { it.getBoundingClientRect().top }.distinct()
        assertEquals(1, tops.size, "the tabs wrap onto ${tops.size} lines, like chips")
        val on = w.getComputedStyle(doc.tab("Overview"))
        val off = w.getComputedStyle(doc.tab("Instants"))
        assertTrue(on.borderBottomWidth.removeSuffix("px").toDouble() >= 2.0, "the open tab has no underline: ${on.borderBottomWidth}")
        assertTrue(on.borderBottomColor != off.borderBottomColor, "the open tab's underline is the same as a closed one's: ${on.borderBottomColor}")
        assertTrue(on.color != off.color, "the open tab's words are no brighter than a closed one's")
        assertEquals("rgba(0, 0, 0, 0)", off.backgroundColor, "a closed tab is a filled pill, not a tab")
        // Every tab still reachable on a phone: the bar scrolls sideways rather than wrapping.
        assertTrue(w.getComputedStyle(bar).overflowX in listOf("auto", "scroll"), "the tab bar cannot scroll: ${w.getComputedStyle(bar).overflowX}")
    }

    private fun org.w3c.dom.Document.sections(): List<Pair<String, List<String>>> =
        all(".stats-section").map { sec ->
            val h = assertNotNull(sec.querySelector(":scope > h2"), "a section with no heading").textContent!!.trim()
            val cards = sec.querySelectorAll(".stats-card > h3")
            h to (0 until cards.length).map { cards[it]!!.textContent!!.trim().lowercase() }
        }

    @Test
    fun eachTabIsAFewTitledSectionsOfTheChartsAboutIt() = runTest {
        val doc = mount(390 to 844)
        assertEquals(listOf("Colours", "Make-up", "Value", "Printings"), doc.sections().map { it.first }, "the overview's sections")
        assertEquals(doc.headings().size, doc.sections().sumOf { it.second.size }, "a chart sits outside every section")
        doc.tab("Instants").click()
        settle(screen!!.window)
        val instants = doc.sections().toMap()
        assertEquals(listOf("mana curve", "rarity"), instants["Make-up"], "the Instants tab's make-up")
        val loose = listOf("card types", "release years", "top artists", "value by colour", "creature types").filter { it in doc.headings() }
        assertTrue(loose.isEmpty(), "the Instants tab still draws $loose")
        doc.tab("Creatures").click()
        settle(screen!!.window)
        assertTrue("creature types" in doc.headings(), "no creature types on the Creatures tab: ${doc.headings()}")
    }

    @Test
    fun theOverviewLeadsWithTheWholeCollectionsNumbersOnce() = runTest {
        val doc = mount(390 to 844)
        assertEquals(
            listOf("cards", "unique", "value", "decks", "free", "sets", "foils", "avg mana"),
            doc.all(".stats-figures .figure .k").map { it.textContent!! },
        )
        // The eight-line totals list said the same things again above the tabs.
        assertTrue(!doc.body!!.textContent!!.contains("Physical cards"), "the totals list is still drawn over the tabs")
        val bar = doc.querySelector(".stats-tabs")!!.getBoundingClientRect()
        val figures = doc.querySelector(".stats-figures")!!.getBoundingClientRect()
        assertTrue(bar.bottom <= figures.top, "the tabs are not above what they choose between")
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
        // The first section is the two colour rings: one above the other on a phone, side by side wider.
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
    fun theChartsSitSideBySideOnATabletHeldSideways() = runTest { fits(Tablet.LANDSCAPE, 2) }
}
