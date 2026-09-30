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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
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
                    Line(
                        owner.replaceFirstChar(Char::uppercase),
                        Ink,
                        Design.H2,
                        FontWeight.SemiBold,
                        Modifier.padding(top = 6.dp),
                    )
                    decks.forEach { DeckTile(it, onOpen) }
                }
            }
        } else {
            Ghost("← Decks", onClick = onClose)
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
                Row(horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                    Btn("Edit list") { onEdit(open) }
                    Btn("Disassemble", danger = true) { onDisassemble(open) }
                }
            }
            DeckStats(DeckAnalysis.of(state.cards))
            // `byType` is the core's, the same list the website reads,
            // so the two cannot group or order a deck differently.
            state.byType.forEach { (group, cards) ->
                Line(
                    group.title,
                    Ink,
                    Design.H3,
                    FontWeight.SemiBold,
                    Modifier.padding(top = 6.dp),
                )
                Panel {
                    cards.forEach { c -> CardLine(c, open.owner, onOpenCard) }
                }
            }
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
private fun CardLine(card: DeckCard, owner: String, onOpen: (DeckCard, String) -> Unit) {
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
            card.typeLine?.takeIf { it.isNotBlank() }?.let { Line(it, Ink3, Design.MINI) }
        }
        if (card.short > 0) Tag("has ${card.owned}", Bad)
        Line("${card.qty}×", Ink3, Design.MINI)
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
        Line("The deck at a glance", Ink, Design.H3, FontWeight.SemiBold)
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
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight(if (most <= 0) 0f else bar.value.toFloat() / most)
                                .background(if (bar.value > 0) Accent else Bg3, Radius),
                        )
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

    if (s.tokens.isNotEmpty()) {
        Panel {
            Line("Tokens it makes", Ink3, Design.MINI, FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                s.tokens.forEach { t -> Tag("${t.cards}× ${t.what}") }
            }
        }
    }

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
