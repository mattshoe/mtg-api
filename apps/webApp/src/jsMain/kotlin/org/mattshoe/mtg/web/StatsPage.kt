package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr
import org.mattshoe.mtg.core.CollectionBreakdown
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatChart
import org.mattshoe.mtg.core.StatTab
import org.mattshoe.mtg.core.StatsState

/** Collection totals, on the web. Sibling of `StatsScreen`. */
@Composable
fun StatsPage(state: StatsState, onState: (StatsState) -> Unit = {}) {
    Div(attrs = { classes("wrap") }) {
        // A caption, where a Both / Matt / Kayla segmented control
        // used to be. The page is one collection now — whichever the
        // address names — so there is nothing to switch between, and
        // the only thing left worth saying is which one these numbers
        // are about.
        Div(attrs = { classes("muted", "small") }) { Text(state.scope.label) }

        when {
            state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
            state.error != null -> Div(attrs = { classes("err") }) { Text("Could not load stats: ${state.error}") }
            // With the cards loaded the overview's figures say all of
            // this, so the list is only for a collection with none.
            state.cards.isNotEmpty() -> Tabbed(state, onState)
            else -> {
                val t = state.totals
                Div(attrs = { classes("panel") }) {
                    Div(attrs = { classes("panel-body") }) {
                        listOf(
                            "Printings" to t.printings.toString(),
                            "Unique cards" to t.uniques.toString(),
                            "Physical cards" to t.physical.toString(),
                            "Free copies" to t.free.toString(),
                            "Decks" to t.decks.toString(),
                            "Sets" to t.sets.toString(),
                            "Foils" to t.foils.toString(),
                            // `unpriced`, never `$0`: nothing priced and
                            // nothing worth anything are different facts.
                            "Value" to Prices.money(t.value, dash = "unpriced"),
                        ).forEach { (label, value) ->
                            Div(attrs = { classes("flex-wrap") }) {
                                Span { Text(label) }
                                Span(attrs = { classes("spacer") }) {}
                                Span(attrs = { classes("tag") }) { Text(value) }
                            }
                        }
                    }
                }
                t.pricedAt?.let { Div(attrs = { classes("muted", "small") }) { Text("Prices from $it") } }
            }
        }
    }
}

/**
 * The tab bar, the filters, the tab's numbers and its sections. The
 * phone's `Tabbed` in `StatsScreen` draws the same, from the same
 * `StatsState`.
 *
 * Matt: "i asked for a tabbed fucking page where the landing tab is
 * overall stats about the whole collection, then a series of other
 * tabs about each card type ... MAKE IT LOOK STYLISH AND ORGANIZED AND
 * TABBED AND READY TO DIGEST". The first version wrapped the tabs as
 * chips, the same chips as the filters, over every chart in one grid.
 */
@Composable
private fun Tabbed(state: StatsState, onState: (StatsState) -> Unit) {
    // One line that scrolls sideways, underlined under the open tab,
    // so it reads as tabs and not as one more row of filters.
    Div(attrs = {
        classes("stats-tabs")
        attr("role", "tablist")
        attr("aria-label", "Card types")
    }) {
        state.tabs.forEach { (tab, n) ->
            val on = tab == state.tab
            Button(attrs = {
                classes("stats-tab")
                if (on) classes("on")
                attr("type", "button")
                attr("role", "tab")
                attr("aria-selected", on.toString())
                onClick { onState(state.onTab(tab)) }
            }) {
                Text(tab.title)
                Span(attrs = { classes("count") }) { Text("$n") }
            }
        }
    }

    Div(attrs = { classes("stats-filters") }) {
        Div(attrs = { classes("pips") }) {
            Pip.entries.forEach { pip ->
                val on = pip in state.filter.colours
                Button(attrs = {
                    classes("pip")
                    if (on) classes("on")
                    attr("type", "button")
                    attr("data-c", pip.letter)
                    attr("title", pip.label)
                    attr("aria-label", pip.label)
                    attr("aria-pressed", on.toString())
                    onClick { onState(state.toggleColour(pip)) }
                }) { ManaPip(pip.letter) }
            }
        }
        Div(attrs = { classes("chips") }) {
            state.rarities.forEach { r ->
                val on = r in state.filter.rarities
                Button(attrs = {
                    classes("chip", "mini")
                    if (on) classes("on")
                    attr("type", "button")
                    attr("aria-pressed", on.toString())
                    onClick { onState(state.toggleRarity(r)) }
                }) { Text(r.replaceFirstChar(Char::uppercase)) }
            }
            if (!state.filter.isEmpty) {
                IconAction("Clear the filters", Glyph.CROSS, look = listOf("chip", "mini")) {
                    onState(state.clearFilters())
                }
            }
        }
    }

    val b = state.breakdown
    if (b.isEmpty) {
        Div(attrs = { classes("empty") }) { Text("No cards match these filters.") }
        return
    }
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("figures", "stats-figures") }) {
            state.figures.forEach { StatFigure(it.value, it.label, hint = it.hint) }
        }
    }
    if (state.tab == StatTab.ALL) {
        state.totals.pricedAt?.let { Div(attrs = { classes("muted", "small", "stats-priced") }) { Text("Prices from $it") } }
    }
    state.sections.forEach { section ->
        Section(attrs = { classes("stats-section") }) {
            H2 { Text(section.title) }
            Div(attrs = { classes("panel") }) {
                Div(attrs = { classes("stats-grid") }) { section.charts.forEach { Chart(it, b) } }
            }
        }
    }
}

@Composable
private fun Chart(chart: StatChart, b: CollectionBreakdown) {
    val money: (Int) -> String = { Prices.money(it.toDouble()) }
    StatChart(chart.title) {
        when (chart) {
            StatChart.COLOUR -> StatPie("Per colour", b.colours)
            StatChart.IDENTITY -> StatPie("Exact identity", b.identities, b.identityRing)
            StatChart.TYPES -> StatBars(b.types, b.copies, "Of ${b.copies} cards. A card of two types counts in each.")
            StatChart.CURVE -> StatCurve(b.curve, "Average ${b.averageManaValueText}. Lands excluded.")
            StatChart.RARITY -> StatBars(b.rarities, b.copies, "Of ${b.copies} cards.")
            StatChart.TRIBES -> StatBars(b.tribes, b.creatures, b.tribesNote)
            StatChart.PRICES -> StatBars(b.priceBands, b.copies, "Copies, by what one is worth.")
            StatChart.VALUE_BY_COLOUR ->
                StatBars(b.valueByColour, b.valueByColour.sumOf { it.value }, "A card of two colours counts in each.", money)
            StatChart.VALUE_BY_RARITY ->
                StatBars(b.valueByRarity, b.valueByRarity.sumOf { it.value }, "In whole dollars.", money)
            StatChart.SETS -> StatBars(b.topSets, b.copies, "Of ${b.copies} cards.")
            StatChart.YEARS -> StatBars(b.years, b.copies, "By the year the printing came out.")
            StatChart.ARTISTS -> StatBars(b.artists, b.copies, "Of ${b.copies} cards.")
            StatChart.PRICIEST -> Priciest(b)
        }
    }
}

@Composable
private fun Priciest(b: CollectionBreakdown) {
    Table(attrs = { classes("pie-table", "priciest") }) {
        Tbody {
            b.priciest.forEachIndexed { i, c ->
                Tr {
                    Td(attrs = { classes("no") }) { Text("${i + 1}") }
                    Td {
                        Text(c.name)
                        Div(attrs = { classes("muted", "small") }) {
                            Text(c.setName + if (c.foil) " · foil" else "")
                        }
                    }
                    Td(attrs = { classes("num") }) { Text(Prices.money(c.each)) }
                    Td(attrs = { classes("num") }) { Text("×${c.qty}") }
                }
            }
        }
    }
}
