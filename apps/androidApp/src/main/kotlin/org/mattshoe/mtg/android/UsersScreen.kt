package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.Collector
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.UsersState

/**
 * The Users tab's front page: everybody's collection as a tile, with
 * their picture, their name and how much they have. Sibling of
 * `UsersPage` on the website.
 *
 * Matt: "a collection of 'cards' or 'tiles' or whatever, with a
 * profile Pic on it and stuff, stylish and attractive. kinda like how
 * the Guide page has things". So it is the Guide's tile, the same
 * raised surface and band, with a face on it. As many across as fit
 * at the website's 240 a tile, so a tablet gets a grid rather than a
 * column of very wide tiles.
 */
@Composable
fun UsersScreen(state: UsersState, onOpen: (Collector) -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("users"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Line("Everybody's collection, to look through.", Ink2, size = Design.SMALL)
        when {
            state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("users-busy"))
            state.error != null -> {
                Line(
                    "Could not load the users: ${state.error}",
                    Bad,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("users-error")
                        .background(Bad.copy(alpha = 0.12f), RadiusSm)
                        .border(1.dp, Bad.copy(alpha = 0.42f), RadiusSm)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                )
                Btn("Try again", modifier = Modifier.testTag("users-retry")) { onRetry() }
            }
            state.isEmpty -> Line("Nobody has a collection yet.", Ink3)
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 14.dp
                val across = ((maxWidth + gap) / (TILE.dp + gap)).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    state.rows.chunked(across).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { UserTile(it, Modifier.weight(1f), onOpen) }
                            // A short last row keeps its tiles the width of the rest.
                            repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** The narrowest a tile gets: `.user-tiles`' `minmax(240px, 1fr)`. */
private const val TILE = 240

@Composable
private fun UserTile(who: Collector, modifier: Modifier, onOpen: (Collector) -> Unit) {
    val strip = Brush.horizontalGradient("WUBRG".map { c(Design.pip(it.toString())) })
    Box(
        modifier
            .testTag("user-tile-${who.key}")
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius)
            .clickable { onOpen(who) },
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(strip))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Face(who, 56)
            Spacer(Modifier.width(14.dp))
            Column {
                Line(who.shownName, Ink, size = 18, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Line(who.blurb, Ink2, size = Design.SMALL)
            }
        }
    }
}

/** Their picture, or the first letter of their name in a ring where there is none. */
@Composable
private fun Face(c: Collector, size: Int) {
    val ring = Modifier.size(size.dp).testTag("user-face-${c.key}").clip(CircleShape).border(1.dp, Line2, CircleShape)
    if (c.avatar != null) {
        AsyncImage(model = c.avatar, contentDescription = null, modifier = ring, contentScale = ContentScale.Crop)
    } else {
        Box(ring.background(Bg3), contentAlignment = Alignment.Center) {
            Line(c.initial, Ink, size = 22, weight = FontWeight.Bold)
        }
    }
}

/**
 * Over somebody's Library: whose it is, and the way back to the tiles.
 * Sibling of `UsersPage`'s `BrowsedHead`.
 */
@Composable
fun BrowsedHead(name: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Design.WRAP_PAD_NARROW.dp, end = Design.WRAP_PAD_NARROW.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Btn("‹ Users", modifier = Modifier.testTag("users-back")) { onBack() }
        Spacer(Modifier.width(12.dp))
        Line("$name's cards", Ink, size = 16, weight = FontWeight.Bold, modifier = Modifier.testTag("users-whose"))
    }
}
