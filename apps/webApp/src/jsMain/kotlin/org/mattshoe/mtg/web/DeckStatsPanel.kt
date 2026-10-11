package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
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
import org.mattshoe.mtg.core.DeckStats
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
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
fun DeckStatsPanel(s: DeckStats, guild: String? = null) {
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

        Div(attrs = { classes("figures") }) {
            figure("${s.totalCards}", "cards")
            figure("${s.lands}", "lands · ${s.landShare}%")
            figure(s.averageManaValueText, "avg mana")
            figure("${s.spells}", "spells")
            s.value?.let { figure(Prices.money(it), "value", hint = if (s.unpriced > 0) "${s.unpriced} cards have no price" else "") }
            if (s.missing > 0) figure("${s.missing}", "not owned", warn = true)
        }

        Div(attrs = { classes("stats-grid") }) {
            if (s.hasCurve) card("Mana curve") { Curve(s) }
            if (s.pips.isNotEmpty() || s.sources.isNotEmpty()) card("Colour") { Colours(s) }
            if (s.types.isNotEmpty()) card("Card types") { Bars(s.types, s.totalCards) }
            // A Human Warrior is a bar each for Human and Warrior.
            if (s.creatureTypes.isNotEmpty()) {
                card("Creature types") { Bars(s.creatureTypes, s.creatures, s.creatureTypesNote) }
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
    Div(attrs = { classes("sub") }) { Text("Median ${s.medianManaValueText}. Lands excluded.") }
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
private fun Bars(bars: List<Bar>, total: Int, note: String = "Of $total cards.") {
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
