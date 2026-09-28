package org.mattshoe.mtg.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.ConsoleState
import org.mattshoe.mtg.core.LogsState

/** The query console, on Android. Sibling of `ConsolePage`. */
@Composable
fun ConsoleScreen(state: ConsoleState, onState: (ConsoleState) -> Unit, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Query", fontSize = 26.sp)
        OutlinedTextField(
            value = state.sql,
            onValueChange = { onState(state.type(it)) },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            placeholder = { Text("SELECT name, qty FROM cards LIMIT 10") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = state.canRun) {
                Text(if (state.busy) "Running…" else "Run")
            }
            state.result?.let { Text("${it.rows.size} rows in ${state.took}ms", fontSize = 13.sp) }
        }
        state.error?.let { Text(it, fontSize = 13.sp) }
        state.result?.let { t ->
            if (t.isEmpty) {
                Text("No rows.", fontSize = 13.sp)
            } else {
                // Selectable and scrollable sideways, because a result is
                // something you copy out of.
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().height(380.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(t.cols.joinToString("  |  "), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        t.rows.forEach { r ->
                            Text(
                                r.joinToString("  |  ") { it ?: "null" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The server log, on Android. Sibling of `LogsPage`. */
@Composable
fun LogsScreen(state: LogsState, onState: (LogsState) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Server logs", fontSize = 26.sp)
        OutlinedButton(onClick = { onState(state.toggleErrors()) }) {
            Text("Errors only (${state.errorCount})", fontSize = 13.sp)
        }
        when {
            state.busy -> Text("Loading…")
            state.error != null -> Text(state.error!!, fontSize = 13.sp)
            state.shown.isEmpty() -> Text("Nothing logged.", fontSize = 13.sp)
            else -> state.shown.forEach { l ->
                Text(
                    "${l.ts.substringAfter('T').take(8)}  ${l.level}  ${l.method ?: ""} ${l.path ?: ""}  " +
                        "${l.status ?: ""}  ${l.ms ?: ""}ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
