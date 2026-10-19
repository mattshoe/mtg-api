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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Tools
import org.mattshoe.mtg.core.View

/**
 * The Tools tab: Fetch and Entry as tiles. Sibling of `ToolsPage` on
 * the website.
 *
 * Matt: "the tools page should look very similar in presentation to
 * the users tab, but instead of profile avatars, use the current icons
 * on the bottom nav for them". So it is `UsersScreen`'s tile, with the
 * icon the tool had in the bar sitting in the ring a face sits in.
 */
@Composable
fun ToolsScreen(tools: List<View>, onOpen: (View) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("tools"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 14.dp
            val across = ((maxWidth + gap) / (TILE.dp + gap)).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                tools.chunked(across).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { ToolTile(it, Modifier.weight(1f), onOpen) }
                        // A short last row keeps its tiles the width of the rest.
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The narrowest a tile gets: `.user-tiles`' `minmax(240px, 1fr)`, which `.tool-tiles` shares. */
private const val TILE = 240

@Composable
private fun ToolTile(tool: View, modifier: Modifier, onOpen: (View) -> Unit) {
    val strip = Brush.horizontalGradient("WUBRG".map { c(Design.pip(it.toString())) })
    Box(
        modifier
            .testTag("tool-tile-${tool.slug}")
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius)
            .clickable { onOpen(tool) },
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(strip))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Bg3).border(1.dp, Line2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NavIcon(tool, Ink, 28.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Line(tool.label, Ink, size = 18, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Line(Tools.blurb(tool), Ink2, size = Design.SMALL)
            }
        }
    }
}
