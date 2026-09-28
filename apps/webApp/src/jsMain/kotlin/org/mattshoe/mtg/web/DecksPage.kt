package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Img
import org.mattshoe.mtg.core.CardQueries
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
                    Div(attrs = { classes("deck-grid") }) { decks.forEach { Tile(it, onOpen) } }
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

/** WUBRG pips, or one colourless one. The same markup `util.js` emitted. */
@Composable
private fun Identity(ci: String) {
    Span(attrs = { classes("mana") }) {
        val letters = ci.ifEmpty { "C" }
        letters.forEach { c ->
            Span(attrs = { classes("ms"); attr("data-s", c.toString()) }) { Text(c.toString()) }
        }
    }
}

@Composable
private fun Tile(deck: Deck, onOpen: (Deck) -> Unit) {
    // The same markup the hand-written grid used: the commander's art
    // cropped to a band across the top, then the name and identity.
    Div(attrs = { classes("deck-card"); onClick { onOpen(deck) } }) {
        val art = CardQueries.banner(deck.artId, deck.commanderName)
        Div(attrs = { classes("deck-banner"); if (art == null) classes("none") }) {
            art?.let { Img(src = it, alt = "", attrs = { attr("loading", "lazy") }) }
        }
        Div(attrs = { classes("deck-body") }) {
            Div(attrs = { classes("deck-top") }) {
                Span(attrs = { classes("deck-name") }) { Text(deck.title) }
                deck.bracket?.let {
                    Span(attrs = { classes("tag", "info") }) { Text("bracket $it") }
                }
            }
            Div(attrs = { classes("deck-meta") }) {
                Identity(deck.identity)
                Span(attrs = { classes("cmdr") }) { Text(deck.commanderName ?: "—") }
            }
        }
    }
}
