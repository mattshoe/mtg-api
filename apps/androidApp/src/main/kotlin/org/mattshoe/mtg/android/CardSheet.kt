package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Face
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
    /**
     * Hand over a link to this card.
     *
     * The website's `CardPage` has had this since it was written —
     * "Copy a link to this card" — and the phone had no share on a
     * card at all, from any route. A card is its own destination with
     * its own address, so it is the screen where a link is most
     * obviously the point.
     */
    onShare: () -> Unit = {},
    /**
     * The picture, the side it is turned to. `AppState.artFor` works
     * that out; the default is the front.
     */
    art: String? = CardQueries.art(card.scryfallId),
    /** Turn a double-faced card over. */
    onFlip: () -> Unit = {},
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
            Spacer(Modifier.weight(1f))
            // Named, near the top, the same as the web's. Opened by
            // the system, which hands an https link to the browser.
            card.edhrecUrl?.let { url ->
                val open = androidx.compose.ui.platform.LocalUriHandler.current
                TextButton(
                    onClick = { runCatching { open.openUri(url) } },
                    modifier = Modifier.testTag("edhrec-link"),
                ) { Text("EDHREC ↗") }
            }
            // The same header shape as the website's card page: back
            // on the left, the share on the right, nothing between.
            ShareCardButton(onShare)
        }
        // Selectable, the same as the web. Copying a card name off the
        // page is most of what the page is for.
        SelectionContainer { Text(card.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        when {
            card.busy -> Text("Loading…")
            card.error != null -> Text(card.error!!)
            else -> Body(card, art, onFlip)
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
private fun Body(card: CardDetail, art: String?, onFlip: () -> Unit) {
    // The scan, the same picture the web page puts at the top. A card
    // page without the card on it is a list of numbers.
    art?.let { url ->
        val frame = RoundedCornerShape(5)
        // `.card-scan` is 300px at the widest and centred in whatever
        // holds it. Filling the width instead was invisible on a phone
        // and absurd on anything else — a playing card the width of a
        // tablet.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 300.dp).fillMaxWidth()) {
                AsyncImage(
                    model = url,
                    contentDescription = card.name,
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio(Design.CARD_ASPECT)
                        .background(Bg3, frame)
                        .clip(frame)
                        .testTag("card-scan")
                        .semantics { cardPicture = url },
                    contentScale = ContentScale.Fit,
                )
                if (card.flips) FlipToggle(onFlip, Modifier.align(Alignment.TopEnd))
            }
        }
    }

    // What the card actually says. Everything below this point is
    // about the collection's relationship to the card — how many are
    // owned, who has them, which decks want them. None of it is the
    // card, and until now none of the card was here either.
    card.faces.forEach { FacePanel(it, named = card.faces.size > 1) }

    Details(card)

    // `.flex-wrap` of `.tag.mini`, the way the web states a figure:
    // boxed, so a number reads as a number and not as the start of a
    // sentence. Three of them run together in plain text is a phrase
    // you have to parse; three pills are three facts.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tag("${card.owned} owned")
        Tag("${card.free} free")
        // More decks want it than exist. Worth saying out loud, in the
        // same words the web says it in.
        if (card.overCommitted) Tag("${card.committed} committed", Bad)
    }

    // Who has how many. The page used to be one person's, which made
    // the other half of the collection invisible.
    if (card.byOwner.isNotEmpty()) {
        Heading("Who owns it")
        card.byOwner.forEachIndexed { i, h ->
            // `.owner-line + .owner-line`: a hairline, so two people
            // read as two rows rather than one paragraph.
            if (i > 0) RowRule()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `.owner-line .t-name` is capitalised by the
                // stylesheet, and takes the slack so the figures stay
                // in a column down the side.
                RowName(
                    h.owner.replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f),
                    weight = FontWeight.SemiBold,
                )
                Figure("${h.owned} owned")
                // Nought spare reads differently from three spare, and
                // it is the number people are actually here for.
                Figure("${h.free} free", if (h.free > 0) Ok else Ink2)
                if (h.short > 0) Figure("${h.short} short", Bad)
            }
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
        card.printings.forEachIndexed { i, p ->
            val shop = p.tcgplayer
            if (i > 0) RowRule()
            Row(
                Modifier.fillMaxWidth()
                    .then(
                        if (shop == null) Modifier
                        else Modifier.clickable { runCatching { open.openUri(shop) } },
                    )
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `.set`, `.cn`, `.t-name`, then the tags: a row you
                // read across, with the set name the only part that
                // gives way. Joined into one sentence it wrapped onto
                // a second line on a phone and the price went with it.
                Text(
                    p.setCode.uppercase(),
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = Design.MINI.sp,
                    maxLines = 1,
                )
                p.collectorNumber?.let {
                    Text(
                        it,
                        color = Ink3,
                        fontFamily = FontFamily.Monospace,
                        fontSize = Design.TINY.sp,
                        maxLines = 1,
                    )
                }
                RowName(p.setName.orEmpty(), Modifier.weight(1f), color = Ink2)
                // Whose copy it is. A card is not one person's.
                if (p.owner.isNotBlank()) Tag(p.owner)
                if (p.finish != "nonfoil") Tag(p.finish)
                Figure("${p.qty}×")
                Text(
                    Prices.money(p.price, dash = "—"),
                    fontSize = Design.MINI.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
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
            // `.flex-wrap` of the deck's name and then a tag apiece,
            // in the website's order: whose deck it is comes straight
            // after what it is called, because that is the half of
            // the answer a shared collection turns on.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RowName(use.name, weight = FontWeight.Medium)
                // Whose deck it is. The page is not one person's.
                if (use.owner.isNotBlank()) Tag(use.owner)
                Tag("${use.qty}×")
                use.role?.let { Tag(it) }
                // A proxy does not consume a real card, so it must not
                // read like one that does.
                if (use.isProxy) Tag("proxy")
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
                // The day in the web's `.muted.mono`, so a column of
                // them lines up and the eye goes to the ruling rather
                // than to the date in front of it. One text node, not
                // two: the line is one sentence to anything reading it
                // out, which is how it reads on the page as well.
                //
                // One space after the date, not two. The web writes
                // `r.day + " "` into a text node, and HTML would
                // collapse a second one anyway; Compose does not, so
                // two here was a visibly wider gap than the page's.
                buildAnnotatedString {
                    if (r.day.isNotEmpty()) {
                        withStyle(SpanStyle(color = Ink3, fontFamily = FontFamily.Monospace)) {
                            append(r.day)
                        }
                        append(" ")
                    }
                    append(r.body)
                },
                fontSize = Design.MINI.sp,
            )
        }
    }
}

/**
 * Everything else the database knows about the printing on the page.
 * Sibling of `Details` on the web; `CardFacts.groups` decides what is
 * said, this only lays it out.
 */
@Composable
private fun Details(card: CardDetail) {
    val groups = card.facts?.groups.orEmpty()
    if (groups.isEmpty()) return
    Heading("Details")
    groups.forEach { g ->
        // `.facts-group`: small capitals over the rows.
        Text(
            g.title.uppercase(),
            Modifier.padding(top = 6.dp),
            color = Ink3,
            fontSize = Design.TINY.sp,
            letterSpacing = 0.5.sp,
        )
        g.facts.forEachIndexed { i, f ->
            if (i > 0) RowRule()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("fact"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(f.label, Modifier.widthIn(min = 112.dp, max = 112.dp), color = Ink3, fontSize = Design.MINI.sp)
                Text(f.value, Modifier.weight(1f), color = Ink, fontSize = Design.MINI.sp)
            }
        }
    }
}

/**
 * The button over a double-faced card's picture that turns it over.
 * Sibling of `FlipToggle` on the web, and used the same three places:
 * the card page, the carousel and the Library's tiles.
 *
 * Told apart from the art by lightness rather than hue — a near-black
 * disc with a white ring and a white mark — because Matt is
 * colourblind and the art behind it can be any colour at all. Its own
 * `clickable`, so on a tile the press turns the card over and does not
 * go on to open the carousel.
 */
@Composable
fun FlipToggle(onFlip: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(TouchTarget)
            .testTag("flip-toggle")
            .semantics {
                role = Role.Button
                contentDescription = "Flip card"
            }
            .clickable(onClick = onFlip),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp)
                .background(FlipDisc, androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("↻", color = Color.White, fontSize = 22.sp)
        }
    }
}

/** `.flip-toggle`'s background, `#0b0d10`. */
val FlipDisc = Color(0xFF0B0D10)

/** An `h3` on the web: the name of a section, present even when empty. */
/**
 * One printed face, the sibling of `FacePanel` on the web.
 *
 * The cost as real symbols beside the name, then the type line, the
 * rules text, the flavour, and the little box in the corner. The line
 * breaks in oracle text carry meaning — one ability per line is how a
 * card is read — so the string goes in whole and Compose wraps it.
 *
 * `named` only on a double-faced card, where saying which face you
 * are reading is the entire point of drawing two of these.
 */
/**
 * The share, on a card. Sibling of the deck's, and the same mark.
 *
 * One share symbol in the app rather than two: `ShareMark` draws the
 * website's own SVG, and a second one typed as a font glyph is how
 * the deck page ended up with an arrow nobody recognised. `TouchTarget`
 * because item 4.8 put a 48dp floor under everything you press and a
 * new control is the easiest place to lose it.
 */
@Composable
private fun ShareCardButton(onShare: () -> Unit) {
    Box(
        Modifier.size(TouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = "Share this card"
            }
            .clickable(onClick = onShare),
        contentAlignment = Alignment.Center,
    ) {
        ShareMark(Ink2)
    }
}

@Composable
private fun FacePanel(face: Face, named: Boolean) {
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (named) {
                Text(
                    face.name,
                    Modifier.weight(1f),
                    color = Ink,
                    fontSize = Design.SMALL.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            ManaCostRow(face.manaCost)
        }
        if (face.typeLine.isNotBlank()) {
            Text(face.typeLine, color = Ink2, fontSize = Design.MINI.sp)
        }
        if (face.oracleText.isNotBlank()) {
            Text(
                face.oracleText,
                Modifier.testTag("oracle"),
                color = Ink,
                fontSize = Design.SMALL.sp,
                lineHeight = (Design.SMALL * 1.6).sp,
            )
        }
        if (face.flavorText.isNotBlank()) {
            Text(
                face.flavorText,
                Modifier.testTag("flavor"),
                color = Ink3,
                fontSize = Design.MINI.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
        // Bottom-right, where it is printed, and in the mono face so
        // a 3/4 and a 12/12 line up when two faces are stacked.
        face.stats?.let { stats ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    stats,
                    Modifier.testTag("ptbox")
                        .background(Bg3, RadiusSm)
                        .border(1.dp, Line2, RadiusSm)
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                    color = Ink,
                    fontSize = Design.SMALL.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

/**
 * `.t-name`: the one thing on a row that gives way.
 *
 * A set name is longer than a phone and every figure beside it is
 * short, so the name is cut off rather than allowed to wrap — a
 * wrapped row takes the price onto a second line with it, which is
 * how the printings here came out two lines tall next to the
 * website's one.
 */
@Composable
private fun RowName(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink,
    weight: FontWeight = FontWeight.Medium,
) {
    Text(
        text,
        modifier,
        color = color,
        fontSize = Design.MINI.sp,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `.tag.mini.mono`: a counted thing, boxed.
 *
 * The shared `Tag` is this pill in the proportional face, which is
 * right for a word like an owner or a finish. A number gets the mono
 * one, so a column of them lines up down the page the way the
 * website's do.
 */
@Composable
private fun Figure(text: String, tone: Color = Ink2) {
    Text(
        text,
        Modifier
            .background(Bg3, RadiusSm)
            .border(1.dp, tone.copy(alpha = 0.4f), RadiusSm)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = tone,
        fontSize = Design.TINY.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}

/** `.owner-line + .owner-line`: the hairline that makes two rows two. */
@Composable
private fun RowRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
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
    // `.chip.mini.<tone>`, which is not what Android drew. The web
    // leaves the face as the page's own colour and puts the tone on a
    // one-pixel edge — the tone at 45% over `--line` — rounds it to a
    // pill, sets 11px, and takes the body's weight. Android had a
    // 16%-tinted lozenge with no edge at all, at 12sp, in Medium or
    // SemiBold depending on the status: a different shape, a
    // different size, a different weight and a fill the website has
    // nowhere.
    //
    // `.chip.off` is the exception the stylesheet spells out: "not
    // legal" is not a failure, so its edge stays `--line-2` like any
    // ordinary chip and only the mark and the dimmed text separate it
    // from the other three. That is deliberate — it is told apart by
    // shape, not by which of three alarm colours is lightest.
    val edged = l.legal || l.banned || l.restricted
    Text(
        l.chip,
        Modifier
            // Outermost on purpose: `Text` puts its own semantics
            // inside the modifier chain, so the node carrying the
            // text has the *unpadded* box. Measuring a chip's air
            // needs a node that owns the padded one.
            .testTag("legality-${l.formatLabel}")
            .background(Bg, Pill)
            .border(1.dp, if (edged) mix(tone, Line, Design.CHIP_EDGE_MIX) else Line2, Pill)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = tone,
        fontSize = Design.TINY.sp,
    )
}
