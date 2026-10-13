package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
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
import org.mattshoe.mtg.core.Rule
import org.mattshoe.mtg.core.Rules
import org.mattshoe.mtg.core.WheelPair

/**
 * Tips and references, on the web. Sibling of `GuideScreen`.
 *
 * The front page is a grid of tiles; a tile is a page of its own at
 * `#/guide/<slug>`. Everything on it is in `Guide`, so the phone and
 * the site cannot list different names for the same colours.
 */
@Composable
fun GuidePage(
    tile: GuideTile?,
    onOpen: (GuideTile) -> Unit,
    onBack: () -> Unit,
    rules: Rules = Rules(),
    onRules: (Rules) -> Unit = {},
    onLoadRules: () -> Unit = {},
) {
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
                Guide.RULES -> RulesSearch(rules, onRules, onLoadRules)
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

/**
 * The Comprehensive Rules, searched as you type. Sibling of Android's
 * `RulesSearch`.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default". The file is fetched once, through the Worker,
 * and `RuleBook` parses and searches it here, on the device.
 */
@Composable
private fun RulesSearch(rules: Rules, onRules: (Rules) -> Unit, onLoad: () -> Unit) {
    Div(attrs = { classes("rules-bar") }) {
        Input(InputType.Search, attrs = {
            classes("rules-search")
            placeholder("Search the rules: trample, 702.19, commander tax")
            attr("aria-label", "Search the rules")
            value(rules.query)
            onInput { onRules(rules.searched(it.value)) }
        })
    }
    val book = rules.book
    when {
        rules.busy -> Div(attrs = { classes("empty", "rules-loading") }) { Text("Loading the Comprehensive Rules…") }
        rules.error != null -> Div(attrs = { classes("rules-failed") }) {
            Div(attrs = { classes("err") }) { Text("Could not load the rules: ${rules.error}") }
            Button(attrs = {
                classes("btn", "rules-retry")
                onClick { onLoad() }
            }) { Text("Try again") }
        }
        book == null -> Div(attrs = { classes("empty") }) { Text("The rules have not loaded yet.") }
        rules.query.isBlank() -> Div(attrs = { classes("empty", "rules-hint") }) {
            Text("Type a word or a rule number to find every rule that mentions it.")
            if (book.effective.isNotEmpty()) Div(attrs = { classes("muted", "small") }) { Text(book.effective) }
        }
        rules.total == 0 -> Div(attrs = { classes("empty") }) { Text("No rule mentions \"${rules.query.trim()}\".") }
        else -> {
            Div(attrs = { classes("muted", "small", "rules-count") }) { Text(rules.count) }
            Div(attrs = { classes("rules-list") }) {
                rules.hits.forEach { r -> RuleCard(r, rules.isOpen(r)) { onRules(rules.toggled(r)) } }
            }
        }
    }
}

/** One rule: its number as the heading, a tap on that to fold it, then its text and examples. */
@Composable
private fun RuleCard(rule: Rule, open: Boolean, onToggle: () -> Unit) {
    Div(attrs = {
        classes("rule-card")
        attr("data-rule", rule.number)
        attr("data-open", open.toString())
    }) {
        Button(attrs = {
            classes("rule-head")
            attr("aria-expanded", open.toString())
            onClick { onToggle() }
        }) {
            Span(attrs = { classes("rule-number") }) { Text(rule.shownNumber) }
            Span(attrs = { classes("rule-section") }) { Text(rule.section) }
            Span(attrs = { classes("rule-chevron") }) { Text(if (open) "▾" else "▸") }
        }
        if (open) {
            Div(attrs = { classes("rule-body") }) {
                Div(attrs = { classes("rule-text") }) { Text(rule.text) }
                rule.examples.forEach { e -> Div(attrs = { classes("rule-example") }) { Text(e) } }
            }
        }
    }
}

/** The colours left to right, each its own stretch of the strip. */
private fun band(pips: String): String {
    val colours = pips.map { Design.css(Design.pip(it.toString())) }
    if (colours.size == 1) return "linear-gradient(90deg, ${colours[0]}, ${colours[0]})"
    return "linear-gradient(90deg, ${colours.joinToString(", ")})"
}
