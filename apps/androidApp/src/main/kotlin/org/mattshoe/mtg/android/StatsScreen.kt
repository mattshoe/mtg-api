package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.StatsState

/** Collection totals, on Android. Sibling of `StatsPage`. */
@Composable
fun StatsScreen(state: StatsState, onScope: (Owner?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Stats", fontSize = 26.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Both", Owner.MATT to "Matt", Owner.KAYLA to "Kayla").forEach { (o, label) ->
                Button(onClick = { onScope(o) }, enabled = state.scope.owner != o) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }

        when {
            state.busy -> Text("Loading…")
            state.error != null -> Text("Could not load stats: ${state.error}")
            else -> {
                val t = state.totals
                listOf(
                    "Printings" to t.printings.toString(),
                    "Unique cards" to t.uniques.toString(),
                    "Physical cards" to t.physical.toString(),
                    "Free copies" to t.free.toString(),
                    "Decks" to t.decks.toString(),
                    "Sets" to t.sets.toString(),
                    "Foils" to t.foils.toString(),
                    "Value" to (t.value?.let { "$$it" } ?: "unpriced"),
                ).forEach { (label, value) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, fontSize = 14.sp)
                            Text(value, fontSize = 14.sp)
                        }
                    }
                }
                t.pricedAt?.let { Text("Prices from $it", fontSize = 12.sp) }
            }
        }
    }
}
