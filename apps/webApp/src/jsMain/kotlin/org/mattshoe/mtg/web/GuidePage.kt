package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.ColourWheel
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile
import org.mattshoe.mtg.core.WheelPair

/**
 * Tips and references, on the web. Sibling of `GuideScreen`.
 *
 * The front page is a grid of tiles; a tile is a page of its own at
 * `#/guide/<slug>`. Everything on it is in `Guide`, so the phone and
 * the site cannot list different names for the same colours.
 */
@Composable
fun GuidePage(tile: GuideTile?, onOpen: (GuideTile) -> Unit, onBack: () -> Unit) {
    Div(attrs = { classes("wrap", "guide") }) {
        if (tile == null) {
            Div(attrs = { classes("guide-tiles") }) {
                Guide.tiles.forEach { t ->
                    Button(attrs = {
                        classes("guide-tile")
                        onClick { onOpen(t) }
                    }) {
                        // The five colours, as a strip of light across
                        // the top: decoration, so the words carry it.
                        Div(attrs = {
                            classes("guide-band")
                            style { property("background-image", band("WUBRG")) }
                        }) {}
                        Div(attrs = { classes("guide-tile-title") }) { Text(t.title) }
                        Div(attrs = { classes("guide-tile-blurb") }) { Text(t.blurb) }
                    }
                }
            }
        } else {
            Button(attrs = {
                classes("btn", "guide-back")
                onClick { onBack() }
            }) { Text("‹ Guide") }
            when (tile.slug) {
                Guide.COLOURS -> Colours()
                Guide.WHEEL -> Wheel()
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
    Div(attrs = { classes("combo-list") }) {
        Guide.list.forEach { ComboRow(it) }
    }
    Guide.colours.forEach { group ->
        Section(attrs = { classes("guide-group") }) {
            H2(attrs = { classes("guide-group-title") }) { Text(group.title) }
            Div(attrs = { classes("guide-group-about") }) { Text(group.about) }
            group.combos.forEach { combo ->
                Div(attrs = { classes("guide-group-line") }) {
                    Span(attrs = { classes("combo-letters") }) { Text(combo.pips) }
                    Text(combo.name)
                    if (combo.note.isNotEmpty()) {
                        Span(attrs = { classes("combo-note") }) { Text(combo.note) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboRow(combo: Combo) {
    Div(attrs = {
        classes("combo-row")
        attr("data-pips", combo.pips)
    }) {
        Div(attrs = { classes("combo-pips") }) {
            combo.pips.forEach { ManaPip(it.toString(), "combo-pip") }
        }
        Span(attrs = { classes("combo-name") }) { Text(combo.name) }
        Span(attrs = { classes("combo-letters") }) { Text(combo.pips) }
    }
}

/**
 * The colour wheel: what it is, the wheel drawn, then each colour and
 * each pair. Everything said is in `ColourWheel`.
 */
@Composable
private fun Wheel() {
    Div(attrs = { classes("wheel-intro") }) {
        ColourWheel.intro.forEach { P { Text(it) } }
    }
    Div(attrs = { classes("wheel") }) {
        // Allies joined by a solid line round the outside, enemies by a
        // dashed one across the middle: told apart by the dash, not by
        // a hue, because Matt is colourblind.
        Div(attrs = {
            classes("wheel-lines")
            ref { it.innerHTML = lines(); onDispose { } }
        }) {}
        ColourWheel.colours.forEach { c ->
            val (x, y) = ColourWheel.point(c.pip)
            Div(attrs = {
                classes("wheel-spoke")
                attr("data-pip", c.pip)
                style {
                    property("left", "${50 + x * RADIUS}%")
                    property("top", "${50 + y * RADIUS}%")
                }
            }) {
                ManaPip(c.pip)
                Span(attrs = { classes("wheel-spoke-name") }) { Text(c.name) }
            }
        }
    }
    Div(attrs = { classes("wheel-key") }) {
        Span(attrs = { classes("wheel-key-ally") }) { Text("Solid: allies, side by side") }
        Span(attrs = { classes("wheel-key-enemy") }) { Text("Dashed: enemies, across") }
    }

    Section(attrs = { classes("guide-group") }) {
        H2(attrs = { classes("guide-group-title") }) { Text("The five colours") }
        Div(attrs = { classes("guide-group-about") }) { Text("Clockwise from the top.") }
        Div(attrs = { classes("wheel-colours") }) {
            ColourWheel.colours.forEach { c ->
                Div(attrs = {
                    classes("wheel-colour")
                    attr("data-pip", c.pip)
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band(c.pip)) }
                    }) {}
                    Div(attrs = { classes("wheel-colour-head") }) {
                        ManaPip(c.pip)
                        Span(attrs = { classes("wheel-colour-name") }) { Text(c.name) }
                    }
                    Said("Wants", c.wants, "wheel-wants")
                    Said("Good at", c.goodAt, "wheel-good")
                    Said("Bad at", c.badAt, "wheel-bad")
                    Said("Allies", ColourWheel.alliesOf(c.pip), "wheel-allies")
                    Said("Enemies", ColourWheel.enemiesOf(c.pip), "wheel-enemies")
                }
            }
        }
    }
    Pairs("Allied pairs", "Side by side on the wheel. They want similar things.", ColourWheel.allied)
    Pairs("Enemy pairs", "Across the wheel. They clash, and a deck of both gets the best of each.", ColourWheel.enemy)
}

@Composable
private fun Said(label: String, words: String, cls: String) {
    Div(attrs = { classes("wheel-said") }) {
        Span(attrs = { classes("wheel-said-label") }) { Text(label) }
        Span(attrs = { classes(cls) }) { Text(words) }
    }
}

@Composable
private fun Pairs(title: String, about: String, pairs: List<WheelPair>) {
    Section(attrs = { classes("guide-group") }) {
        H2(attrs = { classes("guide-group-title") }) { Text(title) }
        Div(attrs = { classes("guide-group-about") }) { Text(about) }
        Div(attrs = { classes("wheel-pairs") }) {
            pairs.forEach { p ->
                Div(attrs = {
                    classes("wheel-pair")
                    attr("data-pips", p.pips)
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band(p.pips)) }
                    }) {}
                    Div(attrs = { classes("combo-pips") }) {
                        p.pips.forEach { ManaPip(it.toString(), "combo-pip") }
                    }
                    Div(attrs = { classes("wheel-pair-name") }) { Text(p.name) }
                    Div(attrs = { classes("combo-note") }) { Text(p.why) }
                }
            }
        }
    }
}

/** How far out the symbols sit, as a percentage of the wheel's width. */
private const val RADIUS = 38.0

/** The ten lines between the five colours, as SVG on a 100 by 100 box. */
private fun lines(): String {
    val at = ColourWheel.colours.map { c -> ColourWheel.point(c.pip).let { (x, y) -> (50 + x * RADIUS) to (50 + y * RADIUS) } }
    fun line(a: Int, b: Int, cls: String) =
        "<line class=\"$cls\" x1=\"${at[a].first}\" y1=\"${at[a].second}\" x2=\"${at[b].first}\" y2=\"${at[b].second}\"/>"
    val allies = (0 until 5).joinToString("") { line(it, (it + 1) % 5, "wheel-ally") }
    val enemies = (0 until 5).joinToString("") { line(it, (it + 2) % 5, "wheel-enemy") }
    return "<svg viewBox=\"0 0 100 100\">$allies$enemies</svg>"
}

/** The colours left to right, each its own stretch of the strip. */
private fun band(pips: String): String {
    val colours = pips.map { Design.css(Design.pip(it.toString())) }
    if (colours.size == 1) return "linear-gradient(90deg, ${colours[0]}, ${colours[0]})"
    return "linear-gradient(90deg, ${colours.joinToString(", ")})"
}
