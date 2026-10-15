package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchCard
import org.mattshoe.mtg.core.FetchListState

/**
 * A deck list in, where every card is filed out. Sibling of Android's
 * `FetchListScreen`.
 *
 * Two pages. Matt: "the root page should have the entry box and the
 * saved fetches", then "searching should take you to a NEW page" where
 * he can "check off cards as i find them". `#/fetch` is the first and
 * `#/fetch/list` the second; which one is on screen is `AppState`'s.
 *
 * Matt sorts his binders by type, then colour, then rarity, so the
 * answer is one section per combination of the three, in that order.
 * What goes in which section, and what is missing, is `FetchList`'s.
 */
@Composable
fun FetchListPage(
    state: FetchListState,
    onList: Boolean,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onPeek: (FetchCard) -> Unit = {},
) {
    Div(attrs = {
        classes("wrap", "fetch")
        attr("data-fetch", if (onList) "list" else "root")
    }) {
        if (onList) FetchListOpen(state, onState, onFind, onSave, onBack, onPeek)
        else FetchListRoot(state, onState, onFind, onOpen)
    }
}

/** The entry box, and every list kept. */
@Composable
private fun FetchListRoot(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) { H2 { Text("Fetch list") } }
        Div(attrs = { classes("panel-body") }) {
            Div(attrs = { classes("muted", "small") }) {
                Text("Paste a deck list. Every card comes back filed under its type, colour and rarity.")
            }
            Div(attrs = { classes("field") }) {
                TextArea(value = state.draft, attrs = {
                    id("fetch-list")
                    classes("prose")
                    rows(8)
                    placeholder("1 Sol Ring\n1 Counterspell")
                    onInput { onState(state.drafted(it.value)) }
                })
            }
            Div(attrs = { classes("flex-wrap") }) {
                Button(attrs = {
                    classes("btn", "primary")
                    attr("data-fetch-find", "")
                    if (!state.canStart) disabled()
                    onClick { onFind() }
                }) { Text("Find cards") }
            }
        }
    }

    val unsaved = state.unsaved
    if (state.saved.isNotEmpty() || unsaved) {
        Div(attrs = { classes("panel", "fetch-saved") }) {
            Div(attrs = { classes("panel-head") }) { H2 { Text("Saved lists") } }
            Div(attrs = { classes("panel-body") }) {
                if (unsaved) {
                    Div(attrs = {
                        classes("flex-wrap", "fetch-saved-row")
                        attr("data-fetch-current", "")
                    }) {
                        Button(attrs = {
                            classes("btn", "ghost", "fetch-open")
                            onClick { onOpen("") }
                        }) { Text(FetchListState.UNSAVED) }
                        Span(attrs = { classes("muted", "small") }) { Text(state.progress) }
                    }
                }
                state.saved.forEach { s ->
                    Div(attrs = {
                        classes("flex-wrap", "fetch-saved-row")
                        attr("data-fetch-saved", s.title)
                    }) {
                        Button(attrs = {
                            classes("btn", "ghost", "fetch-open")
                            onClick { onOpen(s.id) }
                        }) { Text(s.title) }
                        Span(attrs = { classes("muted", "small") }) { Text(s.at.take(10)) }
                        Span(attrs = { classes("spacer") }) {}
                        Button(attrs = {
                            classes("btn", "sm", "ghost", "icon-only")
                            attr("aria-label", "Delete " + s.title)
                            attr("title", "Delete")
                            onClick { onState(state.delete(s.id)) }
                        }) { TrashIcon() }
                    }
                }
            }
        }
    }
}

/** One list: the loading view, then the sections with a box per card. */
@Composable
private fun FetchListOpen(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onPeek: (FetchCard) -> Unit = {},
) {
    Div(attrs = { classes("panel", "fetch-top") }) {
        Div(attrs = { classes("panel-head") }) {
            Button(attrs = {
                classes("btn", "ghost", "sm")
                attr("data-fetch-back", "")
                onClick { onBack() }
            }) { Text("‹ Lists") }
            H2 { Text(state.title.ifBlank { "Fetch list" }) }
            Span(attrs = { classes("spacer") }) {}
            if (state.list.isNotBlank() && state.editing == null) {
                Button(attrs = {
                    classes("btn", "icon-only")
                    attr("data-fetch-edit", "")
                    attr("aria-label", "Edit")
                    attr("title", "Edit")
                    if (state.busy) disabled()
                    onClick { onState(state.edit()) }
                }) { EditIcon() }
            }
        }
        Div(attrs = { classes("panel-body") }) {
            state.editing?.let { text ->
                Div(attrs = { classes("field") }) {
                    TextArea(value = text, attrs = {
                        id("fetch-edit")
                        classes("prose")
                        rows(8)
                        onInput { onState(state.editTyped(it.value)) }
                    })
                }
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        attr("data-fetch-refind", "")
                        if (DeckList.entries(text).isEmpty()) disabled()
                        onClick { onFind() }
                    }) { Text("Find cards") }
                    Button(attrs = {
                        classes("btn", "ghost")
                        onClick { onState(state.cancelEdit()) }
                    }) { Text("Cancel") }
                }
            }
            if (state.list.isBlank()) {
                Div(attrs = { classes("muted") }) { Text(FetchListState.NOTHING_OPEN) }
            }
            state.error?.let { msg ->
                Div(attrs = {
                    classes("err")
                    attr("data-fetch-err", "")
                }) { Text(msg) }
                if (state.list.isNotBlank() && state.editing == null && !state.busy) {
                    Button(attrs = {
                        classes("btn")
                        attr("data-fetch-retry", "")
                        onClick { onFind() }
                    }) { Text("Try again") }
                }
            }
            if (state.result != null && !state.busy) {
                Div(attrs = { classes("flex-wrap", "fetch-save-row") }) {
                    Span(attrs = {
                        classes("tag", "info")
                        attr("data-fetch-progress", "")
                    }) { Text(state.progress) }
                    Span(attrs = { classes("spacer") }) {}
                    Label(forId = "fetch-title", attrs = { classes("muted", "small") }) { Text("Title") }
                    Clearable(state.title, { onState(state.titled("")) }) {
                        Input(InputType.Text, attrs = {
                            id("fetch-title")
                            value(state.title)
                            placeholder("Required to save")
                            onInput { onState(state.titled(it.value)) }
                        })
                    }
                    Button(attrs = {
                        classes("btn")
                        attr("data-fetch-save", "")
                        onClick { onSave() }
                    }) { Text("Save") }
                }
            }
            if (state.checking && !state.busy) {
                Div(attrs = {
                    classes("muted", "small")
                    attr("data-fetch-checking", "")
                }) { Text(FetchListState.CHECKING) }
            }
        }
    }

    if (state.busy) {
        FetchLoading(state.entries.size)
        return
    }

    val result = state.result ?: return
    result.sections.forEach { section ->
        Div(attrs = {
            classes("panel", "fetch-section")
            attr("data-fetch-section", section.title)
        }) {
            Div(attrs = { classes("panel-head") }) {
                // The colour as its mana symbols, in the order the card
                // prints them.
                H2(attrs = { classes("fetch-head") }) {
                    Text(section.type.label)
                    Span(attrs = { classes("fetch-symbols") }) {
                        section.symbols.forEach { ManaPip(it, "fetch-sym") }
                    }
                    Text(section.rarity.label)
                }
                Span(attrs = { classes("tag") }) { Text(section.cards.sumOf { it.want }.toString()) }
            }
            Div(attrs = { classes("panel-body") }) {
                section.cards.forEach { card ->
                    val ticked = state.isTicked(card.name)
                    Div(attrs = {
                        classes("fetch-card")
                        if (ticked) classes("ticked")
                        attr("data-fetch-card", card.name)
                    }) {
                        // The box ticks it. Anywhere else on the row
                        // opens the carousel over the list at this
                        // card. Matt: "tapping the row (outside
                        // checkbox) should show the carousel".
                        Div(attrs = {
                            classes("flex-wrap", "fetch-tick")
                            attr("data-fetch-row", card.name)
                            onClick { onPeek(card) }
                        }) {
                            Input(InputType.Checkbox, attrs = {
                                attr("data-fetch-tick", card.name)
                                attr("aria-label", "Found " + card.name)
                                checked(ticked)
                                onClick { it.stopPropagation() }
                                onChange { onState(state.tick(card.name)) }
                            })
                            Span(attrs = { classes("fetch-qty") }) { Text("${card.want}×") }
                            Span(attrs = { classes("fetch-name") }) { Text(card.name) }
                            Span(attrs = { classes("spacer") }) {}
                            if (card.have < card.want) {
                                Span(attrs = { classes("tag", "warn") }) { Text("have ${card.have}") }
                            }
                            RemoveButton(card.name) { onState(state.remove(card)) }
                        }
                        if (card.alsoCheck.isNotEmpty()) {
                            Div(attrs = {
                                classes("muted", "small", "fetch-also")
                                attr("data-fetch-also", "")
                            }) { Text(card.alsoCheckLine) }
                        }
                        // No copy in bulk: it is in a deck, so say which.
                        card.decksLine?.let { line ->
                            Div(attrs = {
                                classes("muted", "small", "fetch-also")
                                attr("data-fetch-decks", "")
                            }) { Text(line) }
                        }
                    }
                }
            }
        }
    }

    if (result.missing.isNotEmpty()) {
        Div(attrs = {
            classes("panel", "fetch-section", "fetch-missing")
            attr("data-fetch-section", "Missing")
        }) {
            Div(attrs = { classes("panel-head") }) {
                H2 { Text("Not in your collection") }
                Span(attrs = { classes("tag", "bad") }) { Text(result.missing.sumOf { it.short }.toString()) }
            }
            Div(attrs = { classes("panel-body") }) {
                result.missing.forEach { m ->
                    Div(attrs = {
                        classes("fetch-card", "flex-wrap")
                        attr("data-fetch-missing", m.name)
                    }) {
                        Span(attrs = { classes("fetch-qty") }) { Text("${m.short}×") }
                        Span { Text(m.name) }
                        Span(attrs = { classes("spacer") }) {}
                        if (m.have > 0) Span(attrs = { classes("muted", "small") }) { Text("you have ${m.have}") }
                        RemoveButton(m.name) { onState(state.remove(m)) }
                    }
                }
            }
        }
    }
}

/**
 * Takes the card off the list, text and all. Matt: "make sure doing so
 * actually edits the deck list text as well". Its own press, so it
 * opens no carousel on the way.
 */
@Composable
private fun RemoveButton(name: String, onRemove: () -> Unit) {
    Button(attrs = {
        classes("btn", "sm", "ghost", "icon-only")
        attr("data-fetch-remove", name)
        attr("aria-label", "Remove $name from the list")
        attr("title", "Remove")
        onClick {
            it.stopPropagation()
            onRemove()
        }
    }) { TrashIcon() }
}

/**
 * While the collection is being asked: the five colours going round a
 * ring, lighting up in turn, and how many cards it is looking for.
 */
@Composable
private fun FetchLoading(cards: Int) {
    Div(attrs = {
        classes("panel", "fetch-loading")
        attr("data-fetch-loading", "")
        attr("role", "status")
    }) {
        Div(attrs = { classes("fetch-orbit") }) {
            Div(attrs = { classes("fetch-ring") }) {}
            listOf("W", "U", "B", "R", "G").forEachIndexed { i, letter ->
                Span(attrs = {
                    classes("fetch-orb")
                    attr("style", "--i:$i")
                }) { ManaPip(letter) }
            }
        }
        Div(attrs = { classes("fetch-loading-line") }) { Text(FetchListState.finding(cards)) }
    }
}
