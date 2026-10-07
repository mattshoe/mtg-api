package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Img
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.TokenCard
import org.mattshoe.mtg.core.Tweak

/** Decks, on the web. Sibling of `DecksScreen`. */
@Composable
fun DecksPage(
    state: DecksState,
    onOpen: (Deck) -> Unit,
    onClose: () -> Unit,
    admin: Boolean = false,
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
    /** Rename the open deck. The slug moves with the name. */
    onRename: (Deck) -> Unit = {},
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
    /** Maintenance, one card at a time, without leaving the page. */
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
) {
    Div(attrs = { classes("wrap") }) {
        val open = state.open
        if (open == null) {
            // No New deck button. A deck is started from the entry
            // wizard's first question now, the same as on the phone —
            // Matt: "get rid of the one on the decks list page."
            when {
                state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                state.error != null -> Div(attrs = { classes("err") }) { Text("Could not load decks: ${state.error}") }
                state.decks.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No decks yet.") }
                // One shelf. It was a group per owner with the owner's
                // name over it, which was two shelves when there were
                // two collections in one database and is one shelf
                // with somebody's name pointlessly over it now that
                // the page is their collection.
                else -> {
                    Div(attrs = { classes("owner-head") }) {
                        Span(attrs = { classes("count") }) {
                            Text("${state.decks.size} " + if (state.decks.size == 1) "deck" else "decks")
                        }
                    }
                    Div(attrs = { classes("deck-grid") }) { state.decks.forEach { Tile(it, onOpen) } }
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
                Span(attrs = { classes("spacer") }) {}
                ShareMenu(onShare)
                if (admin) {
                    // Both of these move real cards, and both show the
                    // server's own dry run before they are allowed to.
                    Button(attrs = {
                        classes("btn", "sm")
                        onClick { onEdit(open) }
                    }) { Text("Edit list") }
                    Button(attrs = {
                        classes("btn", "sm")
                        onClick { onRename(open) }
                    }) { Text("Rename") }
                    Button(attrs = {
                        classes("btn", "sm", "danger")
                        onClick { onDisassemble(open) }
                    }) { Text("Disassemble") }
                }
            }

            Banner(open, state)

            Div(attrs = { classes("stack") }) {
                DeckStatsPanel(DeckAnalysis.of(state.cards), open.guild)
                // By type, in the order every deck list is written in,
                // alphabetical inside each section. The grouping is in
                // the core so the phone cannot sort it differently.
                if (admin) {
                    Div(attrs = { classes("panel") }) {
                        Div(attrs = { classes("panel-body") }) {
                            Button(attrs = {
                                classes("btn", "primary", "wide")
                                onClick { onAddCard() }
                            }) { Text("+ Add a card") }
                        }
                    }
                }

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
                            cards.forEach { CardLine(it, open.owner, onOpenCard, admin, onTweak) }
                        }
                    }
                }

                Tokens(state)
            }
        }
    }
}

/** WUBRG, as the symbols rather than letters in circles. */
@Composable
private fun Identity(ci: String) {
    Span(attrs = { classes("mana-cost") }) {
        ci.ifEmpty { "C" }.forEach { c -> ManaPip(c.toString()) }
    }
}

@Composable
private fun Tile(deck: Deck, onOpen: (Deck) -> Unit) {
    // The same markup the hand-written grid used: the commander's art
    // cropped to a band across the top, then the name and identity.
    // Reachable by keyboard, the same way the card row below is. A bare
    // clickable `div` is invisible to Tab and to a screen reader, and
    // the deck grid is the only way into a deck.
    Div(attrs = {
        classes("deck-card")
        attr("role", "button")
        attr("tabindex", "0")
        attr("title", deck.title)
        onClick { onOpen(deck) }
        onKeyDown { e -> if (e.key == "Enter" || e.key == " ") onOpen(deck) }
    }) {
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
                // What the pips are called. Matt: "Having that
                // somewhere on the deck card too would be nice."
                deck.guild?.let { Span(attrs = { classes("guild") }) { Text(it) } }
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
            Span(attrs = { classes("who") }) { Text(cmdr?.shown ?: deck.commanderName.orEmpty()) }
            Span(attrs = { classes("what") }) {
                Text(
                    listOfNotNull(
                        deck.bracket?.let { "Bracket $it" },
                        deck.guild,
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
private fun CardLine(
    card: DeckCard,
    owner: String,
    onOpen: (DeckCard, String) -> Unit,
    admin: Boolean = false,
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
) {
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
            Div(attrs = { classes("line-top") }) {
                // The cost is not what a deck list is read for, and on
                // a phone it was taking the room the name needed.
                Span(attrs = { classes("t-name") }) { Text(card.shown) }
            }
            card.knownTypeLine?.takeIf { it.isNotBlank() }?.let {
                Span(attrs = { classes("line-type") }) { Text(it) }
            }
        }
        if (card.short > 0) {
            Span(attrs = { classes("tag", "bad", "mini") }) { Text("has ${card.owned}") }
        }
        Span(attrs = { classes("num") }) { Text("${card.qty}×") }
        // Maintenance lives on the row the card is on. The row still
        // opens the card, so each of these has to keep the press to
        // itself.
        // One button, not three: three marks on every row of a
        // hundred-card list left no room for the card's own name.
        // What to do is asked inside.
        if (admin) {
            Button(attrs = {
                classes("row-act")
                attr("title", "Change this card")
                attr("aria-label", "Change ${card.shown}")
                onClick { e -> e.stopPropagation(); onTweak(card, null) }
                // Enter and space are how this button is pressed, and
                // they are also what the row is listening for. Without
                // this the keyboard opened the card page behind the
                // sheet every time.
                onKeyDown { e -> if (e.key == "Enter" || e.key == " ") e.stopPropagation() }
            }) { Text("⋯") }
        }
    }
}



/**
 * The tokens the deck makes, below the list, as cards.
 *
 * Real printed tokens with their own art rather than a description
 * read out of the rules text — Scryfall names them in `all_parts`,
 * which is the authoritative answer. Laid out the same as every
 * other group so the list reads as one thing.
 */
@Composable
private fun Tokens(state: DecksState) {
    if (state.tokens.isEmpty()) return
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("Tokens") }
            Span(attrs = { classes("spacer") }) {}
            Span(attrs = { classes("tag", "mini") }) { Text("${state.tokens.size}") }
        }
        Div(attrs = { classes("panel-body") }) {
            state.tokens.forEach { token -> TokenLine(token) }
        }
        Div(attrs = { classes("panel-body") }) {
            Div(attrs = { classes("muted", "small") }) {
                Text("Made by the cards in this deck. You will want these to hand — ")
                Text("each one goes to TCGplayer.")
            }
        }
    }
}

/**
 * One token, linking out to where you can buy it.
 *
 * An anchor when Scryfall has a listing and a plain row when it does
 * not — a token from a set nobody sells singles of should stay a row
 * rather than become a link to nowhere. It leaves the site, so it
 * says so: a new tab, `rel=noopener`, and the arrow the stylesheet
 * already uses for a link that goes away.
 */
@Composable
private fun TokenLine(token: TokenCard) {
    val body: @Composable () -> Unit = {
        Div(attrs = { classes("thumb") }) {
            token.art?.let { Img(src = it, alt = "", attrs = { attr("loading", "lazy") }) }
        }
        Div(attrs = { classes("line-text") }) {
            Div(attrs = { classes("line-top") }) {
                Span(attrs = { classes("t-name") }) { Text(token.name) }
                token.stats?.let {
                    Span(attrs = { classes("tag", "mini", "mono") }) { Text(it) }
                }
                // Two 1/1 Warriors that differ only by colour are two
                // tokens, and without this they are two identical rows.
                Span(attrs = { classes("mana-cost") }) {
                    token.colors.ifEmpty { "C" }.forEach { ManaPip(it.toString(), "sm") }
                }
            }
            Span(attrs = { classes("line-type") }) { Text(token.shortType) }
        }
        Span(attrs = { classes("num") }) { Text("${token.madeBy}×") }
    }

    val shop = token.tcgplayer
    if (shop == null) {
        Div(attrs = { classes("deck-line"); attr("title", token.name) }) { body() }
    } else {
        A(href = shop, attrs = {
            classes("deck-line", "away")
            target(ATarget.Blank)
            attr("rel", "noopener noreferrer")
            attr("title", "Buy ${token.name} on TCGplayer")
        }) { body() }
    }
}

/**
 * Share, with something to say about what.
 *
 * A deck is worth handing over two ways: as a link to this page, and
 * as the list itself for somebody to paste into their own builder.
 * Each can go to the clipboard or come down as a file, so the four
 * are a menu rather than four buttons crowding the header.
 */
@Composable
private fun ShareMenu(onShare: (ShareWhat, ExportTo) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Div(attrs = { classes("menu-anchor") }) {
        Button(attrs = {
            classes("btn", "sm", "ghost", "icon-only")
            attr("title", "Share this deck")
            attr("aria-label", "Share this deck")
            attr("aria-expanded", open.toString())
            onClick { open = !open }
        }) { ShareIcon() }

        if (open) {
            // A press anywhere else closes it. A backdrop rather than
            // a document listener, so there is nothing to unregister.
            Div(attrs = {
                classes("nav-backdrop")
                onClick { open = false }
            }) {}
        }

        Div(attrs = {
            classes("app-menu", "from-right")
            if (open) classes("open")
        }) {
            ShareWhat.entries.forEach { what ->
                Div(attrs = { classes("app-menu-group") }) { Text(what.label) }
                ExportTo.entries.forEach { where ->
                    Button(attrs = {
                        classes("app-tab")
                        onClick { open = false; onShare(what, where) }
                    }) { Text(where.label) }
                }
            }
        }
    }
}
