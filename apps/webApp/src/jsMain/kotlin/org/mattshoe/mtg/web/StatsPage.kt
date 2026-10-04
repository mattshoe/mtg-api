package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatsState

/** Collection totals, on the web. Sibling of `StatsScreen`. */
@Composable
fun StatsPage(state: StatsState, onScope: (Owner?) -> Unit) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("flex-wrap") }) {
            listOf(null to "Both", Owner.MATT to "Matt", Owner.KAYLA to "Kayla").forEach { (o, label) ->
                Button(attrs = {
                    classes("owner-opt")
                    if (state.scope.owner == o) classes("on")
                    onClick { onScope(o) }
                }) { Text(label) }
            }
        }

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
