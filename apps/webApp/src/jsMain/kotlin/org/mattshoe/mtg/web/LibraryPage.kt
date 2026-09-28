package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Prices
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
    showFilters: Boolean = false,
    onToggleFilters: () -> Unit = {},
    onExport: () -> Unit = {},
    complete: Completion = Completion(),
    onComplete: (Completion) -> Unit = {},
    facets: Facets = Facets(),
) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) { H1 { Text("Library") } }

        Div(attrs = { classes("panel") }) {
            Div(attrs = { classes("panel-body") }) {
                AutocompleteField(
                    hint = "Card name",
                    state = complete,
                    onState = { c ->
                        onComplete(c)
                        onState(state.where(state.filters.copy(q = c.term)))
                    },
                    onPick = { onSearch() },
                )
                Div(attrs = { classes("flex-wrap") }) {
                    // A segmented control, not three choice cards. The
                    // `owner-opt` styling is for the wizard's one big
                    // decision per screen; three of them side by side
                    // wrapped onto two lines on a phone.
                    Div(attrs = { classes("seg") }) {
                        listOf("both" to "Both", "matt" to "Matt", "kayla" to "Kayla")
                            .forEach { (slug, label) ->
                                Button(attrs = {
                                    if (state.filters.owner == slug) classes("on")
                                    onClick { onState(state.where(state.filters.copy(owner = slug))) }
                                }) { Text(label) }
                            }
                    }
                    Span(attrs = { classes("spacer") }) {}
                    Button(attrs = {
                        classes("btn", "primary")
                        if (state.busy) disabled()
                        onClick { onSearch() }
                    }) { Text("Search") }
                }
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        if (showFilters) classes("on")
                        onClick { onToggleFilters() }
                    }) { Text(if (showFilters) "Hide filters" else "Filters") }
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        onClick { onExport() }
                    }) { Text("Export decklist") }
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

        if (showFilters) FilterPanel(state.filters, facets) { onState(state.where(it)) }

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
    // The same markup and the same class names the hand-written grid
    // used, so the picture, the free badge and the price sit exactly
    // where the stylesheet already puts them.
    Div(attrs = {
        classes("card")
        onClick { onOpen(card) }
    }) {
        Div(attrs = { classes("card-art") }) {
            // Scryfall addresses art by the id already on the row, so
            // this costs a URL rather than a lookup.
            CardQueries.art(card.scryfallId, "normal")?.let { url ->
                Img(src = url, alt = card.fullName, attrs = {
                    classes("card-img")
                    attr("loading", "lazy")
                    attr("decoding", "async")
                })
            }
            val free = card.free ?: 0
            Div(attrs = { classes("free-badge"); if (free <= 0) classes("none") }) {
                Text(if (free > 0) "$free free" else "in decks")
            }
            Div(attrs = { classes("price-badge") }) { Text(Prices.money(card.price, dash = "")) }
        }
        Div(attrs = { classes("card-meta") }) {
            Span(attrs = { classes("nm") }) { Text(card.fullName) }
            Span(attrs = { classes("sb") }) {
                Span { Text(card.manaCost.orEmpty()) }
                Span(attrs = { classes("qty") }) { Text("×${card.qty}") }
                Span(attrs = { classes("set") }) { Text(card.setCode?.uppercase().orEmpty()) }
            }
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
