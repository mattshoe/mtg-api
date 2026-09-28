package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DecksState

/**
 * Decks, on Android. Sibling of `DecksPage`.
 *
 * Grouping, gaps and totals all come from `DecksState`, so this and the
 * web cannot disagree about which cards a deck is short of.
 */
@Composable
fun DecksScreen(
    state: DecksState,
    onOpen: (Deck) -> Unit,
    onClose: () -> Unit,
    admin: Boolean = false,
    onNew: () -> Unit = {},
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val open = state.open
        if (open == null) {
            Text("Decks", fontSize = 26.sp)
            if (admin) OutlinedButton(onClick = onNew) { Text("New deck") }
            when {
                state.busy -> Text("Loading…")
                state.error != null -> Text("Could not load decks: ${state.error}")
                state.decks.isEmpty() -> Text("No decks yet.")
                else -> state.byOwner.forEach { (owner, decks) ->
                    Text(owner.replaceFirstChar(Char::uppercase), fontSize = 17.sp)
                    decks.forEach { DeckTile(it, onOpen) }
                }
            }
        } else {
            OutlinedButton(onClick = onClose) { Text("← Decks") }
            Text(open.name, fontSize = 24.sp)
            Text(
                listOfNotNull(
                    open.commanderName,
                    open.bracket?.let { "Bracket $it" },
                    open.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                ).joinToString(" · "),
                fontSize = 13.sp,
            )
            Text("${state.totalCards} cards", fontSize = 13.sp)
            if (admin) {
                // Both of these move real cards, and both show the
                // server's own dry run before they are allowed to.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(open) }) { Text("Edit list", fontSize = 12.sp) }
                    OutlinedButton(onClick = { onDisassemble(open) }) {
                        Text("Disassemble", fontSize = 12.sp)
                    }
                }
            }
            if (state.gaps.isNotEmpty()) {
                Text("${state.gaps.size} not owned", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            state.cards.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.qty}×", fontSize = 13.sp)
                    Text(c.name, fontSize = 13.sp)
                    if (c.owned < c.qty) Text("(has ${c.owned})", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DeckTile(deck: Deck, onOpen: (Deck) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(deck.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                listOfNotNull(
                    deck.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                    deck.commanderName,
                    deck.bracket?.let { "Bracket $it" },
                ).joinToString(" · "),
                fontSize = 12.sp,
            )
            OutlinedButton(onClick = { onOpen(deck) }) { Text("Open", fontSize = 12.sp) }
        }
    }
}
