package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.StatCard
import org.mattshoe.mtg.core.StatTab
import org.mattshoe.mtg.core.Totals
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Stats screen's tabs, filters and charts, through the real `AppShell`.
 *
 * Matt: "the stats page is so barren and not useful. i want TONS of
 * data and i want to be able to filter on like colors and types ...
 * a tab for every card type ... pie charts, 'top' lists". The
 * website's `CollectionStatsPageTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class CollectionStatsParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: MutableState<AppState>

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

    private fun show(size: DpSize? = null) {
        rule.setContent {
            val body = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(onStats()) }
                        AppShell(
                            state = held.value,
                            onState = { held.value = it },
                            onSearch = {},
                            onOpenDeck = {},
                            onPreviewEntry = {},
                            onApplyEntry = {},
                        )
                    }
                }
            }
            if (size == null) body() else Tablet.Sized(size) { body() }
        }
        rule.waitForIdle()
    }

    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun told(t: String): String =
        tag(t).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")

    private fun words(t: String): String =
        tag(t).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }

    private fun exists(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pressed(t: String): Boolean =
        tag(t).fetchSemanticsNode().config.let { c ->
            if (SemanticsProperties.ToggleableState in c) c[SemanticsProperties.ToggleableState] else null
        } ==
            androidx.compose.ui.state.ToggleableState.On

    private fun selected(t: String): Boolean =
        tag(t).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun press(t: String) {
        tag(t).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private val charts = listOf(
        "Colour", "Colour identity", "Card types", "Mana curve", "Rarity",
        "Price per card", "Value by colour", "Value by rarity", "Top sets", "Release years",
        "Top artists", "Most valuable",
    )

    @Test
    fun theLandingTabIsTheWholeCollectionAndEveryTypeWithCardsHasATab() {
        show()
        assertTrue(exists("stats-tab-ALL"), "no landing tab on the Stats screen")
        assertEquals("Overview 22", told("stats-tab-ALL"), "the landing tab does not say how many cards")
        assertEquals("Instants 5", told("stats-tab-INSTANT"))
        assertTrue(!exists("stats-tab-BATTLE"), "a tab with nothing in it")
        assertTrue(selected("stats-tab-ALL"), "the landing tab is not the one on")
    }

    @Test
    fun theLandingPageHasEveryChartAboutTheWholeCollection() {
        show()
        val missing = charts.filter { !exists("stats-chart-$it") }
        assertTrue(missing.isEmpty(), "no chart for $missing")
        assertTrue(!exists("stats-chart-Creature types"), "the creature types are on the overview")
        assertTrue(exists("stats-priciest-0"), "the most valuable cards are not listed")
        assertTrue(told("stats-priciest-0").startsWith("Atraxa, Praetors' Voice"), "the first is ${told("stats-priciest-0")}")
        // The rings are real rings: what they paint, read back.
        val rings = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(RingArcs), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[RingArcs] }
        assertTrue(rings.size >= 2, "expected the colour and identity rings, found ${rings.size}")
        assertTrue(rings.all { it.size >= 3 }, "a ring paints too little: $rings")
    }

    @Test
    fun aTabNarrowsEveryNumberToItsType() {
        show()
        press("stats-tab-INSTANT")
        assertEquals(StatTab.INSTANT, held.value.stats.tab, "the tab was not picked")
        assertTrue(selected("stats-tab-INSTANT"))
        assertEquals("5 cards", told("stats-figure-0"), "the first figure is not the Instants' five copies")
    }

    @Test
    fun aColourNarrowsEveryNumberAndTheEmptyCaseSaysSo() {
        show()
        press("stats-colour-R")
        assertTrue(pressed("stats-colour-R"), "the red filter does not say it is on")
        assertEquals("5 cards", told("stats-figure-0"), "red is Bolt and Command, five copies")
        press("stats-tab-CREATURE")
        assertEquals("No cards match these filters.", words("stats-empty"))
        rule.onNodeWithContentDescription("Clear the filters").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(held.value.stats.filter.isEmpty, "clearing did not clear")
        assertEquals("5 cards", told("stats-figure-0"), "the Creatures tab's five copies")
    }

    /*
     * Matt, on the first version: "i asked for a tabbed fucking page
     * where the landing tab is overall stats about the whole
     * collection, then a series of other tabs about each card type ...
     * this is so fucking discombobulated and crowded". The tabs were a
     * wrapped row of chips, the same chips as the filters under them,
     * over eight totals, six figures and thirteen charts in one list.
     */

    @Test
    fun theTabsAreATabBarOnOneLineAndNotMoreChips() {
        show()
        val tabs = held.value.stats.tabs.map { "stats-tab-${it.first.name}" }
        assertTrue(tabs.size >= 4, "only ${tabs.size} tabs")
        val tops = tabs.map { tag(it).getUnclippedBoundsInRoot().top }.distinct()
        assertEquals(1, tops.size, "the tabs wrap onto ${tops.size} lines, like chips")
        assertTrue(
            SemanticsProperties.HorizontalScrollAxisRange in tag("stats-tabs").fetchSemanticsNode().config,
            "the tab bar cannot scroll sideways, so a tab past the edge is out of reach",
        )
        // The open tab is underlined: a line, measured, under that tab and no other.
        fun underline() = tag("stats-tab-underline").getUnclippedBoundsInRoot()
        fun under(t: String) {
            val tab = tag(t).getUnclippedBoundsInRoot()
            val line = underline()
            assertTrue(line.left >= tab.left - androidx.compose.ui.unit.Dp(0.5f) && line.right <= tab.right + androidx.compose.ui.unit.Dp(0.5f), "the underline $line is not under $t $tab")
            assertTrue(line.bottom <= tab.bottom + androidx.compose.ui.unit.Dp(0.5f) && line.top >= tab.bottom - androidx.compose.ui.unit.Dp(4f), "the underline $line is not along the foot of $t $tab")
            assertTrue(line.bottom - line.top >= androidx.compose.ui.unit.Dp(2f), "the underline is ${line.bottom - line.top} thick")
        }
        under("stats-tab-ALL")
        press("stats-tab-INSTANT")
        under("stats-tab-INSTANT")
        assertEquals(1, rule.onAllNodesWithTag("stats-tab-underline", useUnmergedTree = true).fetchSemanticsNodes().size, "more than one tab is underlined")
    }

    private fun sections(): List<String> =
        listOf("Colours", "Make-up", "Value", "Printings").filter { exists("stats-section-$it") }

    private fun inside(chart: String, section: String): Boolean {
        val c = tag("stats-chart-$chart").getUnclippedBoundsInRoot()
        val s = tag("stats-section-$section").getUnclippedBoundsInRoot()
        return c.top >= s.top && c.bottom <= s.bottom
    }

    @Test
    fun eachTabIsAFewTitledSectionsOfTheChartsAboutIt() {
        show()
        assertEquals(listOf("Colours", "Make-up", "Value", "Printings"), sections(), "the overview's sections")
        assertEquals("Colours", words("stats-section-title-Colours"), "a section with no heading")
        val tops = sections().map { tag("stats-section-$it").getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "the sections are out of order")
        assertTrue(inside("Colour identity", "Colours"), "the identity ring is not in Colours")
        assertTrue(inside("Most valuable", "Value"), "the most valuable cards are not in Value")
        assertTrue(inside("Top artists", "Printings"), "the artists are not in Printings")
        press("stats-tab-INSTANT")
        val loose = listOf("Card types", "Release years", "Top artists", "Value by colour", "Creature types").filter { exists("stats-chart-$it") }
        assertTrue(loose.isEmpty(), "the Instants tab still draws $loose")
        assertTrue(inside("Mana curve", "Make-up"), "the Instants' curve is not in Make-up")
        press("stats-tab-CREATURE")
        assertTrue(exists("stats-chart-Creature types"), "no creature types on the Creatures tab")
    }

    @Test
    fun theOverviewLeadsWithTheWholeCollectionsNumbersOnce() {
        show()
        assertEquals(
            listOf("cards", "unique", "value", "decks", "free", "sets", "foils", "avg mana"),
            (0..7).map { told("stats-figure-$it").substringAfter(" ").substringBefore(" —") },
        )
        assertTrue(!exists("stats-figure-8"), "a ninth figure")
        // The eight-line totals list said the same things again above the tabs.
        assertTrue(!exists("stat-row-0"), "the totals list is still drawn over the tabs")
        assertTrue(
            tag("stats-tabs").getUnclippedBoundsInRoot().bottom <= tag("stats-figure-0").getUnclippedBoundsInRoot().top,
            "the tabs are not above what they choose between",
        )
    }

    /**
     * The second query failing, the way `intoPage` reports it: the
     * spinner goes, the error says what happened, no stale chart is
     * left under it, and a pull brings the charts back.
     */
    @Test
    fun aLoadThatFailsSaysSoAndAPullBringsTheChartsBack() {
        show()
        held.value = held.value.refreshed()
        rule.waitForIdle()
        assertTrue(exists("stats-busy"), "a pull does not show it is loading")
        held.value = held.value.fetchFailed("500 from /query", View.STATS)
        rule.waitForIdle()
        assertTrue(!exists("stats-busy"), "the spinner outlived the failure")
        assertEquals("Could not load stats: 500 from /query", words("stats-err"))
        assertTrue(!exists("stats-tabs"), "tabs from the last load sit under the error")
        assertTrue(!exists("stats-chart-Colour"), "charts from the last load sit under the error")
        held.value = held.value.refreshed()
        held.value = held.value.copy(stats = held.value.stats.loaded(Totals(), pile))
        rule.waitForIdle()
        assertTrue(exists("stats-chart-Colour"), "a retry did not bring the charts back")
        assertTrue(!exists("stats-err"), "the error outlived the retry")
    }

    /** Nothing past the right edge, and on a wide screen the charts sit side by side. */
    private fun fits(size: DpSize?, sideBySide: Boolean) {
        show(size)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val wide = (charts.map { "stats-chart-$it" } + "stats-tabs" + "stats-filters")
            .filter { exists(it) }
            .map { it to tag(it).getUnclippedBoundsInRoot() }
            .filter { (_, b) -> b.right > root.right + androidx.compose.ui.unit.Dp(0.5f) }
            .map { (t, b) -> "$t ends at ${b.right} of ${root.right}" }
        assertTrue(wide.isEmpty(), "on ${size ?: "a phone"}: $wide")
        val first = tag("stats-chart-Colour").getUnclippedBoundsInRoot()
        val second = tag("stats-chart-Colour identity").getUnclippedBoundsInRoot()
        if (sideBySide) {
            assertEquals(first.top, second.top, "on $size the first two charts are not side by side")
            assertTrue(second.left >= first.right, "on $size the charts overlap")
        } else {
            assertTrue(second.top >= first.bottom, "on a phone the charts are not one above the other")
        }
    }

    @Test
    fun theChartsFitAPhone() = fits(null, sideBySide = false)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theChartsSitSideBySideOnAFoldingPhoneOpenedOut() = fits(Tablet.UNFOLDED, sideBySide = true)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theChartsSitSideBySideOnATabletHeldSideways() = fits(Tablet.LANDSCAPE, sideBySide = true)
}
