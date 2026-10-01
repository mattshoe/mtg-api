package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Bar
import org.mattshoe.mtg.core.DeckStats
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices

/**
 * What the deck is made of, drawn.
 *
 * Every number comes from `DeckAnalysis` in the shared core, so the
 * phone shows the same deck. The charts are divs with a width or a
 * height — a charting library would be more bytes than the whole
 * bundle and the page is not allowed to fetch one anyway.
 */
@Composable
fun DeckStatsPanel(s: DeckStats) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) { H2 { Text("The deck at a glance") } }

        Div(attrs = { classes("figures") }) {
            figure("${s.totalCards}", "cards")
            figure("${s.lands}", "lands · ${s.landShare}%")
            figure(s.averageManaValue.toString(), "avg mana")
            figure("${s.spells}", "spells")
            s.value?.let { figure(Prices.money(it), "value", hint = if (s.unpriced > 0) "${s.unpriced} cards have no price" else "") }
            if (s.missing > 0) figure("${s.missing}", "not owned", warn = true)
        }

        Div(attrs = { classes("stats-grid") }) {
            if (s.hasCurve) card("Mana curve") { Curve(s) }
            if (s.pips.isNotEmpty() || s.sources.isNotEmpty()) card("Colour") { Colours(s) }
            if (s.types.isNotEmpty()) card("Card types") { Bars(s.types, s.totalCards) }
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
    val most = s.curve.maxOfOrNull { it.value } ?: 0
    Div(attrs = { classes("curve") }) {
        s.curve.forEach { bar ->
            Div(attrs = {
                classes("col")
                if (bar.value == 0) classes("none")
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
        s.curve.forEach { bar -> Div(attrs = { classes("col") }) { Div(attrs = { classes("x") }) { Text(bar.label) } } }
    }
    Div(attrs = { classes("sub") }) { Text("Median ${s.medianManaValue}. Lands excluded.") }
}

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
        Pie("Needs", s.pips)
        Pie("Makes", s.sources)
    }
    Div(attrs = { classes("sub") }) {
        Text("Pips the deck asks for, against cards that can produce them. ")
        Text("Hybrid pips count for both halves.")
        if (s.unsupported.isNotEmpty()) {
            Text(" No source for ${s.unsupported.joinToString(", ")}.")
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
private fun Bars(bars: List<Bar>, total: Int) {
    val most = bars.maxOfOrNull { it.value } ?: 0
    bars.forEach { bar ->
        val share = if (total <= 0) 0 else (bar.value * 100) / total
        Div(attrs = { classes("hbar"); attr("title", "${bar.value} of $total") }) {
            Span(attrs = { classes("k") }) { Text(bar.label) }
            Div(attrs = { classes("rail") }) {
                Div(attrs = { classes("fill"); style { property("width", "${bar.share(most)}%") } }) {}
            }
            Span(attrs = { classes("v") }) { Text("${bar.value}") }
        }
    }
    Div(attrs = { classes("sub") }) { Text("Of $total cards.") }
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
private fun Pie(caption: String, bars: List<Bar>) {
    val total = bars.sumOf { it.value }
    if (total <= 0) return
    var at = 0.0
    val stops = bars.joinToString(", ") { bar ->
        val from = at
        at += (bar.value * 100.0) / total
        val colour = Pip.entries.firstOrNull { it.label == bar.label }?.letter?.lowercase() ?: "c"
        "var(--$colour) ${from}% ${at}%"
    }
    Div(attrs = { classes("pie-set") }) {
        Div(attrs = {
            classes("pie")
            style { property("background", "conic-gradient($stops)") }
            attr(
                "title",
                bars.joinToString(", ") { "${it.label} ${(it.value * 100) / total}%" },
            )
        }) {}
        Span(attrs = { classes("pie-cap") }) { Text("$caption · $total") }
        Div(attrs = { classes("pie-key") }) {
            bars.forEach { bar ->
                val letter = Pip.entries.firstOrNull { it.label == bar.label }?.letter ?: "C"
                Span(attrs = { classes("k") }) {
                    ManaPip(letter, "sm")
                    Text("${(bar.value * 100) / total}%")
                }
            }
        }
    }
}
