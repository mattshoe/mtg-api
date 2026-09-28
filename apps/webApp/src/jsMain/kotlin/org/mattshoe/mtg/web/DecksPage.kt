package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DecksState

/** Decks, on the web. Sibling of `DecksScreen`. */
@Composable
fun DecksPage(
    state: DecksState,
    onOpen: (Deck) -> Unit,
    onClose: () -> Unit,
    admin: Boolean = false,
    onNew: () -> Unit = {},
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
) {
    Div(attrs = { classes("wrap") }) {
        val open = state.open
        if (open == null) {
            Div(attrs = { classes("page-head") }) {
                H1 { Text("Decks") }
                if (admin) {
                    Button(attrs = {
                        classes("btn", "primary")
                        onClick { onNew() }
                    }) { Text("New deck") }
                }
            }
            when {
                state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                state.error != null -> Div(attrs = { classes("err") }) { Text("Could not load decks: ${state.error}") }
                state.decks.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No decks yet.") }
                else -> state.byOwner.forEach { (owner, decks) ->
                    H2 { Text(owner.replaceFirstChar(Char::uppercase)) }
                    Div(attrs = { classes("grid") }) { decks.forEach { Tile(it, onOpen) } }
                }
            }
        } else {
            Button(attrs = { classes("btn", "ghost"); onClick { onClose() } }) { Text("← Decks") }
            Div(attrs = { classes("page-head") }) { H1 { Text(open.name) } }
            Div(attrs = { classes("muted", "small") }) {
                Text(
                    listOfNotNull(
                        open.commanderName,
                        open.bracket?.let { "Bracket $it" },
                        open.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                    ).joinToString(" · "),
                )
            }
            Div(attrs = { classes("muted", "small") }) { Text("${state.totalCards} cards") }
            if (admin) {
                // Both of these move real cards, and both show the
                // server's own dry run before they are allowed to.
                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "sm")
                        onClick { onEdit(open) }
                    }) { Text("Edit list") }
                    Button(attrs = {
                        classes("btn", "sm", "danger")
                        onClick { onDisassemble(open) }
                    }) { Text("Disassemble") }
                }
            }
            if (state.gaps.isNotEmpty()) {
                Div(attrs = { classes("tag", "bad") }) { Text("${state.gaps.size} not owned") }
            }
            Div(attrs = { classes("panel") }) {
                Div(attrs = { classes("panel-body") }) {
                    state.cards.forEach { c ->
                        Div(attrs = { classes("flex-wrap", "small") }) {
                            Span { Text("${c.qty}×") }
                            Span(attrs = { classes("t-name") }) { Text(c.name) }
                            if (c.owned < c.qty) {
                                Span(attrs = { classes("tag", "bad", "mini") }) { Text("has ${c.owned}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(deck: Deck, onOpen: (Deck) -> Unit) {
    Div(attrs = { classes("deck-tile"); onClick { onOpen(deck) } }) {
        Div(attrs = { classes("t-name") }) { Text(deck.name) }
        Div(attrs = { classes("muted", "small") }) {
            Text(
                listOfNotNull(
                    deck.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                    deck.commanderName,
                    deck.bracket?.let { "Bracket $it" },
                ).joinToString(" · "),
            )
        }
        Button(attrs = { classes("btn", "sm", "ghost"); onClick { onOpen(deck) } }) { Text("Open") }
    }
}
