package org.mattshoe.mtg.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Cheatsheet
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.PaletteState

/**
 * Quick find, on Android. Sibling of `PaletteDialog` on the web.
 *
 * The same substring-across-both-faces query, from the shared core, so
 * the same typing finds the same cards on either.
 */
@Composable
fun PaletteDialog(
    state: PaletteState,
    onState: (PaletteState) -> Unit,
    onOpen: (Found) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Find a card") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = state.term,
                    onValueChange = { onState(state.typed(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.items.forEachIndexed { i, row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onOpen(row) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.fillMaxWidth(0.75f)) {
                                Text(
                                    row.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (i == state.active) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(row.typeLine.orEmpty(), fontSize = 12.sp)
                            }
                            Text("${row.qty}× ${row.owner}", fontSize = 12.sp)
                        }
                    }
                    if (state.items.isEmpty() && state.term.isNotBlank() && !state.busy) {
                        Text("Nothing matches that.", fontSize = 13.sp)
                    }
                }
            }
        },
    )
}

/** Everything the query box understands. Sibling of `CheatsheetDialog`. */
@Composable
fun CheatsheetDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Query box") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Cheatsheet.PREAMBLE, fontSize = 13.sp)
                Cheatsheet.groups.forEach { group ->
                    Text(group.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    group.keys.forEach { key ->
                        // Selectable, so an example can be copied into
                        // the box rather than retyped from memory.
                        SelectionContainer {
                            Column {
                                Text(key.keys, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Text(key.what, fontSize = 12.sp)
                                Text(key.example, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Text(
                    "is: values (${Cheatsheet.isValues.size})",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cheatsheet.isValues.forEach {
                        AssistChip(onClick = {}, label = { Text(it, fontSize = 11.sp) })
                    }
                }
            }
        },
    )
}
