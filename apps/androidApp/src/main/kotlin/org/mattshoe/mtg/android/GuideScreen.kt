package org.mattshoe.mtg.android

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.ColourWheel
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile
import org.mattshoe.mtg.core.Rule
import org.mattshoe.mtg.core.Rules
import org.mattshoe.mtg.core.WheelColour
import org.mattshoe.mtg.core.WheelPair

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
fun GuideScreen(
    tile: GuideTile?,
    onOpen: (GuideTile) -> Unit,
    onBack: () -> Unit,
    rules: Rules = Rules(),
    onRules: (Rules) -> Unit = {},
    onLoadRules: () -> Unit = {},
) {
    // The rules page keeps its search bar on screen and scrolls the
    // cards under it, so it is laid out on its own.
    if (tile?.slug == Guide.RULES) {
        RulesSearch(rules, onRules, onLoadRules, onBack)
        return
    }
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
                Guide.WHEEL -> Wheel()
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
 * The colour wheel: what it is, the wheel drawn, then each colour and
 * each pair. Everything said is in `ColourWheel`. Sibling of the
 * site's `Wheel` in `GuidePage`.
 */
@Composable
private fun Wheel() {
    ColourWheel.intro.forEach { Line(it, Ink2, size = 14) }
    // A fixed size, as on the site, so a tablet gets the same wheel as
    // a phone rather than one taller than the screen.
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(WHEEL.dp).testTag("wheel")) {
            // Allies joined by a solid line round the outside, enemies by
            // a dashed one across the middle: told apart by the dash, not
            // by a hue, because Matt is colourblind.
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2 * RADIUS
                val at = ColourWheel.colours.map { c ->
                    ColourWheel.point(c.pip).let { (x, y) -> Offset(center.x + (x * r).toFloat(), center.y + (y * r).toFloat()) }
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                for (i in 0 until 5) {
                    drawLine(Ink3, at[i], at[(i + 1) % 5], strokeWidth = 2.dp.toPx())
                    drawLine(Ink3, at[i], at[(i + 2) % 5], strokeWidth = 2.dp.toPx(), pathEffect = dash)
                }
            }
            ColourWheel.colours.forEach { c ->
                val (x, y) = ColourWheel.point(c.pip)
                val dx = (x * WHEEL / 2 * RADIUS).dp
                val dy = (y * WHEEL / 2 * RADIUS).dp
                Box(
                    Modifier.align(Alignment.Center).offset(dx, dy).size(52.dp).clip(CircleShape).background(Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    ManaSymbol(c.pip, size = 44.dp, text = 20.sp, tag = "wheel-spoke-${c.pip}")
                }
                Line(
                    c.name, Ink, size = Design.MINI, weight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).offset(dx, dy + 34.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Key(dashed = false, "Solid: allies, side by side")
        Spacer(Modifier.width(18.dp))
        Key(dashed = true, "Dashed: enemies, across")
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // One column on a phone, two once there is room, as the site's
        // grid does.
        val across = if (maxWidth >= 560.dp) 2 else 1
        Column(Modifier.fillMaxWidth()) {
            Group("The five colours", "Clockwise from the top.")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColourWheel.colours.chunked(across).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { WheelColourCard(it, Modifier.weight(1f)) }
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Pairs("Allied pairs", "Side by side on the wheel. They want similar things.", ColourWheel.allied)
            Spacer(Modifier.height(12.dp))
            Pairs("Enemy pairs", "Across the wheel. They clash, and a deck of both gets the best of each.", ColourWheel.enemy)
        }
    }
}

@Composable
private fun Group(title: String, about: String) {
    Spacer(Modifier.height(10.dp))
    Line(title, Ink, size = Design.H2, weight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Line(about, Ink3, size = Design.MINI)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Key(dashed: Boolean, words: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(22.dp).height(2.dp)) {
            drawLine(
                Ink3, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Line(words, Ink3, size = Design.MINI)
    }
}

@Composable
private fun WheelColourCard(c: WheelColour, modifier: Modifier) {
    Card(c.pip, modifier.testTag("wheel-colour-${c.pip}"), pad = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ManaSymbol(c.pip, size = 26.dp, text = 13.sp)
            Spacer(Modifier.width(8.dp))
            Line(c.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("wheel-colour-name"))
        }
        Said("Wants", c.wants, "wheel-wants")
        Said("Good at", c.goodAt, "wheel-good")
        Said("Bad at", c.badAt, "wheel-bad")
        Said("Allies", ColourWheel.alliesOf(c.pip), "wheel-allies-${c.pip}")
        Said("Enemies", ColourWheel.enemiesOf(c.pip), "wheel-enemies-${c.pip}")
    }
}

@Composable
private fun Said(label: String, words: String, tag: String) {
    Spacer(Modifier.height(6.dp))
    Line(label.uppercase(), Ink3, size = Design.TINY, weight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    Line(words, Ink2, size = Design.SMALL, modifier = Modifier.testTag(tag))
}

@Composable
private fun Pairs(title: String, about: String, pairs: List<WheelPair>) {
    Group(title, about)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pairs.chunked(2).forEach { two ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                two.forEach { p ->
                    Card(p.pips, Modifier.weight(1f).testTag("wheel-pair-${p.pips}"), pad = 12) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            p.pips.forEach { ManaSymbol(it.toString(), size = 22.dp, text = 12.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Line(p.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("wheel-pair-name"))
                        Spacer(Modifier.height(3.dp))
                        Line(p.why, Ink2, size = Design.MINI)
                    }
                }
                if (two.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The wheel's width, the same as the site's `.wheel`. */
private const val WHEEL = 260

/** How far out the symbols sit, as a share of the wheel's radius: the site's 38% of its width. */
private const val RADIUS = 0.76

/**
 * The Comprehensive Rules, searched as you type. Sibling of the site's
 * `RulesSearch`.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default". The file is fetched once, through the Worker,
 * and `RuleBook` parses and searches it here, on the phone.
 *
 * A plain scrolling column, not a lazy one, for the reason the tiles
 * give: a page of results is at most `Rules.SHOWN` cards.
 */
@Composable
private fun RulesSearch(rules: Rules, onRules: (Rules) -> Unit, onLoad: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Design.WRAP_PAD_NARROW.dp).testTag("guide")) {
        Btn("‹ Guide", modifier = Modifier.testTag("guide-back")) { onBack() }
        Spacer(Modifier.height(10.dp))
        Field(
            value = rules.query,
            onValueChange = { onRules(rules.searched(it)) },
            placeholder = "Search the rules: trample, 702.19, commander tax",
            modifier = Modifier.testTag("rules-search"),
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val book = rules.book
            when {
                rules.busy -> Line("Loading the Comprehensive Rules…", Ink3, modifier = Modifier.testTag("rules-busy"))
                rules.error != null -> {
                    Line("Could not load the rules: ${rules.error}", Bad, modifier = Modifier.testTag("rules-err"))
                    Btn("Try again", modifier = Modifier.testTag("rules-retry")) { onLoad() }
                }
                book == null -> Line("The rules have not loaded yet.", Ink3)
                rules.query.isBlank() -> {
                    Line("Type a word or a rule number to find every rule that mentions it.", Ink2, modifier = Modifier.testTag("rules-hint"))
                    if (book.effective.isNotEmpty()) Line(book.effective, Ink3, size = Design.MINI)
                }
                rules.total == 0 -> Line("No rule mentions \"${rules.query.trim()}\".", Ink3, modifier = Modifier.testTag("rules-none"))
                else -> {
                    Line(rules.count, Ink3, size = Design.MINI, modifier = Modifier.testTag("rules-count"))
                    rules.hits.forEach { r -> RuleCard(r, rules.isOpen(r)) { onRules(rules.toggled(r)) } }
                }
            }
        }
    }
}

/**
 * The site's `.rule-card`: the number as the heading, heavy and in the
 * accent, with a rule of it down the left edge; a tap on the heading
 * folds the text and examples away.
 */
@Composable
private fun RuleCard(rule: Rule, open: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .testTag("rule-${rule.number}")
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius)
            .drawBehind { drawRect(Accent, size = Size(3.dp.toPx(), size.height)) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onToggle() }
                    .testTag("rule-head")
                    .padding(start = 17.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Line(
                    rule.shownNumber, Accent, size = 15, weight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("rule-number"),
                )
                Line(rule.section, Ink3, size = Design.MINI, maxLines = 1, modifier = Modifier.weight(1f))
                Line(if (open) "▾" else "▸", Ink2)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 17.dp, end = 14.dp, bottom = 13.dp).testTag("rule-body"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Line(rule.text, Ink, size = Design.BODY, modifier = Modifier.testTag("rule-text"))
                    rule.examples.forEach { e ->
                        Line(
                            e, Ink2, size = Design.SMALL,
                            modifier = Modifier.fillMaxWidth().clip(RadiusSm).background(Bg).padding(8.dp).testTag("rule-example"),
                        )
                    }
                }
            }
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
