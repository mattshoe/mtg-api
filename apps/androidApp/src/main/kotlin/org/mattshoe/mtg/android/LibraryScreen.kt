package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.Sort

/**
 * The Library, on Android.
 *
 * Sibling of `LibraryPage` on the web, and deliberately the same
 * screen: the same heading, the same panel, the same segmented owner
 * control, the same row of ghost buttons, and the same grid of card
 * pictures with the free badge bottom left and the price bottom right.
 * Neither decides what page it is on — `Library` does.
 *
 * A grid rather than a list, because that is what the site is, and the
 * pictures are how anybody actually finds a card.
 */
@Composable
fun LibraryScreen(
    state: Library,
    onState: (Library) -> Unit,
    onSearch: () -> Unit,
    onOpen: (CardRow) -> Unit,
    showFilters: Boolean = false,
    onToggleFilters: () -> Unit = {},
    onExport: () -> Unit = {},
    complete: Completion = Completion(),
    onComplete: (Completion) -> Unit = {},
    onCheatsheet: () -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Design.WRAP_PAD_NARROW.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                PageHead("Library")
                Controls(
                    state, onState, onSearch, showFilters,
                    onToggleFilters, onExport, complete, onComplete, onCheatsheet,
                )
                if (showFilters) FilterSheet(state.filters, { onState(state.where(it)) }, onToggleFilters)
                when {
                    state.busy -> Line("Searching…", Ink3)
                    state.error != null -> Line("Search failed: ${state.error}", Bad)
                    state.isEmpty -> Line("Nothing matches that.", Ink3)
                    else -> Line("${state.showing.first}–${state.showing.last} of ${state.total}", Ink3)
                }
            }
        }

        if (!showFilters) {
            items(state.rows, key = { "${it.owner}:${it.nameNorm}" }) { CardTile(it, onOpen) }

            if (state.rows.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Pager(state) { onState(it); onSearch() }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: Library,
    onState: (Library) -> Unit,
    onSearch: () -> Unit,
    showFilters: Boolean,
    onToggleFilters: () -> Unit,
    onExport: () -> Unit,
    complete: Completion,
    onComplete: (Completion) -> Unit,
    onCheatsheet: () -> Unit,
) {
    Panel {
        AutocompleteField(
            label = "Card name",
            state = complete,
            onState = { c ->
                onComplete(c)
                onState(state.where(state.filters.copy(q = c.term)))
            },
            onPick = { onSearch() },
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
        ) {
            Seg(
                listOf("both" to "Both", "matt" to "Matt", "kayla" to "Kayla"),
                state.filters.owner,
            ) { onState(state.where(state.filters.copy(owner = it))) }
            Spacer(Modifier.weight(1f))
            Primary("Search", enabled = !state.busy, onClick = onSearch)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ghost(if (showFilters) "Hide filters" else "Filters", on = showFilters, onClick = onToggleFilters)
            Ghost("Export decklist", onClick = onExport)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Sort.PRICE, Sort.NAME, Sort.CMC, Sort.QTY).forEach { sort ->
                val on = state.filters.sort == sort
                Ghost(
                    sort.label + if (on) (if (state.filters.descending) " ↓" else " ↑") else "",
                    on = on,
                ) { onState(state.sortBy(sort)); onSearch() }
            }
        }
    }
}

/**
 * One card: the picture, the badges that hang off it, and two lines of
 * caption. The same shape as `.card` on the web.
 */
@Composable
private fun CardTile(card: CardRow, onOpen: (CardRow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(card) },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(Design.CARD_ASPECT)) {
            // 4.75% of the width, which is what the stylesheet rounds a
            // card frame by.
            val frame = RoundedCornerShape(5)
            AsyncImage(
                model = CardQueries.art(card.scryfallId, "normal"),
                contentDescription = card.fullName,
                modifier = Modifier.fillMaxSize().background(Bg3, frame).clip(frame),
                contentScale = ContentScale.Crop,
            )
            val free = card.free ?: 0
            Badge(
                if (free > 0) "$free free" else "in decks",
                if (free > 0) Ok else Ink3,
                Modifier.align(Alignment.BottomStart).padding(5.dp),
            )
            Prices.money(card.price, dash = "").takeIf { it.isNotEmpty() }?.let {
                Badge(it, Accent2, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }
        }
        // Selectable, the same as the web. A canvas renderer would have
        // taken copying a card name away.
        SelectionContainer {
            Line(card.fullName, Ink, Design.MINI, FontWeight.Medium)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(card.manaCost.orEmpty(), Ink3, Design.TINY)
            Spacer(Modifier.weight(1f))
            Line("×${card.qty}", Ink2, Design.TINY, FontWeight.Bold)
            Line(card.setCode?.uppercase().orEmpty(), Ink3, Design.TINY)
        }
    }
}

@Composable
private fun Badge(label: String, tone: Color, modifier: Modifier) {
    androidx.compose.material3.Text(
        label,
        modifier
            .background(Bg.copy(alpha = 0.82f), RadiusSm)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = tone,
        fontSize = Design.TINY.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Pager(state: Library, go: (Library) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ghost("← Previous", enabled = state.hasPrev) { go(state.prev()) }
        Line("Page ${state.page} of ${state.pages}", Ink3)
        Ghost("Next →", enabled = state.hasNext) { go(state.next()) }
    }
}

/** Plain text at the site's sizes, so none of it drifts to Material's. */
@Composable
internal fun Line(
    value: String,
    color: Color = Ink,
    size: Int = Design.SMALL,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) = androidx.compose.material3.Text(
    value,
    modifier,
    color = color,
    fontSize = size.sp,
    fontWeight = weight,
)
