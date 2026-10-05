package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.mattshoe.mtg.core.LogsState

/**
 * The server log, on the web. Sibling of `LogsScreen`.
 *
 * This file was `ConsolePage.kt` and held the query console as well;
 * the console is gone and the log viewer is what is left of it.
 */
@Composable
fun LogsPage(state: LogsState, onState: (LogsState) -> Unit) {
    Div(attrs = { classes("wrap") }) {
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
