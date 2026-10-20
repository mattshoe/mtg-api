package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Tr
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Bar
import org.mattshoe.mtg.core.ColourRing
import org.mattshoe.mtg.core.CurveSplit
import org.mattshoe.mtg.core.DeckStats
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.RingSlice

/**
 * What the deck is made of, drawn.
 *
 * Every number comes from `DeckAnalysis` in the shared core, so the
 * phone shows the same deck. The charts are divs with a width or a
 * height — a charting library would be more bytes than the whole
 * bundle and the page is not allowed to fetch one anyway.
 */
@Composable
fun DeckStatsPanel(
    s: DeckStats,
    guild: String? = null,
    /** A creature type's bar was tapped: the carousel over that type. */
    onTribe: (String) -> Unit = {},
) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("The deck at a glance") }
            // Azorius, Jund, Mono-red. A row of pips says which
            // colours; it does not say what they are called, and the
            // name is how people talk about a deck. Matt: "I want the
            // 'deck at a glance' view to show which guild or whatever
            // you call it."
            guild?.let {
                Span(attrs = { classes("spacer") }) {}
                Span(attrs = { classes("tag", "guild") }) { Text(it) }
            }
        }

        // The numbers stand on the panel itself. They were cells with
        // seams between them inside the bordered panel, and Matt: "i
        // don't like this 'tile in tile' layout ... it feels sloppy".
        // The list is `DeckStats.figures`, the one the phone draws.
        Div(attrs = { classes("figures", "glance") }) {
            s.figures.forEach { figure(it.n, it.k, warn = it.warn, hint = it.hint) }
        }

        Div(attrs = { classes("stats-grid", "glance") }) {
            if (s.hasCurve) card("Mana curve") { Curve(s) }
            if (s.pips.isNotEmpty() || s.sources.isNotEmpty()) card("Colour") { Colours(s) }
            if (s.types.isNotEmpty()) card("Card types") { Bars(s.types, s.totalCards) }
            // A Human Warrior is a bar each for Human and Warrior.
            if (s.creatureTypes.isNotEmpty()) {
                card("Creature types") { Bars(s.creatureTypes, s.creatures, s.creatureTypesNote, onTap = onTribe) }
            }
            if (s.rarities.isNotEmpty()) card("Rarity") { Bars(s.rarities, s.totalCards) }
        }

        if (s.unknown > 0) {
            Div(attrs = { classes("panel-body") }) {
                Div(attrs = { classes("muted", "small") }) {
                    Text(
                        "${s.unknown} card${if (s.unknown == 1) "" else "s"} in this list have no " +
                            "printing in the collection, so nothing is known about them — they are " +
                            "counted in the total and left out of every chart above.",
                    )
                }
            }
        }
    }
}

@Composable
private fun figure(n: String, k: String, warn: Boolean = false, hint: String = "") {
    Div(attrs = {
        classes("figure")
        if (warn) classes("warn")
        attr("title", if (hint.isEmpty()) "$n $k" else "$n $k — $hint")
    }) {
        Div(attrs = { classes("n") }) { Text(n) }
        Div(attrs = { classes("k") }) { Text(k) }
    }
}

@Composable
private fun card(title: String, wide: Boolean = false, body: @Composable () -> Unit) {
    Div(attrs = {
        classes("stats-card")
        if (wide) classes("wide")
    }) {
        H3 { Text(title) }
        body()
    }
}

/**
 * The curve, as columns.
 *
 * Lands are not in it — they cost nothing and would put a third of
 * the deck in the nought column, which is the one thing a curve must
 * not say.
 */
@Composable
private fun Curve(s: DeckStats) {
    // Matt: "i want a dropdown to select what my mana curve graph
    // shows. it defaults to all cards". Card type and Colour draw a
    // graph each, all on one scale so a tall column means the same
    // number of cards in every one of them.
    var split by remember { mutableStateOf(CurveSplit.DEFAULT) }
    Select(attrs = {
        classes("field", "curve-split")
        attr("aria-label", "Show the mana curve by")
        onChange { e -> split = CurveSplit.of(e.value ?: "") }
    }) {
        CurveSplit.entries.forEach { c ->
            Option(c.slug, attrs = { if (c == split) attr("selected", "") }) { Text(c.label) }
        }
    }
    val charts = s.curves(split)
    val most = charts.maxOfOrNull { c -> c.bars.maxOfOrNull { it.value } ?: 0 } ?: 0
    charts.forEach { c ->
        Div(attrs = {
            classes("curve-chart")
            if (charts.size > 1) classes("split")
        }) {
            if (charts.size > 1) Div(attrs = { classes("curve-title") }) { Text(c.title) }
            CurveColumns(c.bars, most)
        }
    }
    Div(attrs = { classes("sub") }) { Text("Lands excluded.") }
}

@Composable
private fun CurveOf(curve: List<Bar>, note: String) {
    CurveColumns(curve, curve.maxOfOrNull { it.value } ?: 0)
    Div(attrs = { classes("sub") }) { Text(note) }
}

/** The columns and their labels, measured against [most] so several graphs share one scale. */
@Composable
private fun CurveColumns(curve: List<Bar>, most: Int) {
    Div(attrs = { classes("curve") }) {
        curve.forEach { bar ->
            Div(attrs = {
                classes("col")
                if (bar.value == 0) classes("none")
                attr("data-mv", bar.label.trimEnd('+'))
                attr("title", "${bar.value} at ${bar.note}")
            }) {
                // The bar measures itself against the track, not
                // against the column: a percentage of the column
                // overflows, and flexbox then squashes every tall bar
                // to the same height. Fifteen and seventeen drew
                // identical. The number rides on top of its own bar
                // rather than sitting in a row along the top of the
                // chart, where it was nowhere near what it counted.
                Div(attrs = { classes("track") }) {
                    Div(attrs = {
                        classes("bar")
                        // A percentage rather than a pixel count, so
                        // the chart is right at any size.
                        style { property("height", "${bar.share(most)}%") }
                    }) {
                        Div(attrs = { classes("n") }) { Text(if (bar.value > 0) "${bar.value}" else "") }
                    }
                }
            }
        }
    }
    Div(attrs = { classes("curve") ; style { property("height", "auto") } }) {
        curve.forEach { bar -> Div(attrs = { classes("col") }) { Div(attrs = { classes("x") }) { Text(bar.label) } } }
    }
}

// The deck page's chart pieces, lent to the Stats page so the two
// draw a bar, a ring and a curve the same way.

@Composable
internal fun StatFigure(n: String, k: String, hint: String = "") = figure(n, k, hint = hint)

@Composable
internal fun StatChart(title: String, wide: Boolean = false, body: @Composable () -> Unit) = card(title, wide, body)

@Composable
internal fun StatBars(bars: List<Bar>, total: Int, note: String, shown: (Int) -> String = { "$it" }) =
    Bars(bars, total, note, shown = shown)

@Composable
internal fun StatPie(caption: String, bars: List<Bar>, numbered: List<RingSlice> = emptyList()) =
    Pie(caption, bars, numbered)

@Composable
internal fun StatCurve(curve: List<Bar>, note: String) = CurveOf(curve, note)

/**
 * What the deck asks for against what it can make, per colour.
 *
 * Two bars on one track: pips needed on top, sources below at half
 * strength. A splash with no sources is the thing this is for, and it
 * is invisible in either chart on its own.
 */
@Composable
private fun Colours(s: DeckStats) {
    val most = maxOf(
        s.pips.maxOfOrNull { it.value } ?: 0,
        s.sources.maxOfOrNull { it.value } ?: 0,
    )
    Pip.entries.forEach { pip ->
        val needs = s.pips.firstOrNull { it.label == pip.label }?.value ?: 0
        val makes = s.sources.firstOrNull { it.label == pip.label }?.value ?: 0
        if (needs == 0 && makes == 0) return@forEach
        Div(attrs = { classes("mana-row") }) {
            ManaPip(pip.letter, "lg")
            Div(attrs = { classes("pair") }) {
                track(needs, most, pip, "needs", faded = false)
                track(makes, most, pip, "makes", faded = true)
            }
        }
    }
    Div(attrs = { classes("pies") }) {
        Pie(ColourRing.NEEDS.caption, s.pips)
        Pie(ColourRing.SOURCES.caption, s.sources)
        // Sources again, with a dual as its own slice rather than a
        // point in each colour's total. On a line of its own and
        // bigger, because it has the most slices to tell apart.
        Pie(ColourRing.PRODUCTION.caption, s.combos, numbered = s.exactly)
    }
    if (s.unsupported.isNotEmpty()) {
        Div(attrs = { classes("sub") }) {
            Text("No source for ${s.unsupported.joinToString(", ")}.")
        }
    }
}

@Composable
private fun track(n: Int, most: Int, pip: Pip, what: String, faded: Boolean) {
    Div(attrs = { classes("mana-track"); attr("title", "$n $what") }) {
        Div(attrs = { classes("rail") }) {
            Div(attrs = {
                classes("fill")
                if (faded) classes("makes")
                style {
                    property("width", "${if (most <= 0) 0 else (n * 100) / most}%")
                    property("background", "var(--${pip.letter.lowercase()})")
                }
            }) {}
        }
        Span(attrs = { classes("v") }) { Text("$n $what") }
    }
}

@Composable
private fun Bars(
    bars: List<Bar>,
    total: Int,
    note: String = "Of $total cards.",
    onTap: ((String) -> Unit)? = null,
    /** How a bar's number reads: a count, or dollars on the Stats page. */
    shown: (Int) -> String = { "$it" },
) {
    val most = bars.maxOfOrNull { it.value } ?: 0
    bars.forEach { bar ->
        val share = if (total <= 0) 0 else (bar.value * 100) / total
        Div(attrs = {
            classes("hbar")
            attr("title", "${bar.value} of $total")
            onTap?.let { tap ->
                classes("tap")
                onClick { tap(bar.label) }
            }
        }) {
            Span(attrs = { classes("k") }) { Text(bar.label) }
            Div(attrs = { classes("rail") }) {
                Div(attrs = { classes("fill"); style { property("width", "${bar.share(most)}%") } }) {}
            }
            Span(attrs = { classes("v") }) { Text(shown(bar.value)) }
        }
    }
    Div(attrs = { classes("sub") }) { Text(note) }
}


/**
 * One colour split, as a ring.
 *
 * A bar says how many white pips there are; a ring says what share of
 * the deck's colour is white, which is the question you ask when
 * deciding whether a splash is really a splash. Drawn with a single
 * `conic-gradient`, so the chart is one CSS property and no script.
 */
@Composable
private fun Pie(caption: String, bars: List<Bar>, numbered: List<RingSlice> = emptyList()) {
    val wide = numbered.isNotEmpty()
    val total = bars.sumOf { it.value }
    if (total <= 0) return
    var at = 0.0
    // A combination is one colour, its colours mixed: `Bar.fill`.
    val stops = bars.joinToString(", ") { bar ->
        val from = at
        at += (bar.value * 100.0) / total
        val paint = bar.letters.singleOrNull()?.let { "var(--${it.lowercase()})" } ?: Design.css(bar.fill)
        "$paint ${from}% ${at}%"
    }
    Div(attrs = {
        classes("pie-set")
        if (wide) classes("wide")
    }) {
        Div(attrs = {
            classes("pie")
            style { property("background", "conic-gradient($stops)") }
            attr(
                "title",
                bars.joinToString(", ") { "${it.label} ${(it.value * 100) / total}%" },
            )
        }) {
            // Each slice's number on the slice itself, over a dark
            // disc so it reads on any colour. Matt: "It's not at all
            // clear which slice is which in the exactly chart."
            numbered.forEach { slice ->
                val (x, y) = slice.at(0.62)
                Span(attrs = {
                    classes("slice-no")
                    style {
                        property("left", "${x * 100}%")
                        property("top", "${y * 100}%")
                    }
                }) { Text("${slice.number}") }
            }
        }
        Span(attrs = { classes("pie-cap") }) { Text("$caption · $total") }
        if (wide) {
            // The legend, as a table: the number on the slice, its
            // symbols, its name and how many cards.
            Table(attrs = { classes("pie-table") }) {
                Tbody {
                    numbered.forEach { slice ->
                        Tr {
                            Td(attrs = { classes("no") }) { Text("${slice.number}") }
                            Td(attrs = { classes("syms") }) { slice.bar.letters.forEach { ManaPip(it, "sm") } }
                            Td { Text(slice.name) }
                            Td(attrs = { classes("num") }) { Text("${slice.bar.value}") }
                            Td(attrs = { classes("num") }) { Text("${slice.percent}%") }
                        }
                    }
                }
            }
            return@Div
        }
        Div(attrs = { classes("pie-key") }) {
            bars.forEach { bar ->
                // Every colour of a combination keyed by its symbol,
                // so UR reads {U}{R} and not a tint.
                Span(attrs = { classes("k") }) {
                    bar.letters.forEach { ManaPip(it, "sm") }
                    Text("${(bar.value * 100) / total}%")
                }
            }
        }
    }
}
