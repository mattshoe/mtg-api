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
import org.mattshoe.mtg.core.FetchListState

/**
 * A deck list in, where every card is filed out. Sibling of Android's
 * `FetchListScreen`.
 *
 * Matt sorts his binders by type, then colour, then rarity, so the
 * answer is one section per combination of the three, in that order.
 * What goes in which section, and what is missing, is `FetchList`'s.
 */
@Composable
fun FetchListPage(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
) {
    Div(attrs = {
        classes("wrap", "fetch")
        attr("data-fetch", "")
    }) {
        Div(attrs = { classes("panel") }) {
            Div(attrs = { classes("panel-head") }) { H2 { Text("Fetch list") } }
            Div(attrs = { classes("panel-body") }) {
                Div(attrs = { classes("muted", "small") }) {
                    Text("Paste a deck list. Every card comes back filed under its type, colour and rarity.")
                }
                Div(attrs = { classes("field") }) {
                    TextArea(value = state.list, attrs = {
                        id("fetch-list")
                        classes("prose")
                        rows(8)
                        placeholder("1 Sol Ring\n1 Counterspell")
                        onInput { onState(state.typed(it.value)) }
                    })
                }
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        attr("data-fetch-find", "")
                        if (!state.canFind) disabled()
                        onClick { onFind() }
                    }) { Text(if (state.busy) "Finding…" else "Find cards") }
                    if (state.result != null || state.list.isNotEmpty()) {
                        Button(attrs = {
                            classes("btn", "ghost")
                            onClick { onState(state.cleared()) }
                        }) { Text("New list") }
                    }
                }
                state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
            }
        }

        state.result?.let { result ->
            Div(attrs = { classes("panel", "fetch-save") }) {
                Div(attrs = { classes("panel-body", "flex-wrap") }) {
                    Label(forId = "fetch-title") { Text("Title") }
                    Input(InputType.Text, attrs = {
                        id("fetch-title")
                        value(state.title)
                        placeholder("Required to save")
                        onInput { onState(state.titled(it.value)) }
                    })
                    Button(attrs = {
                        classes("btn")
                        attr("data-fetch-save", "")
                        onClick { onSave() }
                    }) { Text("Save") }
                }
            }

            result.sections.forEach { section ->
                Div(attrs = {
                    classes("panel", "fetch-section")
                    attr("data-fetch-section", section.title)
                }) {
                    Div(attrs = { classes("panel-head") }) {
                        H2 { Text(section.title) }
                        Span(attrs = { classes("tag") }) { Text(section.cards.sumOf { it.want }.toString()) }
                    }
                    Div(attrs = { classes("panel-body") }) {
                        section.cards.forEach { card ->
                            Div(attrs = {
                                classes("fetch-card")
                                attr("data-fetch-card", card.name)
                            }) {
                                Div(attrs = { classes("flex-wrap") }) {
                                    Span(attrs = { classes("fetch-qty") }) { Text("${card.want}×") }
                                    Span { Text(card.name) }
                                    Span(attrs = { classes("spacer") }) {}
                                    if (card.have < card.want) {
                                        Span(attrs = { classes("tag", "warn") }) { Text("have ${card.have}") }
                                    }
                                }
                                if (card.alsoCheck.isNotEmpty()) {
                                    Div(attrs = {
                                        classes("muted", "small", "fetch-also")
                                        attr("data-fetch-also", "")
                                    }) { Text(card.alsoCheckLine) }
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
                            }
                        }
                    }
                }
            }
        }

        if (state.saved.isNotEmpty()) {
            Div(attrs = { classes("panel", "fetch-saved") }) {
                Div(attrs = { classes("panel-head") }) { H2 { Text("Saved lists") } }
                Div(attrs = { classes("panel-body") }) {
                    state.saved.forEach { s ->
                        Div(attrs = {
                            classes("flex-wrap", "fetch-saved-row")
                            attr("data-fetch-saved", s.title)
                        }) {
                            Button(attrs = {
                                classes("btn", "ghost", "fetch-open")
                                onClick { onState(state.open(s.id)) }
                            }) { Text(s.title) }
                            Span(attrs = { classes("muted", "small") }) { Text(s.at.take(10)) }
                            Span(attrs = { classes("spacer") }) {}
                            Button(attrs = {
                                classes("btn", "sm", "ghost")
                                attr("aria-label", "Delete ${s.title}")
                                onClick { onState(state.delete(s.id)) }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
