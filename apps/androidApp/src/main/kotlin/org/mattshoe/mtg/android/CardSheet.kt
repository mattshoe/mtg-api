package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Legality
import org.mattshoe.mtg.core.Prices

/**
 * One card, opened. Sibling of `CardPage` on the web.
 *
 * Both are handed a `CardDetail` and neither works out how many copies
 * are spare, which chips to draw or which rulings to show — `free`,
 * `legalityChips`, `byOwner` and `rulingsShown` are all on the state,
 * so the answers cannot come out different on a phone. Everything this
 * file decides is how to draw them.
 *
 * `CardSheetParityTest` renders this against the sentences the web
 * suite asserts, because reading the two files and concluding they
 * match is exactly how the phone ended up with its own rulings loop.
 */
@Composable
fun CardSheet(
    card: CardDetail,
    /** The card before this one in the deck being read, if there is one. */
    previous: DeckCard? = null,
    next: DeckCard? = null,
    /** "7 of 99", when the card is part of a deck. */
    place: String? = null,
    onStep: (DeckCard) -> Unit = {},
    // Last, so `CardSheet(card) {}` still means "and this is how you
    // leave it" — which is how every caller and every test writes it.
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← Back") }
        }
        // Selectable, the same as the web. Copying a card name off the
        // page is most of what the page is for.
        SelectionContainer { Text(card.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        when {
            card.busy -> Text("Loading…")
            card.error != null -> Text(card.error!!)
            else -> Body(card)
        }
        // Outside the `when`, the same as the web: stepping along a
        // deck still works while the next card is loading, which is
        // the moment you are most likely to want it.
        if (place != null) Steps(previous, next, place, onStep)
    }
}

/**
 * Previous, where you are, next.
 *
 * Under the card rather than over it: the page is read top to bottom
 * and this is what you do when you reach the end of one. It names the
 * cards, so you can tell whether it is worth the tap.
 */
@Composable
private fun Steps(previous: DeckCard?, next: DeckCard?, place: String, onStep: (DeckCard) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ghost(
            previous?.let { "← ${it.shown}" } ?: "← Previous",
            enabled = previous != null,
        ) { previous?.let(onStep) }
        Muted(place, size = Design.MINI)
        Ghost(
            next?.let { "${it.shown} →" } ?: "Next →",
            enabled = next != null,
        ) { next?.let(onStep) }
    }
}

@Composable
private fun Body(card: CardDetail) {
    // The scan, the same picture the web page puts at the top. A card
    // page without the card on it is a list of numbers.
    CardQueries.art(card.printings.firstOrNull()?.scryfallId)?.let { url ->
        val frame = RoundedCornerShape(5)
        AsyncImage(
            model = url,
            contentDescription = card.name,
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(Design.CARD_ASPECT)
                .background(Bg3, frame)
                .clip(frame),
            contentScale = ContentScale.Fit,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${card.owned} owned", fontSize = Design.SMALL.sp)
        Text("${card.free} free", fontSize = Design.SMALL.sp)
        // More decks want it than exist. Worth saying out loud, in the
        // same words the web says it in.
        if (card.overCommitted) {
            Text(
                "${card.committed} committed",
                fontSize = Design.SMALL.sp,
                color = Bad,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    // Who has how many. The page used to be one person's, which made
    // the other half of the collection invisible.
    if (card.byOwner.isNotEmpty()) {
        Heading("Who owns it")
        card.byOwner.forEach { h ->
            Text(
                listOfNotNull(
                    h.owner,
                    "${h.owned} owned",
                    "${h.free} free",
                    if (h.short > 0) "${h.short} short" else null,
                ).joinToString(" · "),
                fontSize = Design.SMALL.sp,
            )
        }
    }

    Heading("Printings")
    if (card.printings.isEmpty()) {
        Text("Nobody owns one.", fontSize = Design.SMALL.sp)
    } else {
        // Where to buy another, when the database has a listing for
        // that exact printing. The price beside it is the one for the
        // finish this copy is in, worked out by the `card_prices`
        // view, so a foil is not quoted at the nonfoil price.
        val open = androidx.compose.ui.platform.LocalUriHandler.current
        card.printings.forEach { p ->
            val shop = p.tcgplayer
            Row(
                Modifier.fillMaxWidth()
                    .then(
                        if (shop == null) Modifier
                        else Modifier.clickable { runCatching { open.openUri(shop) } },
                    )
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(
                        p.setCode.uppercase(),
                        p.collectorNumber,
                        p.setName,
                        // Whose copy it is. A card is not one person's.
                        p.owner.takeIf { it.isNotBlank() },
                        p.finish.takeIf { it != "nonfoil" },
                        "${p.qty}×",
                    ).joinToString(" · "),
                    fontSize = Design.SMALL.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Prices.money(p.price, dash = "—"),
                    fontSize = Design.SMALL.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // Say where it goes. A row that is only subtly a link
                // is a link nobody finds, so the shop is named rather
                // than hinted at with a bare arrow.
                if (shop != null) Tag("TCGplayer ↗", Accent2)
            }
        }
    }

    Heading("Legal in")
    // `legalityChips`, not the raw list: ordered by the formats people
    // actually ask about, each format once, blanks dropped. The web
    // page gets all of that from the shared core, and this had its own
    // loop over `legalities`, so the two disagreed about the same card.
    val chips = card.legalityChips
    if (chips.isEmpty()) {
        Text("Nothing recorded.", fontSize = Design.SMALL.sp)
    } else {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { Chip(it) }
        }
        // The heading promises somewhere. When there is nowhere, the
        // chips alone leave you counting them to find that out.
        if (!card.legalAnywhere) {
            Text("Legal nowhere.", fontSize = Design.SMALL.sp)
        }
    }

    Heading("In decks")
    if (card.usedIn.isEmpty()) {
        Text("Not in a deck.", fontSize = Design.SMALL.sp)
    } else {
        card.usedIn.forEach { use ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(
                        use.name,
                        "${use.qty}×",
                        use.role,
                        // A proxy does not consume a real card, so it
                        // must not read like one that does.
                        if (use.isProxy) "proxy" else null,
                    ).joinToString(" · "),
                    fontSize = Design.SMALL.sp,
                )
                // Whose deck it is. The page is not one person's.
                if (use.owner.isNotBlank()) Tag(use.owner)
            }
        }
    }

    // `rulingsShown`, not the raw list: oldest first, the same ruling
    // once however many faces the join returned it for, blank ones
    // dropped, and a day rather than a whole timestamp.
    Heading("Rulings")
    val rulings = card.rulingsShown
    if (rulings.isEmpty()) {
        Text("No rulings.", fontSize = Design.MINI.sp)
    } else {
        rulings.forEach { r ->
            Text(
                if (r.day.isEmpty()) r.body else "${r.day}  ${r.body}",
                fontSize = Design.MINI.sp,
            )
        }
    }
}

/** An `h3` on the web: the name of a section, present even when empty. */
@Composable
private fun Heading(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

/**
 * One legality, as a chip.
 *
 * The status is carried three times over: a mark in front of it, the
 * word itself, and only then the colour. Hue is not a channel this
 * collection's owner has, so a chip that said "banned" in red and
 * "not legal" in the same red was saying nothing at all to him — and
 * every status but `legal` used to be that one red.
 */
@Composable
private fun Chip(l: Legality) {
    val tone = when {
        l.legal -> Ok
        l.banned -> Bad
        l.restricted -> Warn
        else -> Ink3
    }
    Text(
        l.chip,
        Modifier
            .background(tone.copy(alpha = 0.16f), RadiusSm)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = tone,
        fontSize = Design.MINI.sp,
        fontWeight = if (l.legal) FontWeight.Medium else FontWeight.SemiBold,
    )
}
