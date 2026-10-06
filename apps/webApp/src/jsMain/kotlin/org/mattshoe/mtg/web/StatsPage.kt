package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatsState

/** Collection totals, on the web. Sibling of `StatsScreen`. */
@Composable
fun StatsPage(state: StatsState) {
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
            }
        }
    }
}
