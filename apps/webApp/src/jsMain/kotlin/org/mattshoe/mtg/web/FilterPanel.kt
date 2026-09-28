package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.COLOR_LETTERS
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.ColorTarget
import org.mattshoe.mtg.core.Facet
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Tri
import org.w3c.dom.HTMLElement

/**
 * The filter panel: ten groups, each one folds away on its own.
 *
 * Every control here only edits a `Filters`; what any of it means in
 * SQL is `conditions()` in the shared core. The markup and the class
 * names are the hand-written page's, so the accordion, the colour pips
 * and the checkbox grids are the ones the stylesheet already draws.
 */
@Composable
fun FilterPanel(
    f: Filters,
    facets: Facets = Facets(),
    onChange: (Filters) -> Unit,
) {
    // Folded away to start. Ten open groups is a wall, and the whole
    // point of the accordion is that you open the one you want. A
    // group already holding a filter opens itself, so a link that
    // arrives with a search in it shows where the search came from.
    var open by remember { mutableStateOf(Facet.inUse(f)) }
    fun toggle(g: Facet) { open = if (g in open) open - g else open + g }

    Div(attrs = { classes("fgrid") }) {
        Group(Facet.COLLECTION, f, open, ::toggle) { Collection(f, facets, onChange) }
        Group(Facet.COLOUR, f, open, ::toggle) { Colour(f, onChange) }
        Group(Facet.TYPE, f, open, ::toggle) { Types(f, facets, onChange) }
        Group(Facet.MANA, f, open, ::toggle) { Mana(f, onChange) }
        Group(Facet.TEXT, f, open, ::toggle) { Words(f, onChange) }
        Group(Facet.TAGS, f, open, ::toggle) { Tags(f, onChange) }
        Group(Facet.PRINTING, f, open, ::toggle) { Printing(f, facets, onChange) }
        Group(Facet.PHYSICAL, f, open, ::toggle) { Physical(f, facets, onChange) }
        Group(Facet.FLAGS, f, open, ::toggle) { Flags(f, onChange) }
        Group(Facet.LEGALITY, f, open, ::toggle) { Legality(f, facets, onChange) }
    }

    Div(attrs = { classes("flex-wrap") }) {
        Button(attrs = {
            classes("btn", "ghost")
            onClick { onChange(Filters()) }
        }) { Text("Reset everything") }
    }
}

// ------------------------------------------------------------- the shell

@Composable
private fun Details(attrs: AttrsScope<HTMLElement>.() -> Unit, content: ContentBuilder<HTMLElement>) =
    TagElement("details", attrs, content)

@Composable
private fun Summary(attrs: AttrsScope<HTMLElement>.() -> Unit, content: ContentBuilder<HTMLElement>) =
    TagElement("summary", attrs, content)

/**
 * One foldable group, with a count of what is set inside it.
 *
 * The `open` attribute is driven from Compose rather than left to the
 * browser: a native toggle would be undone by the next recomposition,
 * which happens on every keystroke in any field.
 */
@Composable
private fun Group(
    facet: Facet,
    f: Filters,
    open: Set<Facet>,
    toggle: (Facet) -> Unit,
    body: @Composable () -> Unit,
) {
    val isOpen = facet in open
    val n = facet.countIn(f)
    Details(attrs = {
        classes("fgroup")
        if (facet == Facet.COLOUR) classes("fgroup-color")
        if (isOpen) attr("open", "")
        attr("data-facet", facet.id)
    }) {
        Summary(attrs = {
            // The browser would toggle this itself and then disagree
            // with the state we are holding, so it is ours alone.
            onClick { it.preventDefault(); toggle(facet) }
        }) {
            Span(attrs = { classes("fg-title") }) { Text(facet.title) }
            if (n > 0) Span(attrs = { classes("count-pill") }) { Text("$n") }
        }
        if (isOpen) Div(attrs = { classes("fgroup-body") }) { body() }
    }
}

// ------------------------------------------------------------ the groups

@Composable
private fun Collection(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Row("Whose") {
        Seg(
            listOf("matt" to "Matt", "kayla" to "Kayla", "both" to "Both"),
            f.owner,
        ) { onChange(f.copy(owner = it)) }
    }
    Row("Pool") {
        Seg(
            listOf("all" to "All", "free" to "Unassigned", "committed" to "In decks"),
            f.pool.slug,
        ) { slug -> onChange(f.copy(pool = Pool.entries.first { it.slug == slug })) }
    }
    Row("Copies owned") {
        Range(f.qtyMin, f.qtyMax, { onChange(f.copy(qtyMin = it)) }, { onChange(f.copy(qtyMax = it)) })
    }
    Row("Free copies, at least") {
        Num(f.freeMin, "any") { onChange(f.copy(freeMin = it)) }
    }
    Row("In a deck") {
        Dropdown(
            listOf("" to "any", "_any" to "— in any deck —", "_none" to "— in no deck —") +
                facets.decks.map { it.slug to it.label },
            f.deck,
        ) { onChange(f.copy(deck = it)) }
    }
    Row("EDHREC rank") {
        Range(f.edhrecMin, f.edhrecMax, { onChange(f.copy(edhrecMin = it)) }, { onChange(f.copy(edhrecMax = it)) })
    }
    Row("Price, USD") {
        Range(f.priceMin, f.priceMax, { onChange(f.copy(priceMin = it)) }, { onChange(f.copy(priceMax = it)) })
    }
}

@Composable
private fun Colour(f: Filters, onChange: (Filters) -> Unit) {
    Row("Match against") {
        Seg(
            ColorTarget.entries.map {
                it.slug to if (it == ColorTarget.IDENTITY) "Colour identity" else "Printed colour"
            },
            f.colorTarget.slug,
        ) { slug -> onChange(f.copy(colorTarget = ColorTarget.entries.first { it.slug == slug })) }
    }
    Row("How") {
        Div(attrs = { classes("mode-grid") }) {
            ColorMode.entries.forEach { m ->
                Button(attrs = {
                    classes("chip")
                    if (f.colorMode == m) classes("on")
                    attr("title", m.explains)
                    onClick { onChange(f.copy(colorMode = m)) }
                }) { Text(m.label) }
            }
        }
        Div(attrs = { classes("hint") }) { Text(f.colorMode.explains) }
    }
    Row("Colours") {
        Pips(f.colors) { c -> onChange(f.copy(colors = f.colors.toggle(c))) }
        Div(attrs = { classes("chips") }) {
            Button(attrs = {
                classes("chip", "mini")
                onClick { onChange(f.copy(colors = emptyList())) }
            }) { Text("clear") }
            Button(attrs = {
                classes("chip", "mini")
                onClick { onChange(f.copy(colors = COLOR_LETTERS)) }
            }) { Text("all five") }
            Button(attrs = {
                classes("chip", "mini")
                onClick { onChange(f.copy(colors = listOf("C"), colorMode = ColorMode.EXACTLY)) }
            }) { Text("colourless") }
        }
    }
    Row("Number of colours") {
        Range(f.ciMin, f.ciMax, { onChange(f.copy(ciMin = it)) }, { onChange(f.copy(ciMax = it)) })
    }
    Row("Produces mana") {
        Pips(f.produces) { c -> onChange(f.copy(produces = f.produces.toggle(c))) }
    }
}

@Composable
private fun Types(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Row(null) { Checks(facets.types, f.types) { onChange(f.copy(types = it)) } }
    Row("Supertype") {
        Checks(Facets.SUPERTYPES, f.supertypes) { onChange(f.copy(supertypes = it)) }
    }
    Row("Subtypes") { Tokens(f.subtypes, "Elf, Equipment…") { onChange(f.copy(subtypes = it)) } }
    Row("Exclude type") { Checks(facets.types, f.typesNot) { onChange(f.copy(typesNot = it)) } }
    Row("Type line contains") {
        TextBox(f.typeLine, "Artifact Creature") { onChange(f.copy(typeLine = it)) }
    }
}

@Composable
private fun Mana(f: Filters, onChange: (Filters) -> Unit) {
    Row("Mana value") {
        Range(f.cmcMin, f.cmcMax, { onChange(f.copy(cmcMin = it)) }, { onChange(f.copy(cmcMax = it)) })
    }
    Row("Mana cost contains") { TextBox(f.manaCost, "{G}{G}") { onChange(f.copy(manaCost = it)) } }
    Row("Power") { Stat(f.powOp, f.pow, { onChange(f.copy(powOp = it)) }, { onChange(f.copy(pow = it)) }) }
    Row("Toughness") { Stat(f.touOp, f.tou, { onChange(f.copy(touOp = it)) }, { onChange(f.copy(tou = it)) }) }
    Row("Loyalty") { Stat(f.loyOp, f.loy, { onChange(f.copy(loyOp = it)) }, { onChange(f.copy(loy = it)) }) }
}

@Composable
private fun Words(f: Filters, onChange: (Filters) -> Unit) {
    Row("Name contains") { TextBox(f.q, "sol ring") { onChange(f.copy(q = it)) } }
    Row("Rules text") {
        TextBox(f.text, "draw a card") { onChange(f.copy(text = it)) }
        Div(attrs = { classes("hint") }) { Text("full-text, stemmed") }
    }
    Row("Exact text") {
        TextBox(f.textLike, "enters tapped") { onChange(f.copy(textLike = it)) }
        Div(attrs = { classes("hint") }) { Text("literal substring") }
    }
    Row("Flavour text") { TextBox(f.flavor, "") { onChange(f.copy(flavor = it)) } }
    Row("Artist") { TextBox(f.artist, "Rebecca Guay") { onChange(f.copy(artist = it)) } }
    Row("Watermark") { TextBox(f.watermark, "") { onChange(f.copy(watermark = it)) } }
}

@Composable
private fun Tags(f: Filters, onChange: (Filters) -> Unit) {
    Row("Keywords") { Tokens(f.keywords, "Flying, Ward…") { onChange(f.copy(keywords = it)) } }
    Row("Scryfall tags") { Tokens(f.tags, "mana-rock, spot-removal…") { onChange(f.copy(tags = it)) } }
    Div(attrs = { classes("hint") }) { Text("every one listed must match") }
}

@Composable
private fun Printing(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Row("Rarity") { Checks(Facets.RARITIES, f.rarities) { onChange(f.copy(rarities = it)) } }
    Row("Finish") { Seg(Facets.FINISHES, f.finish) { onChange(f.copy(finish = it)) } }
    Row("Sets") { Tokens(f.sets, "MH3") { onChange(f.copy(sets = it)) } }
    Row("Set type") { Checks(facets.setTypes, f.setTypes) { onChange(f.copy(setTypes = it)) } }
    Row("Release year") {
        Range(f.yearMin, f.yearMax, { onChange(f.copy(yearMin = it)) }, { onChange(f.copy(yearMax = it)) })
    }
    Row("Collector number") { TextBox(f.collnum, "117") { onChange(f.copy(collnum = it)) } }
}

@Composable
private fun Physical(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Row("Layout") { Checks(facets.layouts, f.layouts) { onChange(f.copy(layouts = it)) } }
    Row("Frame") { Checks(facets.frames, f.frames, cols = 3) { onChange(f.copy(frames = it)) } }
    Row("Border") { Checks(facets.borders, f.borders) { onChange(f.copy(borders = it)) } }
    Row("Available in") {
        Checks(Facets.GAMES, f.games, cols = 3) { onChange(f.copy(games = it)) }
    }
}

@Composable
private fun Flags(f: Filters, onChange: (Filters) -> Unit) {
    Div(attrs = { classes("tri-list") }) {
        Flag.entries.forEach { flag ->
            TriRow(flag.label, f.flags[flag] ?: Tri.ANY) {
                onChange(f.copy(flags = f.flags + (flag to it)))
            }
        }
    }
}

@Composable
private fun Legality(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Row("Format") {
        Dropdown(
            listOf("" to "any format") + facets.formats.map { it to it },
            f.format,
        ) { onChange(f.copy(format = it)) }
    }
    Row("Status") {
        Dropdown(
            Facets.LEGALITIES.map { it to it },
            f.legality,
            enabled = f.format.isNotBlank(),
        ) { onChange(f.copy(legality = it)) }
    }
    Div(attrs = { classes("tri-list") }) {
        TriRow("Has rulings", f.hasRulings) { onChange(f.copy(hasRulings = it)) }
    }
}

// ------------------------------------------------------------- controls

private fun List<String>.toggle(v: String) = if (v in this) this - v else this + v

@Composable
private fun Row(label: String?, content: @Composable () -> Unit) {
    Div(attrs = { classes("frow") }) {
        label?.let { Label { Text(it) } }
        content()
    }
}

/** The colour toggles. `data-c` is what paints each one its own colour. */
@Composable
private fun Pips(selected: List<String>, onToggle: (String) -> Unit) {
    Div(attrs = { classes("pips") }) {
        (COLOR_LETTERS + "C").forEach { c ->
            Button(attrs = {
                classes("pip")
                attr("data-c", c)
                if (c in selected) classes("on")
                attr("title", NAMES[c] ?: c)
                onClick { onToggle(c) }
            }) { Text(c) }
        }
    }
}

private val NAMES = mapOf(
    "W" to "White", "U" to "Blue", "B" to "Black",
    "R" to "Red", "G" to "Green", "C" to "Colourless",
)

@Composable
private fun Seg(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Div(attrs = { classes("seg") }) {
        options.forEach { (value, label) ->
            Button(attrs = {
                if (value == selected) classes("on")
                onClick { onPick(value) }
            }) { Text(label) }
        }
    }
}

@Composable
private fun TextBox(value: String, hint: String, onInput: (String) -> Unit) {
    Input(type = InputType.Text) {
        classes("field")
        placeholder(hint)
        value(value)
        onInput { onInput(it.value) }
    }
}

@Composable
private fun Num(value: String, hint: String, onInput: (String) -> Unit) {
    Input(type = InputType.Number) {
        classes("field")
        placeholder(hint)
        attr("min", "0")
        value(value)
        onInput { onInput(it.value?.toString().orEmpty()) }
    }
}

@Composable
private fun Range(min: String, max: String, onMin: (String) -> Unit, onMax: (String) -> Unit) {
    Div(attrs = { classes("row") }) {
        Input(type = InputType.Number) {
            classes("field")
            placeholder("min")
            value(min)
            onInput { onMin(it.value?.toString().orEmpty()) }
        }
        Input(type = InputType.Number) {
            classes("field")
            placeholder("max")
            value(max)
            onInput { onMax(it.value?.toString().orEmpty()) }
        }
    }
}

@Composable
private fun Stat(op: String, value: String, onOp: (String) -> Unit, onValue: (String) -> Unit) {
    Div(attrs = { classes("row") }) {
        Dropdown(listOf(">=" to "≥", "<=" to "≤", "=" to "=", ">" to ">", "<" to "<"), op, onPick = onOp)
        Input(type = InputType.Text) {
            classes("field")
            placeholder("any")
            value(value)
            onInput { onValue(it.value) }
        }
    }
}

@Composable
private fun Dropdown(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    Select(attrs = {
        classes("field")
        if (!enabled) attr("disabled", "")
        onChange { onPick(it.value ?: "") }
    }) {
        options.forEach { (value, label) ->
            Option(value, attrs = { if (value == selected) attr("selected", "") }) { Text(label) }
        }
    }
}

/** Real checkboxes, because a checkbox should look like a checkbox. */
@Composable
private fun Checks(
    values: List<String>,
    selected: List<String>,
    cols: Int = 2,
    onChange: (List<String>) -> Unit,
) {
    if (values.isEmpty()) {
        Div(attrs = { classes("hint") }) { Text("loading…") }
        return
    }
    Div(attrs = {
        classes("checks")
        style { property("--cols", cols.toString()) }
    }) {
        values.forEach { v ->
            Label(attrs = { classes("check") }) {
                Input(type = InputType.Checkbox) {
                    checked(v in selected)
                    onChange { onChange(selected.toggle(v)) }
                }
                Span { Text(v) }
            }
        }
    }
}

/**
 * A free list, typed one at a time and shown as removable chips. A set
 * picker with six hundred tags is worse than typing two of them.
 */
@Composable
private fun Tokens(values: List<String>, hint: String, onChange: (List<String>) -> Unit) {
    var typed by remember { mutableStateOf("") }
    Div {
        Input(type = InputType.Text) {
            classes("field")
            placeholder(hint)
            value(typed)
            onInput { typed = it.value }
            onKeyDown { e ->
                if (e.key == "Enter") {
                    e.preventDefault()
                    val v = typed.trim()
                    if (v.isNotEmpty() && v !in values) onChange(values + v)
                    typed = ""
                }
            }
        }
        if (values.isEmpty()) {
            Div(attrs = { classes("hint") }) { Text("type and press enter") }
        } else {
            Div(attrs = { classes("chips") }) {
                values.forEach { v ->
                    Button(attrs = {
                        classes("chip", "mini")
                        attr("title", "Remove")
                        onClick { onChange(values - v) }
                    }) { Text("$v ×") }
                }
            }
        }
    }
}

@Composable
private fun TriRow(label: String, value: Tri, onPick: (Tri) -> Unit) {
    Div(attrs = { classes("tri") }) {
        Span(attrs = { classes("tri-label") }) { Text(label) }
        Div(attrs = { classes("seg", "seg-sm") }) {
            Tri.entries.forEach { t ->
                Button(attrs = {
                    if (t == value) classes("on")
                    onClick { onPick(t) }
                }) { Text(if (t == Tri.ANY) "any" else if (t == Tri.YES) "yes" else "no") }
            }
        }
    }
}
