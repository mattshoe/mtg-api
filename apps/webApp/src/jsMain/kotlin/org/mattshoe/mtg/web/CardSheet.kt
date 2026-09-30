package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Prices

/**
 * One card, opened.
 *
 * Sibling of `CardSheet` on Android. Both are handed a `CardDetail` and
 * neither works out how many copies are spare — `free` is on the state,
 * so the number cannot come out different on a phone.
 */
@Composable
fun CardSheet(card: CardDetail, onShare: () -> Unit = {}, onClose: () -> Unit) {
    Div(attrs = {
        classes("drawer-scrim")
        onClick { onClose() }
    })
    Div(attrs = { classes("drawer") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text(card.name) }
            Span(attrs = { classes("spacer") }) {}
            Button(attrs = {
                classes("btn", "sm", "ghost", "icon-only")
                attr("title", "Copy a link to this card")
                attr("aria-label", "Share this card")
                onClick { onShare() }
            }) { ShareIcon() }
            Button(attrs = {
                classes("btn", "sm", "ghost")
                onClick { onClose() }
            }) { Text("Close") }
        }

        Div(attrs = { classes("panel-body", "stack") }) {
            when {
                card.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                card.error != null -> Div(attrs = { classes("err") }) { Text(card.error!!) }
                else -> Body(card)
            }
        }
    }
}

@Composable
private fun Body(card: CardDetail) {
    CardQueries.art(card.printings.firstOrNull()?.scryfallId)?.let { url ->
        // `drawer-art`, not `card-art`. The latter is the grid tile's
        // wrapper and sets no width at all, so Scryfall's 745px scan
        // rendered at 745px and ran off the side of a phone.
        Img(src = url, alt = card.name, attrs = { classes("drawer-art") })
    }

    Div(attrs = { classes("flex-wrap", "small") }) {
        Span(attrs = { classes("tag", "mini") }) { Text("${card.owned} owned") }
        Span(attrs = { classes("tag", "mini") }) { Text("${card.free} free") }
        Span(attrs = { classes("tag", "mini") }) { Text(card.owner) }
        if (card.overCommitted) {
            // More decks want it than exist. Worth saying out loud.
            Span(attrs = { classes("tag", "bad", "mini") }) {
                Text("${card.committed} committed")
            }
        }
    }

    H3 { Text("Printings") }
    if (card.printings.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("None owned.") }
    } else {
        Div(attrs = { classes("table-wrap") }) {
            card.printings.forEach { p ->
                Div(attrs = { classes("flex-wrap", "small") }) {
                    Span(attrs = { classes("mono") }) { Text(p.setCode.uppercase()) }
                    Span(attrs = { classes("mono") }) { Text(p.collectorNumber.orEmpty()) }
                    Span { Text(p.setName.orEmpty()) }
                    Span(attrs = { classes("tag", "mini") }) { Text(p.finish) }
                    Span(attrs = { classes("tag", "mini") }) { Text("${p.qty}×") }
                }
            }
        }
    }

    H3 { Text("Legal in") }
    if (card.legalities.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("Nothing recorded.") }
    } else {
        Div(attrs = { classes("chips") }) {
            card.legalities.forEach { l ->
                Span(attrs = {
                    classes("chip", "mini")
                    if (!l.legal) classes("bad")
                }) { Text("${l.format} ${l.label}") }
            }
        }
    }

    H3 { Text("In decks") }
    if (card.usedIn.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("Not in a deck.") }
    } else {
        card.usedIn.forEach { use ->
            Div(attrs = { classes("flex-wrap", "small") }) {
                Span(attrs = { classes("t-name") }) { Text(use.name) }
                Span(attrs = { classes("tag", "mini") }) { Text("${use.qty}×") }
                use.role?.let { Span(attrs = { classes("tag", "mini") }) { Text(it) } }
                // A proxy does not consume a real card, so it must not
                // read like one that does.
                if (use.isProxy) Span(attrs = { classes("tag", "mini") }) { Text("proxy") }
            }
        }
    }

    Rulings(card)
}

@Composable
private fun Rulings(card: CardDetail) {
    if (card.rulings.isEmpty()) return
    H3 { Text("Rulings") }
    card.rulings.forEach { r ->
        Div(attrs = { classes("small") }) {
            Span(attrs = { classes("muted") }) { Text("${r.date} ") }
            Text(r.text)
        }
    }
}

/** The price line, shared with the grid so the two never disagree. */
fun priceLine(price: Double?, layout: String?, releasedAt: String?, today: String): String =
    Prices.orReason(price, layout, releasedAt, today)
