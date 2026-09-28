package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.COLOR_LETTERS
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.ColorTarget
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Tri

/**
 * The filter panel, on the web.
 *
 * Every control here only edits a `Filters`; what any of it means in SQL
 * is `conditions()` in the shared core. Sibling of `FilterSheet` on
 * Android, and the two agree because neither decides anything.
 */
@Composable
fun FilterPanel(f: Filters, onChange: (Filters) -> Unit) {
    Div(attrs = { classes("panel", "filters") }) {
        Div(attrs = { classes("panel-body") }) {

            Section("Words") {
                TextField("Name", f.q) { onChange(f.copy(q = it)) }
                TextField("Oracle text", f.text) { onChange(f.copy(text = it)) }
                TextField("Text contains", f.textLike) { onChange(f.copy(textLike = it)) }
                TextField("Flavour", f.flavor) { onChange(f.copy(flavor = it)) }
                TextField("Artist", f.artist) { onChange(f.copy(artist = it)) }
                TextField("Watermark", f.watermark) { onChange(f.copy(watermark = it)) }
                TextField("Type line", f.typeLine) { onChange(f.copy(typeLine = it)) }
                TextField("Mana cost", f.manaCost) { onChange(f.copy(manaCost = it)) }
            }

            Section("Colour") {
                Div(attrs = { classes("flex-wrap") }) {
                    ColorTarget.entries.forEach { t ->
                        Chip(if (t == ColorTarget.IDENTITY) "Identity" else "Printed", f.colorTarget == t) {
                            onChange(f.copy(colorTarget = t))
                        }
                    }
                }
                Div(attrs = { classes("flex-wrap") }) {
                    // Four modes, and the difference between them is the
                    // whole point of the panel for Commander.
                    ColorMode.entries.forEach { m ->
                        Chip(m.label, f.colorMode == m) { onChange(f.copy(colorMode = m)) }
                    }
                }
                Div(attrs = { classes("flex-wrap") }) {
                    (COLOR_LETTERS + "C").forEach { c ->
                        Chip(c, c in f.colors) {
                            onChange(f.copy(colors = f.colors.toggle(c)))
                        }
                    }
                }
                Div(attrs = { classes("muted", "small") }) {
                    Text(f.colorMode.explains)
                }
                Range("Colours in identity", f.ciMin, f.ciMax,
                    { onChange(f.copy(ciMin = it)) }, { onChange(f.copy(ciMax = it)) })
            }

            Section("Numbers") {
                Range("Mana value", f.cmcMin, f.cmcMax,
                    { onChange(f.copy(cmcMin = it)) }, { onChange(f.copy(cmcMax = it)) })
                Range("Quantity", f.qtyMin, f.qtyMax,
                    { onChange(f.copy(qtyMin = it)) }, { onChange(f.copy(qtyMax = it)) })
                Range("Price", f.priceMin, f.priceMax,
                    { onChange(f.copy(priceMin = it)) }, { onChange(f.copy(priceMax = it)) })
                Range("Year", f.yearMin, f.yearMax,
                    { onChange(f.copy(yearMin = it)) }, { onChange(f.copy(yearMax = it)) })
                Range("EDHREC rank", f.edhrecMin, f.edhrecMax,
                    { onChange(f.copy(edhrecMin = it)) }, { onChange(f.copy(edhrecMax = it)) })
                Compare("Power", f.powOp, f.pow,
                    { onChange(f.copy(powOp = it)) }, { onChange(f.copy(pow = it)) })
                Compare("Toughness", f.touOp, f.tou,
                    { onChange(f.copy(touOp = it)) }, { onChange(f.copy(tou = it)) })
                Compare("Loyalty", f.loyOp, f.loy,
                    { onChange(f.copy(loyOp = it)) }, { onChange(f.copy(loy = it)) })
            }

            Section("Pool") {
                Div(attrs = { classes("flex-wrap") }) {
                    Pool.entries.forEach { p ->
                        Chip(p.slug.replaceFirstChar(Char::uppercase), f.pool == p) {
                            onChange(f.copy(pool = p))
                        }
                    }
                }
                TextField("At least this many free", f.freeMin) { onChange(f.copy(freeMin = it)) }
                TextField("Deck slug", f.deck) { onChange(f.copy(deck = it)) }
                TextField("Finish", f.finish) { onChange(f.copy(finish = it)) }
            }

            Section("Printing") {
                CommaList("Rarities", f.rarities) { onChange(f.copy(rarities = it)) }
                CommaList("Sets", f.sets) { onChange(f.copy(sets = it)) }
                CommaList("Types", f.types) { onChange(f.copy(types = it)) }
                CommaList("Not types", f.typesNot) { onChange(f.copy(typesNot = it)) }
                CommaList("Supertypes", f.supertypes) { onChange(f.copy(supertypes = it)) }
                CommaList("Subtypes", f.subtypes) { onChange(f.copy(subtypes = it)) }
                CommaList("Keywords", f.keywords) { onChange(f.copy(keywords = it)) }
                CommaList("Tags", f.tags) { onChange(f.copy(tags = it)) }
                TextField("Collector number", f.collnum) { onChange(f.copy(collnum = it)) }
            }

            Section("Flags") {
                Flag.entries.forEach { flag ->
                    val t = f.flags[flag] ?: Tri.ANY
                    Div(attrs = { classes("flex-wrap", "small") }) {
                        Span { Text(flag.label) }
                        Span(attrs = { classes("spacer") }) {}
                        Tri.entries.forEach { v ->
                            Chip(v.name.lowercase(), t == v) {
                                onChange(f.copy(flags = f.flags + (flag to v)))
                            }
                        }
                    }
                }
            }

            Section("Legality") {
                TextField("Format", f.format) { onChange(f.copy(format = it)) }
                TextField("Status", f.legality) { onChange(f.copy(legality = it)) }
                Div(attrs = { classes("flex-wrap", "small") }) {
                    Span { Text("Has rulings") }
                    Tri.entries.forEach { v ->
                        Chip(v.name.lowercase(), f.hasRulings == v) { onChange(f.copy(hasRulings = v)) }
                    }
                }
            }

            Div(attrs = { classes("flex-wrap") }) {
                Button(attrs = {
                    classes("btn", "ghost")
                    onClick { onChange(Filters()) }
                }) { Text("Reset everything") }
            }
        }
    }
}

private fun List<String>.toggle(v: String) = if (v in this) this - v else this + v

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Div(attrs = { classes("facet") }) {
        H3(attrs = { classes("facet-head") }) { Text(title) }
        Div(attrs = { classes("facet-body") }) { body() }
    }
}

@Composable
private fun TextField(label: String, value: String, onInput: (String) -> Unit) {
    Div(attrs = { classes("field") }) {
        Span(attrs = { classes("muted", "small") }) { Text(label) }
        Input(type = InputType.Text) {
            classes("field")
            placeholder(label)
            value(value)
            onInput { onInput(it.value) }
        }
    }
}

@Composable
private fun Range(label: String, min: String, max: String, onMin: (String) -> Unit, onMax: (String) -> Unit) {
    Div(attrs = { classes("field", "flex-wrap") }) {
        Span(attrs = { classes("muted", "small") }) { Text(label) }
        Input(type = InputType.Text) { classes("mini"); placeholder("min"); value(min); onInput { onMin(it.value) } }
        Input(type = InputType.Text) { classes("mini"); placeholder("max"); value(max); onInput { onMax(it.value) } }
    }
}

@Composable
private fun Compare(label: String, op: String, value: String, onOp: (String) -> Unit, onValue: (String) -> Unit) {
    Div(attrs = { classes("field", "flex-wrap") }) {
        Span(attrs = { classes("muted", "small") }) { Text(label) }
        listOf(">=", "<=", "=").forEach { o -> Chip(o, op == o) { onOp(o) } }
        Input(type = InputType.Text) { classes("mini"); value(value); onInput { onValue(it.value) } }
    }
}

/**
 * A comma separated list, because a set picker with six hundred tags is
 * a worse experience than typing two of them.
 */
@Composable
private fun CommaList(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    TextField(label, values.joinToString(", ")) { raw ->
        onChange(raw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
}

@Composable
private fun Chip(label: String, on: Boolean, click: () -> Unit) {
    Button(attrs = {
        classes("btn", "sm", "ghost")
        if (on) classes("on")
        onClick { click() }
    }) { Text(label) }
}
