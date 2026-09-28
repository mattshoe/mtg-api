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
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
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
    // One rule: anything that changes the filters asks the database
    // again. The page debounces, so typing does not fire per keystroke
    // — and without this, choosing a colour changed the state and left
    // the same hundred cards on screen, which reads as a filter that
    // does nothing.
    fun apply(next: Library) {
        onState(next)
        onSearch()
    }

    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) { H1 { Text("Library") } }

        Div(attrs = { classes("panel") }) {
            // `stack` is what puts air between the rows. Without it
            // every control in here sits flush against the next.
            Div(attrs = { classes("panel-body", "stack") }) {
                AutocompleteField(
                    hint = "Card name",
                    state = complete,
                    onState = { c ->
                        onComplete(c)
                        apply(state.where(state.filters.copy(q = c.term)))
                    },
                    onPick = { onSearch() },
                )
                // No owner control and no Search button up here. Whose
                // collection it is lives in the Collection group with
                // every other filter, and everything applies as it is
                // typed — a button that re-runs what already ran is
                // just a thing to forget to press.
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        if (showFilters) classes("on")
                        onClick { onToggleFilters() }
                    }) { Text(if (showFilters) "Hide filters" else "Filters") }
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        onClick { onExport() }
                    }) { Text("Export") }
                    SortPicker(state, ::apply)
                }
            }
        }

        if (showFilters) FilterPanel(state.filters, facets) { apply(state.where(it)) }

        when {
            state.busy -> Div(attrs = { classes("empty") }) { Text("Searching…") }
            state.error != null -> Div(attrs = { classes("err") }) { Text("Search failed: ${state.error}") }
            state.isEmpty -> Div(attrs = { classes("empty") }) { Text("Nothing matches that.") }
            else -> {
                Div(attrs = { classes("muted", "small") }) {
                    Text("${state.showing.first}–${state.showing.last} of ${state.total}")
                }
                Div(attrs = { classes("grid") }) { state.rows.forEach { Tile(it, onOpen) } }
                Pager(state, ::apply)
            }
        }
    }
}

/**
 * Sort as a dropdown rather than a row of buttons.
 *
 * Fourteen sorts do not fit across a phone, and four of them chosen
 * arbitrarily is a worse answer than all of them behind one control.
 * The arrow beside it flips the direction, which is the other half of
 * the question.
 */
@Composable
private fun SortPicker(state: Library, apply: (Library) -> Unit) {
    // Sits in the same row as Filters and Export. `.field` is full
    // width by default, which is what pushed the direction arrow onto
    // a line of its own and left it stranded in the middle.
    Select(attrs = {
        classes("field", "sort")
        style { property("width", "auto") }
        attr("title", "Sort by")
        onChange { e -> apply(state.sortedBy(Sort.of(e.value ?: ""))) }
    }) {
        Sort.entries.forEach { sort ->
            Option(sort.slug, attrs = {
                if (state.filters.sort == sort) attr("selected", "")
            }) { Text(sort.label) }
        }
    }
    Button(attrs = {
        classes("btn", "sm", "ghost")
        attr("title", if (state.filters.descending) "Largest first" else "Smallest first")
        onClick { apply(state.where(state.filters.copy(descending = !state.filters.descending))) }
    }) { Text(if (state.filters.descending) "↓" else "↑") }
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
