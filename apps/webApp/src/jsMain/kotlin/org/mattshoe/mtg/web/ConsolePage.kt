package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.mattshoe.mtg.core.ConsoleState
import org.mattshoe.mtg.core.LogsState

/** The query console, on the web. Sibling of `ConsoleScreen`. */
@Composable
fun ConsolePage(state: ConsoleState, onState: (ConsoleState) -> Unit, onRun: () -> Unit) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) { H1 { Text("Query") } }
        Div(attrs = { classes("panel") }) {
            Div(attrs = { classes("panel-body") }) {
                TextArea(value = state.sql, attrs = {
                    classes("field", "mono")
                    rows(8)
                    onInput { onState(state.type(it.value)) }
                })
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        if (!state.canRun) disabled()
                        onClick { onRun() }
                    }) { Text(if (state.busy) "Running…" else "Run") }
                    state.result?.let {
                        Div(attrs = { classes("muted", "small") }) {
                            Text("${it.rows.size} rows in ${state.took}ms")
                        }
                    }
                }
                state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
                state.result?.let { ResultTable(it) }
            }
        }
    }
}

@Composable
private fun ResultTable(t: org.mattshoe.mtg.core.Table) {
    if (t.isEmpty) {
        Div(attrs = { classes("empty") }) { Text("No rows.") }
        return
    }
    Div(attrs = { classes("table-wrap") }) {
        Table {
            Thead { Tr { t.cols.forEach { Th { Text(it) } } } }
            Tbody {
                t.rows.forEach { row ->
                    Tr { row.forEach { Td { Text(it ?: "null") } } }
                }
            }
        }
    }
}

/** The server log, on the web. Sibling of `LogsScreen`. */
@Composable
fun LogsPage(state: LogsState, onState: (LogsState) -> Unit) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) { H1 { Text("Server logs") } }
        Div(attrs = { classes("flex-wrap") }) {
            Button(attrs = {
                classes("btn", "sm", "ghost")
                if (state.onlyErrors) classes("on")
                onClick { onState(state.toggleErrors()) }
            }) { Text("Errors only (${state.errorCount})") }
        }
        when {
            state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
            state.error != null -> Div(attrs = { classes("err") }) { Text(state.error!!) }
            state.shown.isEmpty() -> Div(attrs = { classes("empty") }) { Text("Nothing logged.") }
            else -> Div(attrs = { classes("table-wrap") }) {
                Table {
                    Thead {
                        Tr { listOf("When", "Level", "Method", "Path", "Status", "ms").forEach { Th { Text(it) } } }
                    }
                    Tbody {
                        state.shown.forEach { l ->
                            Tr(attrs = { if (l.failed) classes("bad") }) {
                                Td { Text(l.ts.substringAfter('T').take(8)) }
                                Td { Text(l.level) }
                                Td { Text(l.method ?: "") }
                                Td { Text(l.path ?: "") }
                                Td { Text(l.status?.toString() ?: "") }
                                Td { Text(l.ms?.toString() ?: "") }
                            }
                        }
                    }
                }
            }
        }
    }
}
