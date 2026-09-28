package org.mattshoe.mtg.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.ConsoleState
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.LogsState

/** The query console, on Android. Sibling of `ConsolePage`. */
@Composable
fun ConsoleScreen(
    state: ConsoleState,
    onState: (ConsoleState) -> Unit,
    onRun: () -> Unit,
    onCheatsheet: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        PageHead("Query") { Ghost("Cheatsheet", onClick = onCheatsheet) }

        Panel {
            Field(
                value = state.sql,
                onValueChange = { onState(state.type(it)) },
                placeholder = "SELECT name, qty FROM cards LIMIT 10",
                modifier = Modifier.height(180.dp),
                singleLine = false,
                mono = true,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Primary(if (state.busy) "Running…" else "Run", enabled = state.canRun, onClick = onRun)
                state.result?.let { Line("${it.rows.size} rows in ${state.took}ms", Ink3) }
            }
            state.error?.let { Line(it, Bad) }
            state.result?.let { t ->
                if (t.isEmpty) {
                    Line("No rows.", Ink3)
                } else {
                    // Selectable and scrollable sideways, because a
                    // result is something you copy out of.
                    SelectionContainer {
                        Column(
                            Modifier.fillMaxWidth().height(380.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                t.cols.joinToString("  |  "),
                                color = Ink2,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                            t.rows.forEach { r ->
                                Text(
                                    r.joinToString("  |  ") { it ?: "null" },
                                    color = Ink,
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
}

/** The server log, on Android. Sibling of `LogsPage`. */
@Composable
fun LogsScreen(state: LogsState, onState: (LogsState) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        PageHead("Server logs")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ghost("Errors only (${state.errorCount})", on = state.onlyErrors) {
                onState(state.toggleErrors())
            }
        }
        when {
            state.busy -> Line("Loading…", Ink3)
            state.error != null -> Line(state.error!!, Bad)
            state.shown.isEmpty() -> Line("Nothing logged.", Ink3)
            else -> Panel {
                state.shown.forEach { l ->
                    Text(
                        "${l.ts.substringAfter('T').take(8)}  ${l.level}  ${l.method ?: ""} " +
                            "${l.path ?: ""}  ${l.status ?: ""}  ${l.ms ?: ""}ms",
                        color = if (l.failed) Bad else Ink2,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
