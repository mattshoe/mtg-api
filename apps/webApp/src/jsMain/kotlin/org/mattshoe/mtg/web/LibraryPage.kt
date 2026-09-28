package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Sort

/**
 * The Library, on the web.
 *
 * Sibling of `LibraryScreen` on Android. Neither decides what page it is
 * on or whether there is a next one — `Library` does, from the shared
 * core, so the two cannot page differently.
 *
 * Class names are the ones the existing stylesheet already defines, so
 * this looks like the rest of the site without a second stylesheet to
 * keep in step.
 */
@Composable
fun LibraryPage(
    state: Library,
    onState: (Library) -> Unit,
    onSearch: () -> Unit,
    onOpen: (CardRow) -> Unit,
) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) { H1 { Text("Library") } }

        Div(attrs = { classes("panel") }) {
            Div(attrs = { classes("panel-body") }) {
                Input(type = InputType.Text) {
                    classes("field")
                    placeholder("Card name")
                    value(state.filters.q)
                    onInput { onState(state.where(state.filters.copy(q = it.value))) }
                }
                Div(attrs = { classes("flex-wrap") }) {
                    listOf("both" to "Both", "matt" to "Matt", "kayla" to "Kayla").forEach { (slug, label) ->
                        Button(attrs = {
                            classes("owner-opt")
                            if (state.filters.owner == slug) classes("on")
                            onClick { onState(state.where(state.filters.copy(owner = slug))) }
                        }) { Text(label) }
                    }
                    Span(attrs = { classes("spacer") }) {}
                    Button(attrs = {
                        classes("btn", "primary")
                        if (state.busy) disabled()
                        onClick { onSearch() }
                    }) { Text("Search") }
                }
                Div(attrs = { classes("flex-wrap") }) {
                    listOf(Sort.PRICE, Sort.NAME, Sort.CMC, Sort.QTY).forEach { sort ->
                        val on = state.filters.sort == sort
                        Button(attrs = {
                            classes("btn", "sm", "ghost")
                            if (on) classes("on")
                            onClick { onState(state.sortBy(sort)); onSearch() }
                        }) {
                            Text(sort.label + if (on) (if (state.filters.descending) " ↓" else " ↑") else "")
                        }
                    }
                }
            }
        }

        when {
            state.busy -> Div(attrs = { classes("empty") }) { Text("Searching…") }
            state.error != null -> Div(attrs = { classes("err") }) { Text("Search failed: ${state.error}") }
            state.isEmpty -> Div(attrs = { classes("empty") }) { Text("Nothing matches that.") }
            else -> {
                Div(attrs = { classes("muted", "small") }) {
                    Text("${state.showing.first}–${state.showing.last} of ${state.total}")
                }
                Div(attrs = { classes("grid") }) { state.rows.forEach { Tile(it, onOpen) } }
                Pager(state) { onState(it); onSearch() }
            }
        }
    }
}

@Composable
private fun Tile(card: CardRow, onOpen: (CardRow) -> Unit) {
    Div(attrs = {
        classes("card-tile")
        onClick { onOpen(card) }
    }) {
        Div(attrs = { classes("t-name") }) { Text(card.fullName) }
        Div(attrs = { classes("muted", "small") }) {
            Text(listOfNotNull(card.typeLine, card.setCode?.uppercase(), card.manaCost).joinToString(" · "))
        }
        Div(attrs = { classes("flex-wrap", "small") }) {
            Span(attrs = { classes("tag", "mini") }) { Text("${card.qty}× ${card.owner}") }
            card.free?.let { Span(attrs = { classes("tag", "mini") }) { Text("$it free") } }
            card.price?.let { Span(attrs = { classes("tag", "mini") }) { Text("$$it") } }
        }
    }
}

@Composable
private fun Pager(state: Library, go: (Library) -> Unit) {
    Div(attrs = { classes("flex-wrap") }) {
        Button(attrs = {
            classes("btn", "ghost")
            if (!state.hasPrev) disabled()
            onClick { go(state.prev()) }
        }) { Text("← Previous") }
        Span(attrs = { classes("muted", "small") }) { Text("Page ${state.page} of ${state.pages}") }
        Button(attrs = {
            classes("btn", "ghost")
            if (!state.hasNext) disabled()
            onClick { go(state.next()) }
        }) { Text("Next →") }
    }
}
