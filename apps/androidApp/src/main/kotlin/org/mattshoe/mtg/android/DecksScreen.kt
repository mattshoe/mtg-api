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
            PageHead(open.title)
            Line(
                listOfNotNull(
                    open.commanderName,
                    open.bracket?.let { "Bracket $it" },
                ).joinToString(" · "),
                Ink3,
            )
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
            Panel {
                state.cards.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Line("${c.qty}×", Ink3, Design.MINI)
                        Line(c.name, Ink, Design.SMALL)
                        Spacer(Modifier.weight(1f))
                        if (c.owned < c.qty) Tag("has ${c.owned}", Bad)
                    }
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
