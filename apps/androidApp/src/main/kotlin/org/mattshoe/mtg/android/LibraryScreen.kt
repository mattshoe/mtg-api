package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Sort

/**
 * The Library, on Android.
 *
 * Sibling of `LibraryPage` on the web. Both are handed a `Library` and
 * ask it the same questions — what page, is there a next one, what range
 * is showing — so the paging cannot come out different on one of them.
 * This file chooses what it looks like and nothing else.
 */
@Composable
fun LibraryScreen(
    state: Library,
    onState: (Library) -> Unit,
    onSearch: () -> Unit,
    onOpen: (CardRow) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Library", fontSize = 26.sp)

        OutlinedTextField(
            value = state.filters.q,
            onValueChange = { onState(state.where(state.filters.copy(q = it))) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Card name") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("both" to "Both", "matt" to "Matt", "kayla" to "Kayla").forEach { (slug, label) ->
                val on = state.filters.owner == slug
                Button(
                    onClick = { onState(state.where(state.filters.copy(owner = slug))) },
                    enabled = !on,
                ) { Text(label, fontSize = 13.sp) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSearch, enabled = !state.busy) { Text("Search") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Sort.PRICE, Sort.NAME, Sort.CMC, Sort.QTY).forEach { sort ->
                val on = state.filters.sort == sort
                OutlinedButton(onClick = { onState(state.sortBy(sort)); onSearch() }) {
                    Text(
                        sort.label + if (on) (if (state.filters.descending) " ↓" else " ↑") else "",
                        fontSize = 12.sp,
                    )
                }
            }
        }

        when {
            state.busy -> Text("Searching…")
            state.error != null -> Text("Search failed: ${state.error}")
            state.isEmpty -> Text("Nothing matches that.")
            else -> {
                Text(
                    "${state.showing.first}–${state.showing.last} of ${state.total}",
                    fontSize = 13.sp,
                )
                LazyColumn(
                    Modifier.fillMaxWidth().height(520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.rows, key = { "${it.owner}:${it.nameNorm}" }) { CardTile(it, onOpen) }
                }
                Pager(state) { onState(it); onSearch() }
            }
        }
    }
}

@Composable
private fun CardTile(card: CardRow, onOpen: (CardRow) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Selectable, the same as the web. A canvas renderer would
            // have taken copying a card name away.
            SelectionContainer {
                Text(card.fullName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text(
                listOfNotNull(
                    card.typeLine,
                    card.setCode?.uppercase(),
                    card.manaCost,
                ).joinToString(" · "),
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${card.qty}× ${card.owner}", fontSize = 12.sp)
                card.free?.let { Text("$it free", fontSize = 12.sp) }
                card.price?.let { Text("$$it", fontSize = 12.sp) }
            }
            OutlinedButton(onClick = { onOpen(card) }) { Text("Details", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Pager(state: Library, go: (Library) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { go(state.prev()) }, enabled = state.hasPrev) { Text("← Previous") }
        Text("Page ${state.page} of ${state.pages}", fontSize = 13.sp)
        OutlinedButton(onClick = { go(state.next()) }, enabled = state.hasNext) { Text("Next →") }
    }
}
