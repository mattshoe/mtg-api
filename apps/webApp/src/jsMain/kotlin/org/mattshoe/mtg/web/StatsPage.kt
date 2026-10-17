package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr
import org.mattshoe.mtg.core.CollectionBreakdown
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
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
                if (state.cards.isNotEmpty()) Charts(state, onState)
            }
        }
    }
}

/**
 * The tabs, the filters and every chart. The phone's `Charts` in
 * `StatsScreen` draws the same, from the same `CollectionBreakdown`.
 *
 * Matt: "i want TONS of data and i want to be able to filter on like
 * colors and types and stuff ... a tab for every card type would be
 * cool, with the landing page being the data on the collection as a
 * whole".
 */
@Composable
private fun Charts(state: StatsState, onState: (StatsState) -> Unit) {
    Div(attrs = { classes("stats-tabs") }) {
        state.tabs.forEach { (tab, n) ->
            val on = tab == state.tab
            Button(attrs = {
                classes("chip")
                if (on) classes("on")
                attr("type", "button")
                attr("aria-pressed", on.toString())
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
            StatFigure("${b.copies}", "cards")
            StatFigure("${b.uniques}", "unique")
            StatFigure(
                Prices.money(b.value, dash = "unpriced"),
                "value",
                hint = if (b.unpriced > 0) "${b.unpriced} cards have no price" else "",
            )
            StatFigure(b.averageManaValueText, "avg mana")
            StatFigure("${b.foils}", "foils")
            StatFigure("${b.sets}", "sets")
        }
        Div(attrs = { classes("stats-grid") }) { ChartGrid(b) }
    }
}

@Composable
private fun ChartGrid(b: CollectionBreakdown) {
    val money: (Int) -> String = { Prices.money(it.toDouble()) }
    StatChart("Colour") { StatPie("Per colour", b.colours) }
    StatChart("Colour identity") { StatPie("Exact identity", b.identities, b.identityRing) }
    StatChart("Card types") {
        StatBars(b.types, b.copies, "Of ${b.copies} cards. A card of two types counts in each.")
    }
    if (b.hasCurve) StatChart("Mana curve") { StatCurve(b.curve, "Average ${b.averageManaValueText}. Lands excluded.") }
    StatChart("Rarity") { StatBars(b.rarities, b.copies, "Of ${b.copies} cards.") }
    if (b.tribes.isNotEmpty()) StatChart("Creature types") { StatBars(b.tribes, b.creatures, b.tribesNote) }
    StatChart("Price per card") { StatBars(b.priceBands, b.copies, "Copies, by what one is worth.") }
    if (b.valueByColour.isNotEmpty()) {
        StatChart("Value by colour") {
            StatBars(b.valueByColour, b.valueByColour.sumOf { it.value }, "A card of two colours counts in each.", money)
        }
    }
    if (b.valueByRarity.isNotEmpty()) {
        StatChart("Value by rarity") {
            StatBars(b.valueByRarity, b.valueByRarity.sumOf { it.value }, "In whole dollars.", money)
        }
    }
    StatChart("Top sets") { StatBars(b.topSets, b.copies, "Of ${b.copies} cards.") }
    if (b.years.isNotEmpty()) StatChart("Release years") { StatBars(b.years, b.copies, "By the year the printing came out.") }
    if (b.artists.isNotEmpty()) StatChart("Top artists") { StatBars(b.artists, b.copies, "Of ${b.copies} cards.") }
    if (b.priciest.isNotEmpty()) {
        StatChart("Most valuable") {
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
    }
}
