package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile

/**
 * Tips and references, on Android. Sibling of `GuidePage`.
 *
 * The front page is a column of tiles; a tile is a page of its own,
 * `#/guide/<slug>` on the site. Everything on it is in `Guide`, so
 * the two cannot list different names for the same colours.
 *
 * A plain scrolling column rather than a lazy grid: thirty-one tiles
 * is nothing, and a lazy grid never composes what is below the fold,
 * which is exactly where a test would go looking.
 */
@Composable
fun GuideScreen(tile: GuideTile?, onOpen: (GuideTile) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("guide"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tile == null) {
            Guide.tiles.forEach { t ->
                Card(
                    "WUBRG",
                    Modifier.fillMaxWidth().testTag("guide-tile-${t.slug}").clickable { onOpen(t) },
                    pad = 18,
                ) {
                    Line(t.title, Ink, size = 18, weight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Line(t.blurb, Ink2, size = Design.SMALL)
                }
            }
        } else {
            Btn("‹ Guide", modifier = Modifier.testTag("guide-back")) { onBack() }
            when (tile.slug) {
                Guide.COLOURS -> Colours()
            }
        }
    }
}

@Composable
private fun Colours() {
    Guide.colours.forEach { group ->
        Column(Modifier.fillMaxWidth().testTag("guide-group")) {
            Line(group.title, Ink, size = Design.H2, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Line(group.about, Ink3, size = Design.MINI)
            Spacer(Modifier.height(10.dp))
            // Two to a row, the way the site's grid lays out at a
            // phone's width. A lone last tile keeps its half.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.combos.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { ComboTile(it, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboTile(combo: Combo, modifier: Modifier) {
    Card(combo.pips, modifier.testTag("combo-${combo.pips}"), pad = 12) {
        Box(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                combo.pips.forEach { ManaSymbol(it.toString(), size = 22.dp, text = 12.sp) }
            }
            Line(
                combo.pips, Ink3, size = Design.TINY, weight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Spacer(Modifier.height(8.dp))
        Line(combo.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("combo-name"))
        if (combo.note.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Line(combo.note, Ink2, size = Design.MINI, modifier = Modifier.testTag("combo-note"))
        }
    }
}

/**
 * The site's `.guide-tile` and `.combo`: a raised surface with the
 * colours as a strip across its top. The strip is decoration; the
 * words and the mana symbols carry it, because Matt is colourblind.
 */
@Composable
private fun Card(pips: String, modifier: Modifier, pad: Int, content: @Composable () -> Unit) {
    val colours = pips.map { c(Design.pip(it.toString())) }
    val strip = if (colours.size == 1) Brush.horizontalGradient(colours + colours) else Brush.horizontalGradient(colours)
    Box(
        modifier
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius),
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(strip).testTag("guide-band"))
        Column(Modifier.fillMaxWidth().padding(start = pad.dp, end = pad.dp, top = (pad + 4).dp, bottom = pad.dp)) {
            content()
        }
    }
}
