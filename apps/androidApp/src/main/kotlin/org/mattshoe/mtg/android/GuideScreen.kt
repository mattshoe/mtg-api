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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * Matt: "just a list of color combos and names, in wubrg order, so
 * they can easily be scanned to find what i need". The groups and
 * what they mean follow underneath, for reading rather than finding.
 */
@Composable
private fun Colours() {
    Column(Modifier.fillMaxWidth().border(1.dp, Line, Radius).clip(Radius)) {
        Guide.list.forEachIndexed { i, combo ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            ComboRow(combo)
        }
    }
    Guide.colours.forEach { group ->
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("guide-group")) {
            Line(group.title, Ink, size = Design.H2, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Line(group.about, Ink3, size = Design.MINI)
            Spacer(Modifier.height(6.dp))
            group.combos.forEach { combo ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Line(
                        combo.pips, Ink3, size = Design.TINY, weight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp),
                    )
                    Line(combo.name, Ink, size = Design.SMALL)
                }
                if (combo.note.isNotEmpty()) {
                    Line(combo.note, Ink2, size = Design.MINI, modifier = Modifier.padding(start = 52.dp).testTag("combo-note"))
                }
            }
        }
    }
}

@Composable
private fun ComboRow(combo: Combo) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("combo-row-${combo.pips}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            combo.pips.forEach { ManaSymbol(it.toString(), size = 20.dp, text = 11.sp) }
        }
        Line(
            combo.name, Ink, size = 15, weight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).testTag("combo-name"),
        )
        Line(combo.pips, Ink3, size = Design.TINY, weight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
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
