package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile
import org.mattshoe.mtg.core.Rule
import org.mattshoe.mtg.core.Rules

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
                Guide.RULES -> RulesSearch(rules, onRules, onLoadRules)
            }
        }
    }
}

@Composable
private fun Colours() {
    Guide.colours.forEach { group ->
        Section(attrs = { classes("guide-group") }) {
            H2(attrs = { classes("guide-group-title") }) { Text(group.title) }
            Div(attrs = { classes("guide-group-about") }) { Text(group.about) }
            Div(attrs = { classes("combo-grid") }) {
                group.combos.forEach { ComboTile(it) }
            }
        }
    }
}

@Composable
private fun ComboTile(combo: Combo) {
    Div(attrs = {
        classes("combo")
        attr("data-pips", combo.pips)
    }) {
        Div(attrs = {
            classes("guide-band")
            style { property("background-image", band(combo.pips)) }
        }) {}
        Div(attrs = { classes("combo-pips") }) {
            combo.pips.forEach { ManaPip(it.toString(), "combo-pip") }
        }
        Div(attrs = { classes("combo-name") }) { Text(combo.name) }
        if (combo.note.isNotEmpty()) {
            Div(attrs = { classes("combo-note") }) { Text(combo.note) }
        }
        Span(attrs = { classes("combo-letters") }) { Text(combo.pips) }
    }
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
