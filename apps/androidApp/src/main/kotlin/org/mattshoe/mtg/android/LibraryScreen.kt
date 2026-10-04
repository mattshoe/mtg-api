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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.Sort

/**
 * The Library, on Android.
 *
 * Sibling of `LibraryPage` on the web, and deliberately the same
 * screen: the same heading, the same panel holding the name box and the
 * sort, the same ten-group filter accordion always on the page, the
 * same line saying which hundred of how many is on screen, and the same
 * grid of card pictures with the price bottom left. Neither decides
 * what page it is on — `Library` does.
 *
 * What it used to have and the website does not: a Search button, a
 * second owner picker above the panel, a Filters button that hid the
 * grid, and four of the fourteen sorts as buttons. Everything applies
 * as it is typed here too now, because a button that re-runs what
 * already ran is just a thing to forget to press.
 */
@Composable
fun LibraryScreen(
    state: Library,
    onState: (Library) -> Unit,
    onSearch: () -> Unit,
    onOpen: (CardRow) -> Unit,
    /**
     * Unused, and kept because the shell still holds the flag and
     * passes it. The filter groups are always on the page now, folded
     * away rather than hidden behind a button — hiding the whole panel
     * was a way to leave a filter applied with nothing on screen
     * saying so.
     */
    @Suppress("UNUSED_PARAMETER") showFilters: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onToggleFilters: () -> Unit = {},
    onExport: () -> Unit = {},
    complete: Completion = Completion(),
    onName: (Completion) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCheatsheet: () -> Unit = {},
    /** The lists the panel offers, read once on the first search. */
    facets: Facets = Facets(),
    /**
     * Handed down from `AppShell`, which keeps it alive across a trip
     * through the card screen — see the comment there. Defaulted so
     * every test that mounts this screen on its own, rather than
     * through the shell, keeps working unchanged.
     */
    gridState: LazyGridState = rememberLazyGridState(),
) {
    // One rule, the web's: anything that changes the filters asks the
    // database again. Without it, choosing a colour changed the state
    // and left the same hundred cards on screen, which reads as a
    // filter that does nothing.
    fun apply(next: Library) {
        onState(next)
        onSearch()
    }

    // Exactly the web's four states. Rows that are already on screen
    // stay there while a narrower search is in flight — dimmed rather
    // than replaced by one line of text, so the grid keeps its height
    // and you keep your place in it.
    val searching = state.busy && state.rows.isEmpty()
    val grid = !searching && state.error == null && !state.isEmpty

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().testTag("library"),
        contentPadding = PaddingValues(Design.WRAP_PAD_NARROW.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                PageHead("Library")
                Controls(state, ::apply, onSearch, onExport, complete, onName)
                FilterSheet(state.filters, facets) { apply(state.where(it)) }
                when {
                    searching -> Line("Searching…", Ink3)
                    state.error != null -> Line("Search failed: ${state.error}", Bad)
                    state.isEmpty -> Line("Nothing matches that.", Ink3)
                    else -> Line(if (state.busy) "Searching…" else state.showingLabel, Ink3)
                }
            }
        }

        if (grid) {
            items(state.rows, key = { "${it.owner}:${it.nameNorm}" }) { CardTile(it, state.busy, onOpen) }

            item(span = { GridItemSpan(maxLineSpan) }) { Pager(state, ::apply) }
        }
    }
}

@Composable
private fun Controls(
    state: Library,
    apply: (Library) -> Unit,
    onSearch: () -> Unit,
    onExport: () -> Unit,
    complete: Completion,
    onName: (Completion) -> Unit,
) {
    Panel {
        // One callback, not two — see `AppState.typedCardName`.
        AutocompleteField(
            label = "Card name",
            state = complete,
            onState = onName,
            onPick = { onSearch() },
        )

        // Sort and its direction on the left because they are what you
        // reach for; export pushed to the right because it is the one
        // thing here that leaves. No Search button and no owner
        // picker: whose collection it is lives in the Collection
        // group with every other filter.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
        ) {
            SortPicker(state, apply)
            Spacer(Modifier.weight(1f))
            // Only the clipboard. The web offers a file as well; the
            // phone's export hands the decklist to the clipboard, and
            // wiring a second destination is `MainActivity`'s to do.
            Ghost(ExportTo.CLIPBOARD.label, onClick = onExport)
        }
    }
}

/**
 * Sort as a dropdown rather than a row of buttons.
 *
 * Fourteen sorts do not fit across a phone, and four of them chosen
 * arbitrarily is a worse answer than all of them behind one control —
 * which is what this was. The arrow beside it flips the direction,
 * which is the other half of the question.
 */
@Composable
private fun SortPicker(state: Library, apply: (Library) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(176.dp)) {
            Dropdown(
                "sort",
                Sort.entries.map { it.slug to it.label },
                state.filters.sort.slug,
            ) { slug ->
                // `sortedBy`, not `sortBy`: picking a column from a
                // list must not silently reverse it.
                apply(state.sortedBy(Sort.of(slug)))
            }
        }
        Box(
            Modifier.semantics {
                contentDescription = state.filters.sort.directionLabel(state.filters.descending)
            },
        ) {
            // A box, not a bare glyph: `dir` on the web exists because
            // a lone arrow beside a chunky dropdown reads as an
            // accident rather than a control.
            Btn(if (state.filters.descending) "↓" else "↑") {
                // Not `where`: reversing the order does not change
                // which cards match, so it should not throw away the
                // page you are on.
                apply(state.copy(filters = state.filters.copy(descending = !state.filters.descending)))
            }
        }
    }
}

/**
 * One card: the picture, the price that hangs off it, and the name with
 * how many. The same shape as `.card` on the web, which carries no mana
 * cost (a string of `{1}{G}` with no symbols behind it), no set code
 * (the picture already shows it) and no free count (that is on the
 * card's own page, which has room to say whose it is).
 */
@Composable
private fun CardTile(card: CardRow, stale: Boolean, onOpen: (CardRow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().alpha(if (stale) 0.55f else 1f).clickable { onOpen(card) },
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
            Prices.money(card.price, dash = "").takeIf { it.isNotEmpty() }?.let {
                Badge(it, Accent2, Modifier.align(Alignment.BottomStart).padding(5.dp))
            }
        }
        // The name and how many, on one line. The name gives way so
        // the quantity is always readable.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selectable, the same as the web. A canvas renderer would
            // have taken copying a card name away.
            SelectionContainer(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    card.fullName,
                    color = Ink,
                    fontSize = Design.MINI.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Line("×${card.qty}", Ink2, Design.TINY, FontWeight.Bold)
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
    FlowRow(
        Modifier.fillMaxWidth().testTag("pager").padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
