package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile

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

/** The colours left to right, each its own stretch of the strip. */
private fun band(pips: String): String {
    val colours = pips.map { Design.css(Design.pip(it.toString())) }
    if (colours.size == 1) return "linear-gradient(90deg, ${colours[0]}, ${colours[0]})"
    return "linear-gradient(90deg, ${colours.joinToString(", ")})"
}
