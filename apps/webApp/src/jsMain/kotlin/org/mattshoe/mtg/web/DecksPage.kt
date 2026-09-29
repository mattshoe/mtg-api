package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Img
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
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
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
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
                    // A group, not a loose heading followed by a grid.
                    // The two people's shelves have to look like two
                    // shelves.
                    Div(attrs = { classes("owner-group") }) {
                        Div(attrs = { classes("owner-head") }) {
                            H2 { Text(owner.replaceFirstChar(Char::uppercase)) }
                            Span(attrs = { classes("count") }) {
                                Text("${decks.size} " + if (decks.size == 1) "deck" else "decks")
                            }
                        }
                        Div(attrs = { classes("deck-grid") }) { decks.forEach { Tile(it, onOpen) } }
                    }
                }
            }
        } else {
            // The back button, the name and the two admin actions are
            // one header row rather than four things stacked flush.
            Div(attrs = { classes("page-head") }) {
                Button(attrs = {
                    classes("btn", "sm", "ghost")
                    onClick { onClose() }
                }) { Text("← Decks") }
                H1 { Text(open.name) }
                Span(attrs = { classes("sub") }) {
                    Text(
                        listOfNotNull(
                            open.commanderName,
                            open.bracket?.let { "Bracket $it" },
                            open.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                        ).joinToString(" · "),
                    )
                }
                Span(attrs = { classes("spacer") }) {}
                if (admin) {
                    // Both of these move real cards, and both show the
                    // server's own dry run before they are allowed to.
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

            Banner(open, state)

            Div(attrs = { classes("stack") }) {
                Div(attrs = { classes("flex-wrap", "small") }) {
                    Span(attrs = { classes("tag", "mini") }) { Text("${state.totalCards} cards") }
                    if (state.gaps.isNotEmpty()) {
                        Span(attrs = { classes("tag", "bad", "mini") }) {
                            Text("${state.gaps.size} not owned")
                        }
                    }
                }
                // By type, in the order every deck list is written in,
                // alphabetical inside each section. The grouping is in
                // the core so the phone cannot sort it differently.
                state.byType.forEach { (group, cards) ->
                    Div(attrs = { classes("panel") }) {
                        Div(attrs = { classes("panel-head") }) {
                            H2 { Text(group.title) }
                            Span(attrs = { classes("spacer") }) {}
                            Span(attrs = { classes("tag", "mini") }) {
                                Text("${cards.sumOf { it.qty }}")
                            }
                        }
                        Div(attrs = { classes("panel-body") }) {
                            cards.forEach { CardLine(it, open.owner, onOpenCard) }
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

/**
 * The commander, across the top.
 *
 * Its own art rather than the deck tile's, because by the time the
 * deck is open the list has a real printing to take one from — and a
 * deck with no commander (a 60-card list) gets no band at all rather
 * than an empty grey one.
 */
@Composable
private fun Banner(deck: Deck, state: DecksState) {
    val cmdr = state.commander
    val art = cmdr?.art ?: CardQueries.banner(deck.artId, deck.commanderName) ?: return
    Div(attrs = { classes("deck-hero") }) {
        Img(src = art, alt = "", attrs = { attr("loading", "lazy") })
        Div(attrs = { classes("deck-hero-wash") }) {}
        Div(attrs = { classes("deck-hero-text") }) {
            Span(attrs = { classes("who") }) { Text(cmdr?.name ?: deck.commanderName.orEmpty()) }
            Span(attrs = { classes("what") }) {
                Text(
                    listOfNotNull(
                        deck.bracket?.let { "Bracket $it" },
                        deck.colorPips.takeIf { it.isNotEmpty() }?.joinToString(""),
                        "${state.totalCards} cards",
                    ).joinToString(" · "),
                )
            }
        }
    }
}

/**
 * One card in the list: a square crop of its art, the name, and how
 * many. The whole row opens the card, the way the grid tiles do.
 */
@Composable
private fun CardLine(card: DeckCard, owner: String, onOpen: (DeckCard, String) -> Unit) {
    Div(attrs = {
        classes("deck-line")
        attr("role", "button")
        attr("tabindex", "0")
        attr("title", card.name)
        onClick { onOpen(card, owner) }
        onKeyDown { e -> if (e.key == "Enter" || e.key == " ") onOpen(card, owner) }
    }) {
        // `art_crop` is a landscape band; the square comes from the box
        // cropping it, which is why there is a wrapper rather than a
        // bare img.
        Div(attrs = { classes("thumb") }) {
            card.art?.let { Img(src = it, alt = "", attrs = { attr("loading", "lazy") }) }
        }
        Div(attrs = { classes("line-text") }) {
            Span(attrs = { classes("t-name") }) { Text(card.name) }
            card.typeLine?.takeIf { it.isNotBlank() }?.let {
                Span(attrs = { classes("line-type") }) { Text(it) }
            }
        }
        if (card.owned < card.qty) {
            Span(attrs = { classes("tag", "bad", "mini") }) { Text("has ${card.owned}") }
        }
        Span(attrs = { classes("num") }) { Text("${card.qty}×") }
    }
}
