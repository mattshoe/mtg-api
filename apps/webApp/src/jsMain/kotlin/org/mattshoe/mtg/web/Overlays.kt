package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.mattshoe.mtg.core.Cheatsheet
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.PaletteState

/**
 * The overlays: quick find and the query box reference.
 *
 * Both have Android siblings, and both are dismissed by the shared
 * overlay stack rather than by their own idea of what back means.
 */
@Composable
fun PaletteDialog(
    state: PaletteState,
    onState: (PaletteState) -> Unit,
    onOpen: (Found) -> Unit,
    onClose: () -> Unit,
) {
    Div(attrs = {
        classes("palette-scrim")
        onClick { onClose() }
    }) {
        Div(attrs = {
            classes("palette")
            // The scrim closes; a press inside must not.
            onClick { it.stopPropagation() }
        }) {
            Input(type = InputType.Text) {
                classes("field")
                id("palette-input")
                placeholder("Find a card")
                value(state.term)
                onInput { onState(state.typed(it.value)) }
            }
            Ul(attrs = { classes("palette-list") }) {
                state.items.forEachIndexed { i, row ->
                    Li(attrs = {
                        if (i == state.active) classes("on")
                        onClick { onOpen(row) }
                    }) {
                        Div { Text(row.name) }
                        Div(attrs = { classes("muted", "small") }) { Text(row.typeLine.orEmpty()) }
                        Span(attrs = { classes("tag", "mini") }) { Text("${row.qty}× ${row.owner}") }
                    }
                }
            }
            if (state.items.isEmpty() && state.term.isNotBlank() && !state.busy) {
                Div(attrs = { classes("empty") }) { Text("Nothing matches that.") }
            }
        }
    }
}

/** Everything the query box understands, in one overlay. */
@Composable
fun CheatsheetDialog(onClose: () -> Unit) {
    Div(attrs = {
        classes("palette-scrim")
        onClick { onClose() }
    }) {
        Div(attrs = {
            classes("palette", "wide")
            onClick { it.stopPropagation() }
        }) {
            Div(attrs = { classes("panel-head") }) {
                H2 { Text("Query box") }
                Span(attrs = { classes("spacer") }) {}
                Button(attrs = {
                    classes("btn", "sm", "ghost", "icon-only")
                    attr("title", "Close")
                    attr("aria-label", "Close")
                    onClick { onClose() }
                }) { CloseIcon() }
            }
            Div(attrs = { classes("panel-body", "stack") }) {
                Div(attrs = { classes("small", "muted") }) { Text(Cheatsheet.PREAMBLE) }
                Cheatsheet.groups.forEach { group ->
                    H3 { Text(group.title) }
                    Div(attrs = { classes("table-wrap") }) {
                        group.keys.forEach { key ->
                            Div(attrs = { classes("flex-wrap", "small") }) {
                                Code { Text(key.keys) }
                                Span(attrs = { classes("muted") }) { Text(key.what) }
                                Code(attrs = { classes("small") }) { Text(key.example) }
                            }
                        }
                    }
                }
                H3 { Text("is: values (${Cheatsheet.isValues.size})") }
                Div(attrs = { classes("chips") }) {
                    Cheatsheet.isValues.forEach {
                        Span(attrs = { classes("chip", "mini") }) { Text(it) }
                    }
                }
            }
        }
    }
}
