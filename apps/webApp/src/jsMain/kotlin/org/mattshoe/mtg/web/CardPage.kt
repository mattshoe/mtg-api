package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.DeckCard
import org.jetbrains.compose.web.attributes.disabled
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Face
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Prices

/**
 * One card, as its own page.
 *
 * It was a drawer over whatever page you happened to be on, with its
 * address in that page's query string. That is where the navigation
 * bugs came from: the link carried the deck underneath, back had to
 * choose between dismissing and navigating, and the page behind kept
 * its own scroll. A card is a place now, so none of those are
 * questions any more.
 *
 * Sibling of `CardSheet` on Android. Both are handed a `CardDetail`
 * and neither works out how many copies are spare — `free` is on the
 * state, so the number cannot come out different on a phone.
 */
@Composable
fun CardPage(
    card: CardDetail,
    onShare: () -> Unit = {},
    onBack: () -> Unit = {},
    /** The card before this one in the deck being read, if there is one. */
    previous: DeckCard? = null,
    next: DeckCard? = null,
    /** "7 of 99", when the card is part of a deck. */
    place: String? = null,
    onStep: (DeckCard) -> Unit = {},
) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) {
            Button(attrs = {
                classes("btn", "sm", "ghost")
                onClick { onBack() }
            }) { Text("← Back") }
            Span(attrs = { classes("spacer") }) {}
            Button(attrs = {
                classes("btn", "sm", "ghost", "icon-only")
                attr("title", "Copy a link to this card")
                attr("aria-label", "Share this card")
                onClick { onShare() }
            }) { ShareIcon() }
        }

        Div(attrs = {
            classes("card-page", "stack")
            // A swipe, because this is read on a phone and opening a
            // card, going back and opening the next is three gestures
            // for every card in a hundred-card deck.
            if (place != null) {
                var x = 0.0
                var y = 0.0
                onTouchStart { e ->
                    val t = e.touches.item(0) ?: return@onTouchStart
                    x = t.clientX.toDouble()
                    y = t.clientY.toDouble()
                }
                onTouchEnd { e ->
                    val t = e.changedTouches.item(0) ?: return@onTouchEnd
                    val dx = t.clientX.toDouble() - x
                    val dy = t.clientY.toDouble() - y
                    // Far enough across, and more across than down, so
                    // scrolling the page is never mistaken for a swipe.
                    if (kotlin.math.abs(dx) < SWIPE || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5) {
                        return@onTouchEnd
                    }
                    (if (dx < 0) next else previous)?.let(onStep)
                }
            }
        }) {
            when {
                card.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                card.error != null -> Div(attrs = { classes("err") }) { Text(card.error!!) }
                else -> Body(card)
            }
            if (place != null) Steps(previous, next, place, onStep)
        }
    }
}

/** How far a finger has to travel before it counts as a swipe. */
private const val SWIPE = 45.0

/**
 * Previous, where you are, next.
 *
 * Under the card rather than over it: the page is read top to bottom
 * and this is what you do when you reach the end of one. It names the
 * cards, so you can tell whether it is worth the tap.
 */
@Composable
private fun Steps(previous: DeckCard?, next: DeckCard?, place: String, onStep: (DeckCard) -> Unit) {
    Div(attrs = { classes("card-steps") }) {
        Button(attrs = {
            classes("btn", "sm", "ghost", "step-prev")
            if (previous == null) disabled()
            previous?.let { attr("title", "Previous: ${it.name}") }
            onClick { previous?.let(onStep) }
        }) { Text(previous?.let { "← ${it.shown}" } ?: "← Previous") }
        Span(attrs = { classes("muted", "small", "mono", "step-place") }) { Text(place) }
        Button(attrs = {
            classes("btn", "sm", "ghost", "step-next")
            if (next == null) disabled()
            next?.let { attr("title", "Next: ${it.name}") }
            onClick { next?.let(onStep) }
        }) { Text(next?.let { "${it.shown} →" } ?: "Next →") }
    }
}

/**
 * One printed face: the cost, the type line, the rules text, the
 * flavour and the little box in the corner.
 *
 * `.oracle` and `.flavor` have been sitting in `app.css` with nothing
 * using them. The line breaks inside oracle text are meaningful — one
 * ability per line is how a card is read — so both are `pre-wrap` and
 * the text goes in unmangled.
 *
 * `named` is only true for a double-faced card, where saying which
 * face you are reading is the entire point of showing two panels.
 */
@Composable
private fun FacePanel(face: Face, named: Boolean) {
    Div(attrs = { classes("panel", "card-face") }) {
        Div(attrs = { classes("panel-body") }) {
            Div(attrs = { classes("face-head") }) {
                if (named) Span(attrs = { classes("face-name") }) { Text(face.name) }
                Span(attrs = { classes("spacer") }) {}
                ManaCostRow(face.manaCost)
            }
            if (face.typeLine.isNotBlank()) {
                Div(attrs = { classes("type-line") }) { Text(face.typeLine) }
            }
            if (face.oracleText.isNotBlank()) {
                Div(attrs = { classes("oracle") }) { Text(face.oracleText) }
            }
            if (face.flavorText.isNotBlank()) {
                Div(attrs = { classes("flavor") }) { Text(face.flavorText) }
            }
            face.stats?.let { Div(attrs = { classes("ptbox") }) { Text(it) } }
        }
    }
}

@Composable
private fun Body(card: CardDetail) {
    CardQueries.art(card.printings.firstOrNull()?.scryfallId)?.let { url ->
        // `card-scan`, not `card-art`. The latter is the grid tile's
        // wrapper and sets no width at all, so Scryfall's 745px scan
        // rendered at 745px and ran off the side of a phone.
        Img(src = url, alt = card.name, attrs = { classes("card-scan") })
    }

    // What the card actually says. Everything under it is about the
    // collection; this is the card.
    card.faces.forEach { FacePanel(it, card.faces.size > 1) }

    Div(attrs = { classes("flex-wrap", "small") }) {
        Span(attrs = { classes("tag", "mini") }) { Text("${card.owned} owned") }
        Span(attrs = { classes("tag", "mini") }) { Text("${card.free} free") }
        if (card.overCommitted) {
            // More decks want it than exist. Worth saying out loud.
            Span(attrs = { classes("tag", "bad", "mini") }) {
                Text("${card.committed} committed")
            }
        }
    }

    Owners(card)

    H3 { Text("Printings") }
    if (card.printings.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("Nobody owns one.") }
    } else {
        card.printings.forEach { p -> PrintingLine(p) }
    }

    H3 { Text("Legal in") }
    // Ordered and de-duplicated by the state, not by whichever query
    // happened to fill it, so the row reads the same everywhere.
    val chips = card.legalityChips
    if (chips.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("Nothing recorded.") }
    } else {
        Div(attrs = { classes("chips", "legalities") }) {
            chips.forEach { l ->
                Span(attrs = {
                    classes("chip", "mini", l.tone)
                    attr("title", "${l.formatLabel}: ${l.label}")
                }) { Text(l.chip) }
            }
        }
        // The heading promises somewhere. When there is nowhere, the
        // chips alone leave you counting them to find that out.
        if (!card.legalAnywhere) {
            Div(attrs = { classes("muted", "small") }) { Text("Legal nowhere.") }
        }
    }

    H3 { Text("In decks") }
    if (card.usedIn.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("Not in a deck.") }
    } else {
        card.usedIn.forEach { use ->
            Div(attrs = { classes("flex-wrap", "small") }) {
                Span(attrs = { classes("t-name") }) { Text(use.name) }
                Span(attrs = { classes("tag", "mini") }) { Text(use.ownerName.ifEmpty { use.owner }) }
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

/**
 * Who owns how many, and how much of it is spare.
 *
 * The page was scoped to one person, so the other half of the
 * collection was simply invisible: Matt's page for a card said "1
 * owned" while Kayla had three of it sitting in a box. A card
 * belongs to nobody in particular, so it says who has it instead.
 */
@Composable
private fun Owners(card: CardDetail) {
    val holdings = card.byOwner
    if (holdings.isEmpty()) return
    H3 { Text("Who owns it") }
    holdings.forEach { h ->
        Div(attrs = { classes("owner-line") }) {
            Span(attrs = { classes("t-name") }) { Text(h.ownerName) }
            Span(attrs = { classes("tag", "mini", "mono") }) { Text("${h.owned} owned") }
            Span(attrs = {
                classes("tag", "mini", "mono")
                // Nought spare is worth reading differently from
                // three spare, and it is the number people are
                // actually here for.
                if (h.free > 0) classes("ok")
            }) { Text("${h.free} free") }
            if (h.short > 0) {
                Span(attrs = { classes("tag", "mini", "bad", "mono") }) { Text("${h.short} short") }
            }
        }
    }
}

/**
 * What the rules team has said about the card.
 *
 * The heading is there even when there is nothing under it, the way
 * every other section of this page works. A section that vanishes is
 * one you cannot tell apart from rulings that failed to load, and
 * "no rulings" is itself worth knowing.
 *
 * Ordering, de-duplication and reading the date are all `CardDetail`'s
 * job, so a phone shows the same list in the same order.
 */
@Composable
private fun Rulings(card: CardDetail) {
    H3 { Text("Rulings") }
    val rulings = card.rulingsShown
    if (rulings.isEmpty()) {
        Div(attrs = { classes("muted", "small") }) { Text("No rulings.") }
        return
    }
    rulings.forEach { r ->
        Div(attrs = { classes("ruling", "small") }) {
            // Nothing at all when the stored date is not a date: a
            // lone separator in front of the text reads like a typo.
            if (r.day.isNotEmpty()) {
                Span(attrs = { classes("muted", "mono") }) { Text(r.day + " ") }
            }
            // `Text` writes a text node, so a ruling quoting "<i>" or
            // an ability word in angle brackets is read, not parsed.
            Span { Text(r.body) }
        }
    }
}

/** The price line, shared with the grid so the two never disagree. */
fun priceLine(price: Double?, layout: String?, releasedAt: String?, today: String): String =
    Prices.orReason(price, layout, releasedAt, today)

/**
 * One printing you own, and where to buy another.
 *
 * The shop link and the price are both already in the database —
 * `card_prices` works out which of usd, usd_foil and usd_etched
 * applies to the finish this copy is in, so a foil does not quote a
 * nonfoil price. No second service to ask.
 *
 * A link when there is a listing and a plain row when there is not,
 * the same rule the token rows follow.
 */
@Composable
private fun PrintingLine(p: Printing) {
    val body: @Composable () -> Unit = {
        Span(attrs = { classes("mono", "set") }) { Text(p.setCode.uppercase()) }
        Span(attrs = { classes("mono", "cn") }) { Text(p.collectorNumber.orEmpty()) }
        Span(attrs = { classes("t-name") }) { Text(p.setName.orEmpty()) }
        if (p.owner.isNotBlank()) {
            Span(attrs = { classes("tag", "mini") }) { Text(p.ownerName.ifEmpty { p.owner }) }
        }
        if (p.finish != "nonfoil") {
            Span(attrs = { classes("tag", "mini") }) { Text(p.finish) }
        }
        Span(attrs = { classes("tag", "mini", "mono") }) { Text("${p.qty}×") }
        Span(attrs = { classes("num", "mono") }) { Text(Prices.money(p.price, dash = "—")) }
        // Say where it goes. A row that is only subtly a link is a
        // link nobody finds, so the shop is named rather than hinted
        // at with an arrow in the corner.
        if (p.tcgplayer != null) {
            Span(attrs = { classes("tag", "mini", "buy") }) { Text("TCGplayer ↗") }
        }
    }

    val shop = p.tcgplayer
    if (shop == null) {
        Div(attrs = { classes("print-line") }) { body() }
    } else {
        A(href = shop, attrs = {
            classes("print-line")
            target(ATarget.Blank)
            attr("rel", "noopener noreferrer")
            attr("title", "Buy the ${p.setCode.uppercase()} printing on TCGplayer")
        }) { body() }
    }
}
