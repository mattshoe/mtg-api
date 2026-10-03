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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.Bar
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.Tweak

/**
 * Decks, on Android. Sibling of `DecksPage`.
 *
 * The same tile the site draws: the commander's art as a band across
 * the top, the tile-width name, the identity pips and who it is. The
 * grouping, the gaps and the totals all come from `DecksState`, so the
 * two cannot disagree about what a deck is short of.
 */
@Composable
fun DecksScreen(
    state: DecksState,
    onOpen: (Deck) -> Unit,
    onClose: () -> Unit,
    admin: Boolean = false,
    onNew: () -> Unit = {},
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
    /** Rename the open deck. The slug moves with the name. */
    onRename: (Deck) -> Unit = {},
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
    /** Maintenance, one card at a time, without leaving the screen. */
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        val open = state.open
        if (open == null) {
            PageHead("Decks") { if (admin) Primary("New deck", onClick = onNew) }
            when {
                state.busy -> Line("Loading…", Ink3)
                state.error != null -> Line("Could not load decks: ${state.error}", Bad)
                state.decks.isEmpty() -> Line("No decks yet.", Ink3)
                else -> state.byOwner.forEach { (owner, decks) ->
                    // A shelf, with a heading that says how many are on
                    // it. The web's `.owner-head` is the name and the
                    // count together, not a bare name.
                    GroupHead(
                        owner.replaceFirstChar(Char::uppercase),
                        "${decks.size} " + if (decks.size == 1) "deck" else "decks",
                        Design.H2,
                    )
                    decks.forEach { DeckTile(it, onOpen) }
                }
            }
        } else {
            var sharing by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ghost("← Decks", onClick = onClose)
                Spacer(Modifier.weight(1f))
                ShareButton(sharing) { sharing = !sharing }
            }
            // Inline rather than a floating popup: a phone has the
            // width for it, and a menu that is part of the page
            // cannot end up off the edge of the screen.
            if (sharing) ShareMenu { what, where -> sharing = false; onShare(what, where) }
            Hero(open, state)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tag("${state.totalCards} cards")
                if (state.gaps.isNotEmpty()) Tag("${state.gaps.size} not owned", Bad)
            }
            if (admin) {
                // Both of these move real cards, and both show the
                // server's own dry run before they are allowed to.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Btn("Edit list") { onEdit(open) }
                    Btn("Rename") { onRename(open) }
                    Btn("Disassemble", danger = true) { onDisassemble(open) }
                }
            }
            DeckStats(DeckAnalysis.of(state.cards))
            if (admin) {
                Panel { Primary("+ Add a card", onClick = onAddCard) }
            }
            // `byType` is the core's, the same list the website reads,
            // so the two cannot group or order a deck differently.
            state.byType.forEach { (group, cards) ->
                GroupHead(group.title, "${cards.sumOf { it.qty }}")
                Panel {
                    cards.forEach { c -> CardLine(c, open.owner, onOpenCard, admin, onTweak) }
                }
            }

            TokenList(state.tokens)
        }
    }
}

@Composable
private fun DeckTile(deck: Deck, onOpen: (Deck) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Bg2, Radius)
            .border(1.dp, Line, Radius)
            .clip(Radius)
            .clickable { onOpen(deck) },
    ) {
        CardQueries.banner(deck.artId, deck.commanderName)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(96.dp).background(Bg3),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Line(deck.title, Ink, Design.H3, FontWeight.SemiBold, Modifier.weight(1f))
                deck.bracket?.let { Tag("bracket $it", Info) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Identity(deck.identity)
                Line(deck.commanderName ?: "—", Ink2, Design.MINI)
            }
        }
    }
}

/** WUBRG pips, or one colourless one. The `.mana` row on the web. */
@Composable
private fun Identity(ci: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (ci.ifEmpty { "C" }).forEach { letter ->
            Box(
                Modifier.size(16.dp).background(c(Design.pip(letter.toString())), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    letter.toString(),
                    color = Bg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The commander across the top of its own deck. The web's `.deck-hero`.
 *
 * The art is a landscape crop, so the band is a fixed height with the
 * picture covering it and the name over a wash — white on bare art is
 * unreadable over half the commanders in the game.
 */
@Composable
private fun Hero(deck: Deck, state: DecksState) {
    val cmdr = state.commander
    val url = cmdr?.art ?: CardQueries.banner(deck.artId, deck.commanderName)
    if (url == null) {
        PageHead(deck.title)
        return
    }
    Box(Modifier.fillMaxWidth().height(150.dp).background(Bg3, Radius).clip(Radius)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.55f to androidx.compose.ui.graphics.Color(0x8C04060A),
                        1f to androidx.compose.ui.graphics.Color(0xEB04060A),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Line(deck.title, Ink, Design.H2, FontWeight.SemiBold)
            Line(
                listOfNotNull(
                    cmdr?.shown ?: deck.commanderName,
                    deck.bracket?.let { "Bracket $it" },
                ).joinToString(" · "),
                Ink2,
                Design.MINI,
            )
        }
    }
}

/**
 * One card in the list: a square crop of its art, the name and type,
 * and how many. The whole row opens the card, the way the web's does.
 */
@Composable
private fun CardLine(
    card: DeckCard,
    owner: String,
    onOpen: (DeckCard, String) -> Unit,
    admin: Boolean = false,
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(card, owner) }.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `art_crop` is a landscape band; the square comes from this
        // box cropping it.
        Box(Modifier.size(40.dp).background(Bg3, Radius).clip(Radius)) {
            card.art?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Line(card.shown, Ink, Design.SMALL)
            // `knownTypeLine`, not `typeLine`: a basic land nobody
            // inventories has no printing to read one off, and the
            // web names it anyway. The phone was leaving every
            // Island, Plains and Forest with a blank second line.
            card.knownTypeLine?.takeIf { it.isNotBlank() }?.let { Line(it, Ink3, Design.MINI) }
        }
        if (card.short > 0) Tag("has ${card.owned}", Bad)
        Line("${card.qty}×", Ink3, Design.MINI)
        // Maintenance lives on the row the card is on. One button,
        // not three: three marks on every row of a hundred-card list
        // left no room for the card's own name. What to do is asked
        // inside the sheet.
        if (admin) RowAction(card, onTweak)
    }
}

/**
 * The "⋯" on a card's row. Admin only, and it keeps the press.
 *
 * The row it sits on is itself a button, so without its own click the
 * sheet would open with the card page behind it. A 44dp box rather
 * than a 20dp glyph, which is the floor the web's coarse-pointer rule
 * puts on it.
 */
@Composable
private fun RowAction(card: DeckCard, onTweak: (DeckCard, Tweak?) -> Unit) {
    Box(
        Modifier.size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Change ${card.shown}"
            }
            // Nothing is decided here: the sheet asks what to do.
            .clickable { onTweak(card, null) },
        contentAlignment = Alignment.Center,
    ) {
        Line("⋯", Ink2, Design.H3, FontWeight.SemiBold)
    }
}

/**
 * A panel head: what it is, and how many of it. The web's
 * `.panel-head` is an `h2` and a count tag, never a bare word.
 */
@Composable
private fun GroupHead(title: String, count: String, size: Int = Design.H3) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(
            title,
            Ink,
            size,
            FontWeight.SemiBold,
            Modifier.weight(1f).semantics { heading() },
        )
        Tag(count)
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
private fun ShareButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Share this deck"
            }
            .background(
                if (open) AccentDim else androidx.compose.ui.graphics.Color.Transparent,
                Radius,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The glyph, not the word - the web's button is icon-only.
        // Boxed and gold while it is open, so "open" reads as a shape
        // and a lightness rather than a hue nobody can see.
        Line("⤴", if (open) Accent2 else Ink2, Design.H2, FontWeight.SemiBold)
    }
}

@Composable
private fun ShareMenu(onShare: (ShareWhat, ExportTo) -> Unit) {
    Panel {
        // A group is its label and the two options under it, close
        // enough together to read as one thing. Evenly spaced, the
        // second label sat as far from its own options as from the
        // group above it and the menu read as six loose words.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareWhat.entries.forEach { what ->
                Column {
                    Line(
                        what.label,
                        Ink3,
                        Design.MINI,
                        FontWeight.SemiBold,
                        Modifier.padding(bottom = 2.dp),
                    )
                    ExportTo.entries.forEach { where ->
                        Line(
                            where.label,
                            Ink,
                            Design.SMALL,
                            FontWeight.Normal,
                            Modifier.fillMaxWidth()
                                .semantics { role = Role.Button }
                                .clickable { onShare(what, where) }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the deck is made of, on a phone.
 *
 * Every number is `DeckAnalysis`, the same object the website reads,
 * so the two cannot disagree about a deck's curve. Drawn plainer than
 * the web's: a phone has one column and no room for five panels side
 * by side.
 */
@Composable
private fun DeckStats(s: org.mattshoe.mtg.core.DeckStats) {
    Panel {
        Line(
            "The deck at a glance",
            Ink,
            Design.H3,
            FontWeight.SemiBold,
            Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Figure("${s.totalCards}", "cards")
            Figure("${s.lands}", "lands · ${s.landShare}%")
            Figure(s.averageManaValue.toString(), "avg mana")
            Figure("${s.spells}", "spells")
            s.value?.let { Figure(Prices.money(it), "value") }
            if (s.missing > 0) Figure("${s.missing}", "not owned", Warn)
        }
    }

    if (s.hasCurve) {
        Panel {
            Line("Mana curve", Ink3, Design.MINI, FontWeight.SemiBold)
            val most = s.curve.maxOfOrNull { it.value } ?: 0
            Row(
                Modifier.fillMaxWidth().height(110.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.curve.forEach { bar ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Line(if (bar.value > 0) "${bar.value}" else "", Ink2, Design.MINI)
                        // The bar fills a fraction of the track, not
                        // of the whole column: the column also holds
                        // the number and the label, so a fraction of
                        // the column overruns the space left and
                        // every tall bar ends up the same height.
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .fillMaxHeight(if (most <= 0) 0f else bar.value.toFloat() / most)
                                    .background(if (bar.value > 0) Accent else Bg3, Radius),
                            )
                        }
                        Line(bar.label, Ink3, Design.MINI)
                    }
                }
            }
            Line("Median ${s.medianManaValue}. Lands excluded.", Ink3, Design.MINI)
        }
    }

    if (s.pips.isNotEmpty() || s.sources.isNotEmpty()) {
        Panel {
            Line("Colour", Ink3, Design.MINI, FontWeight.SemiBold)
            val most = maxOf(
                s.pips.maxOfOrNull { it.value } ?: 0,
                s.sources.maxOfOrNull { it.value } ?: 0,
            )
            Pip.entries.forEach { pip ->
                val needs = s.pips.firstOrNull { it.label == pip.label }?.value ?: 0
                val makes = s.sources.firstOrNull { it.label == pip.label }?.value ?: 0
                if (needs == 0 && makes == 0) return@forEach
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(18.dp).background(c(Design.pip(pip.letter)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(
                            pip.letter, color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Track(needs, most, pip, "needs")
                        Track(makes, most, pip, "makes")
                    }
                }
            }
            if (s.unsupported.isNotEmpty()) {
                Line("No source for ${s.unsupported.joinToString(", ")}.", Warn, Design.MINI)
            }
        }
    }

    if (s.types.isNotEmpty()) Panel { Bars("Card types", s.types, s.totalCards) }
    if (s.rarities.isNotEmpty()) Panel { Bars("Rarity", s.rarities, s.totalCards) }

    if (s.unknown > 0) {
        Line(
            "${s.unknown} card${if (s.unknown == 1) "" else "s"} here have no printing in the " +
                "collection, so they are counted in the total and left out of every chart.",
            Ink3,
            Design.MINI,
        )
    }
}

@Composable
private fun Figure(n: String, k: String, tint: androidx.compose.ui.graphics.Color = Ink) {
    Column {
        Line(n, tint, Design.H2, FontWeight.SemiBold)
        Line(k, Ink3, Design.MINI)
    }
}

@Composable
private fun Track(n: Int, most: Int, pip: Pip, what: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(9.dp).background(Bg3, Radius)) {
            Box(
                Modifier.fillMaxWidth(if (most <= 0) 0f else n.toFloat() / most)
                    .height(9.dp)
                    .background(c(Design.pip(pip.letter)), Radius),
            )
        }
        Line("$n $what", Ink3, Design.MINI)
    }
}

@Composable
private fun Bars(title: String, bars: List<Bar>, total: Int) {
    Line(title, Ink3, Design.MINI, FontWeight.SemiBold)
    val most = bars.maxOfOrNull { it.value } ?: 0
    bars.forEach { bar ->
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(bar.label, Ink2, Design.MINI, modifier = Modifier.weight(0.42f))
            Box(Modifier.weight(1f).height(8.dp).background(Bg3, Radius)) {
                Box(
                    Modifier.fillMaxWidth(if (most <= 0) 0f else bar.value.toFloat() / most)
                        .height(8.dp)
                        .background(Accent, Radius),
                )
            }
            Line("${bar.value}", Ink3, Design.MINI)
        }
    }
    Line("Of $total cards.", Ink3, Design.MINI)
}

/**
 * The tokens the deck makes, below the list, as real cards.
 *
 * Scryfall names them in every card's `all_parts`, so these are
 * printed tokens with their own art rather than a phrase read out of
 * the rules text. Two 1/1 Warriors that differ only by colour are two
 * tokens, which is why the colour is on the row.
 */
@Composable
private fun TokenList(tokens: List<org.mattshoe.mtg.core.TokenCard>) {
    if (tokens.isEmpty()) return
    GroupHead("Tokens", "${tokens.size}")
    val open = androidx.compose.ui.platform.LocalUriHandler.current
    Panel {
        tokens.forEach { token ->
            Row(
                Modifier.fillMaxWidth()
                    // Where to buy one, when Scryfall has a listing.
                    // A token from a set nobody sells singles of stays
                    // a row rather than becoming a tap to nowhere.
                    .then(
                        token.tcgplayer?.let { url ->
                            Modifier.clickable { runCatching { open.openUri(url) } }
                        } ?: Modifier,
                    )
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(Bg3, Radius).clip(Radius)) {
                    token.art?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Line(token.name, Ink, Design.SMALL)
                        token.stats?.let { Line(it, Ink2, Design.MINI) }
                        Identity(token.colors)
                    }
                    Line(token.shortType, Ink3, Design.MINI)
                }
                if (token.tcgplayer != null) Line("↗", Ink3, Design.MINI)
                Line("${token.madeBy}×", Ink3, Design.MINI)
            }
        }
        Line(
            "Made by the cards in this deck. You will want these to hand — " +
                "each one goes to TCGplayer.",
            Ink3,
            Design.MINI,
        )
    }
}
