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
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatsState

/**
 * Collection totals, on Android. Sibling of `StatsPage`.
 *
 * The same key-and-number list the site shows, in the same panel, with
 * the same segmented scope control across the top.
 */
@Composable
fun StatsScreen(state: StatsState, onScope: (Owner?) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        PageHead("Stats")

        Box(Modifier.testTag("scope")) {
            Seg(
                listOf("both" to "Both", "matt" to "Matt", "kayla" to "Kayla"),
                state.scope.owner?.slug ?: "both",
            ) { slug -> onScope(Owner.entries.firstOrNull { it.slug == slug }) }
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
