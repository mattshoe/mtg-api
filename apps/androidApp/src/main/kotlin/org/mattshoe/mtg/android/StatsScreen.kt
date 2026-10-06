package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatsState

/**
 * Collection totals, on Android. Sibling of `StatsPage`.
 *
 * The same key-and-number list the site shows, in the same panel,
 * over the same caption naming whose collection it is.
 */
@Composable
fun StatsScreen(state: StatsState) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        // No heading: the tab says "Stats". A Both / Matt / Kayla
        // segmented control was here, which was a list of the only two
        // people there would ever be. The page is one collection now,
        // so this says which one rather than offering a choice.
        Box(Modifier.testTag("scope")) {
            Line(state.scope.label, Ink3, modifier = Modifier.testTag("scope-label"))
        }

        when {
            state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("stats-busy"))
            state.error != null -> Line(
                "Could not load stats: ${state.error}",
                Bad,
                modifier = Modifier.testTag("stats-err"),
            )
            else -> {
                val t = state.totals
                Panel {
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
                    ).forEachIndexed { i, (label, value) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .testTag("stat-row-$i")
                                .semantics { contentDescription = "$label $value" },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Line(label, Ink2, Design.BODY, modifier = Modifier.testTag("stat-label-$i"))
                            // `.tag`: the number is a stated fact in a
                            // pill, not prose run together with its label.
                            Box(Modifier.testTag("stat-value-$i")) { Tag(value) }
                        }
                    }
                }
                t.pricedAt?.let {
                    Line(
                        "Prices from $it",
                        Ink3,
                        Design.MINI,
                        modifier = Modifier.testTag("priced-at"),
                    )
                }
            }
        }
    }
}
