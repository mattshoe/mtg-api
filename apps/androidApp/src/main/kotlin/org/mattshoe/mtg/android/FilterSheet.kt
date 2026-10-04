package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.COLOR_LETTERS
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.ColorTarget
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Facet
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Tri

/**
 * The filter panel on Android: ten groups, each one folds away on its
 * own. Sibling of `FilterPanel` on the web, group for group and row for
 * row.
 *
 * It was a flat wall of every box at once, with half the columns the
 * website filters on missing — no produces-mana, no set type, no
 * layout, frame or border, no games, and the facet lists the web offers
 * as tappable checkboxes typed as comma-separated text. The accordion,
 * which groups are open and the count on each header all come from
 * `Facet` in the shared core, so the phone and the browser cannot fold
 * different things away or disagree about the badge.
 *
 * Only edits a `Filters`; what any of it means in SQL is the shared
 * core's business, which is why the two platforms cannot disagree about
 * what "at most these colours" returns.
 */
@Composable
fun FilterSheet(
    f: Filters,
    facets: Facets = Facets(),
    onChange: (Filters) -> Unit,
) {
    // Folded away to start. Ten open groups is a wall, and the whole
    // point of the accordion is that you open the one you want.
    //
    // Derived rather than held, exactly as the web derives it: a group
    // holding a filter opens itself unless it has been closed by hand.
    var touched by remember { mutableStateOf(mapOf<Facet, Boolean>()) }

    // Groups that have been open and have not been closed by hand.
    // Open was derived from "does this group hold a filter", which
    // means clearing the last filter in a group folded it away
    // underneath you — cursor still in the box, keyboard still up.
    val stuck = remember { mutableSetOf<Facet>() }
    val open = Facet.entries.filter { touched[it] ?: (it in stuck || it.countIn(f) > 0) }.toSet()
    stuck += open

    fun toggle(g: Facet) {
        touched = touched + (g to (g !in open))
    }

    // Half-typed token text, held by the panel rather than by the field
    // it belongs to: a `remember` inside the field leaves the
    // composition when its group folds and takes the text with it.
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    fun draft(key: String) = drafts[key].orEmpty()
    fun setDraft(key: String, v: String) { drafts = drafts + (key to v) }

    Column(
        Modifier.fillMaxWidth().testTag("filters"),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Group(Facet.COLLECTION, f, open, ::toggle) { CollectionGroup(f, facets, onChange) }
        Group(Facet.COLOUR, f, open, ::toggle) { ColourGroup(f, onChange) }
        Group(Facet.TYPE, f, open, ::toggle) { TypeGroup(f, facets, onChange) }
        Group(Facet.MANA, f, open, ::toggle) { ManaGroup(f, onChange) }
        Group(Facet.TEXT, f, open, ::toggle) { WordsGroup(f, onChange) }
        Group(Facet.TAGS, f, open, ::toggle) { TagsGroup(f, onChange, ::draft, ::setDraft) }
        Group(Facet.PRINTING, f, open, ::toggle) {
            PrintingGroup(f, facets, onChange, ::draft, ::setDraft)
        }
        Group(Facet.PHYSICAL, f, open, ::toggle) { PhysicalGroup(f, facets, onChange) }
        Group(Facet.FLAGS, f, open, ::toggle) { FlagsGroup(f, onChange) }
        Group(Facet.LEGALITY, f, open, ::toggle) { LegalityGroup(f, facets, onChange) }

        // Clears the filters, not the layout. Folding the groups away
        // as well would take the controls out from under somebody who
        // is still working in them. And no Apply button: everything
        // applies as it is typed, the same as the web.
        Btn("Reset everything") { onChange(Filters()) }
    }
}

// ------------------------------------------------------------- the shell

/**
 * One foldable group, with a count of what is set inside it.
 *
 * A closed group does not compose its body, so "is this control on the
 * screen" is the same question on both platforms.
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
    Column(
        Modifier.fillMaxWidth()
            .testTag("facet-${facet.id}")
            .background(Bg2, Radius)
            .border(1.dp, Line, Radius),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .testTag("header-${facet.id}")
                .clickable { toggle(facet) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A caret, so open and shut are told apart by shape rather
            // than by a shade of grey.
            Text(if (isOpen) "▾" else "▸", color = Ink3, fontSize = Design.MINI.sp)
            Text(
                facet.title,
                Modifier.weight(1f),
                color = Ink,
                fontSize = Design.H3.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (n > 0) {
                Text(
                    "$n",
                    Modifier.testTag("count-${facet.id}")
                        .background(AccentDim, Pill)
                        .border(1.dp, Accent, Pill)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    color = Accent2,
                    fontSize = Design.TINY.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (isOpen) {
            Column(
                Modifier.fillMaxWidth()
                    .testTag("body-${facet.id}")
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { body() }
        }
    }
}

// ------------------------------------------------------------ the groups

@Composable
private fun CollectionGroup(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    FRow("Whose") {
        Seg(listOf("matt" to "Matt", "kayla" to "Kayla", "both" to "Both"), f.owner) {
            onChange(f.copy(owner = it))
        }
    }
    FRow("Pool") {
        Seg(
            listOf("all" to "All", "free" to "Unassigned", "committed" to "In decks"),
            f.pool.slug,
        ) { slug -> onChange(f.copy(pool = Pool.entries.first { it.slug == slug })) }
    }
    FRow("Copies owned") {
        Range("qty", f.qtyMin, f.qtyMax, { onChange(f.copy(qtyMin = it)) }, { onChange(f.copy(qtyMax = it)) })
    }
    FRow("Free copies, at least") {
        Num("freeMin", f.freeMin, "any") { onChange(f.copy(freeMin = it)) }
    }
    FRow("In a deck") {
        Dropdown(
            "deck",
            listOf("" to "any", "_any" to "— in any deck —", "_none" to "— in no deck —") +
                facets.decks.map { it.slug to it.label },
            f.deck,
        ) { onChange(f.copy(deck = it)) }
    }
    FRow("EDHREC rank") {
        Range(
            "edhrec", f.edhrecMin, f.edhrecMax,
            { onChange(f.copy(edhrecMin = it)) }, { onChange(f.copy(edhrecMax = it)) },
        )
    }
    FRow("Price, USD") {
        Range(
            "price", f.priceMin, f.priceMax,
            { onChange(f.copy(priceMin = it)) }, { onChange(f.copy(priceMax = it)) },
        )
    }
}

@Composable
private fun ColourGroup(f: Filters, onChange: (Filters) -> Unit) {
    FRow("Match against") {
        Seg(
            ColorTarget.entries.map {
                it.slug to if (it == ColorTarget.IDENTITY) "Colour identity" else "Printed colour"
            },
            f.colorTarget.slug,
        ) { slug -> onChange(f.copy(colorTarget = ColorTarget.entries.first { it.slug == slug })) }
    }
    FRow("How") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ColorMode.entries.forEach { m ->
                Ghost(m.label, on = f.colorMode == m) { onChange(f.copy(colorMode = m)) }
            }
        }
        Hint(f.colorMode.explains)
    }
    FRow("Colours") {
        Pips("colors", f.colors) { c -> onChange(f.copy(colors = f.colors.toggle(c))) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ghost("clear") { onChange(f.copy(colors = emptyList())) }
            Ghost("all five") { onChange(f.copy(colors = COLOR_LETTERS)) }
            Ghost("colourless") {
                onChange(f.copy(colors = listOf("C"), colorMode = ColorMode.EXACTLY))
            }
        }
    }
    FRow("Number of colours") {
        Range("ci", f.ciMin, f.ciMax, { onChange(f.copy(ciMin = it)) }, { onChange(f.copy(ciMax = it)) })
    }
    FRow("Produces mana") {
        Pips("produces", f.produces) { c -> onChange(f.copy(produces = f.produces.toggle(c))) }
    }
}

@Composable
private fun TypeGroup(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    Checks("types", facets.types, f.types) { onChange(f.copy(types = it)) }
    FRow("Type line contains") {
        TextBox("typeLine", f.typeLine, "creature !land") { onChange(f.copy(typeLine = it)) }
        Hint("every word. \"phrase\", !exclude")
    }
}

@Composable
private fun ManaGroup(f: Filters, onChange: (Filters) -> Unit) {
    FRow("Mana value") {
        Range("cmc", f.cmcMin, f.cmcMax, { onChange(f.copy(cmcMin = it)) }, { onChange(f.copy(cmcMax = it)) })
    }
    FRow("Mana cost contains") {
        TextBox("manaCost", f.manaCost, "{G}{G}") { onChange(f.copy(manaCost = it)) }
    }
    FRow("Power") {
        Stat("pow", f.powOp, f.pow, { onChange(f.copy(powOp = it)) }, { onChange(f.copy(pow = it)) })
    }
    FRow("Toughness") {
        Stat("tou", f.touOp, f.tou, { onChange(f.copy(touOp = it)) }, { onChange(f.copy(tou = it)) })
    }
    FRow("Loyalty") {
        Stat("loy", f.loyOp, f.loy, { onChange(f.copy(loyOp = it)) }, { onChange(f.copy(loy = it)) })
    }
}

@Composable
private fun WordsGroup(f: Filters, onChange: (Filters) -> Unit) {
    FRow("Name contains") {
        TextBox("q", f.q, "sol ring") { onChange(f.copy(q = it)) }
        Hint("every word, in either face. \"phrase\", !exclude")
    }
    FRow("Rules text") {
        TextBox("text", f.text, "draw card") { onChange(f.copy(text = it)) }
        Hint("full-text and stemmed. every word must match; \"quote a phrase\"; !exclude")
    }
    FRow("Flavour text") { TextBox("flavor", f.flavor, "") { onChange(f.copy(flavor = it)) } }
    FRow("Artist") { TextBox("artist", f.artist, "Rebecca Guay") { onChange(f.copy(artist = it)) } }
    FRow("Watermark") { TextBox("watermark", f.watermark, "") { onChange(f.copy(watermark = it)) } }
}

@Composable
private fun TagsGroup(
    f: Filters,
    onChange: (Filters) -> Unit,
    draft: (String) -> String,
    setDraft: (String, String) -> Unit,
) {
    FRow("Keywords") {
        Tokens("keywords", f.keywords, "Flying, Ward…", draft("keywords"), { setDraft("keywords", it) }) {
            onChange(f.copy(keywords = it))
        }
    }
    FRow("Scryfall tags") {
        Tokens("tags", f.tags, "mana-rock, spot-removal…", draft("tags"), { setDraft("tags", it) }) {
            onChange(f.copy(tags = it))
        }
    }
    Hint("every one listed must match")
}

@Composable
private fun PrintingGroup(
    f: Filters,
    facets: Facets,
    onChange: (Filters) -> Unit,
    draft: (String) -> String,
    setDraft: (String, String) -> Unit,
) {
    FRow("Rarity") { Checks("rarities", Facets.RARITIES, f.rarities) { onChange(f.copy(rarities = it)) } }
    FRow("Finish") { Seg(Facets.FINISHES, f.finish) { onChange(f.copy(finish = it)) } }
    FRow("Sets") {
        Tokens("sets", f.sets, "MH3", draft("sets"), { setDraft("sets", it) }) { onChange(f.copy(sets = it)) }
    }
    FRow("Set type") { Checks("setTypes", facets.setTypes, f.setTypes) { onChange(f.copy(setTypes = it)) } }
    FRow("Release year") {
        Range("year", f.yearMin, f.yearMax, { onChange(f.copy(yearMin = it)) }, { onChange(f.copy(yearMax = it)) })
    }
    FRow("Collector number") { TextBox("collnum", f.collnum, "117") { onChange(f.copy(collnum = it)) } }
}

@Composable
private fun PhysicalGroup(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    FRow("Layout") { Checks("layouts", facets.layouts, f.layouts) { onChange(f.copy(layouts = it)) } }
    FRow("Frame") { Checks("frames", facets.frames, f.frames, cols = 3) { onChange(f.copy(frames = it)) } }
    FRow("Border") { Checks("borders", facets.borders, f.borders) { onChange(f.copy(borders = it)) } }
    FRow("Available in") {
        Checks("games", Facets.GAMES, f.games, cols = 3) { onChange(f.copy(games = it)) }
    }
}

@Composable
private fun FlagsGroup(f: Filters, onChange: (Filters) -> Unit) {
    Flag.entries.forEach { flag ->
        TriRow(flag.slug, flag.label, f.flags[flag] ?: Tri.ANY) {
            onChange(f.copy(flags = f.flags + (flag to it)))
        }
    }
}

@Composable
private fun LegalityGroup(f: Filters, facets: Facets, onChange: (Filters) -> Unit) {
    FRow("Format") {
        Dropdown(
            "format",
            listOf("" to "any format") + facets.formats.map { it to it },
            f.format,
        ) { onChange(f.copy(format = it)) }
    }
    FRow("Status") {
        Dropdown(
            "legality",
            Facets.LEGALITIES.map { it to it },
            f.legality,
            enabled = f.format.isNotBlank(),
        ) { onChange(f.copy(legality = it)) }
    }
    TriRow("hasRulings", "Has rulings", f.hasRulings) { onChange(f.copy(hasRulings = it)) }
}

// ------------------------------------------------------------- controls

private fun List<String>.toggle(v: String) = if (v in this) this - v else this + v

/** `.frow`: a label and whatever it labels, stacked. */
@Composable
private fun FRow(label: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        label?.let { Text(it, color = Ink2, fontSize = Design.MINI.sp, fontWeight = FontWeight.Medium) }
        content()
    }
}

/** `.hint`: what the box above it accepts. */
@Composable
private fun Hint(text: String) = Text(text, color = Ink3, fontSize = Design.TINY.sp)

/**
 * The colour toggles.
 *
 * Which colour a pip is, is its letter — not its hue, because the one
 * person who uses this cannot rely on hue. Chosen and not chosen differ
 * in lightness and in border weight as well as colour: an unchosen pip
 * is the page's own dark background behind a coloured letter, a chosen
 * one is filled and ringed in gold. `LibraryParityTest` measures that
 * difference off the screen rather than trusting it.
 */
@Composable
private fun Pips(which: String, selected: List<String>, onToggle: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        (COLOR_LETTERS + "C").forEach { letter ->
            val on = letter in selected
            val hue = c(Design.pip(letter))
            // The real symbol, the same artwork the website uses. What
            // says this one is chosen is the ring and the dimming, not
            // the hue: a thick Accent ring and the symbol at full
            // strength when on, a thin ring and a faded symbol when
            // off. Two channels that survive being unable to see the
            // difference between the colours themselves.
            Box(
                Modifier
                    .testTag("pip-$which-$letter")
                    .size(40.dp)
                    .background(if (on) hue.copy(alpha = 0.22f) else Bg, CircleShape)
                    .border(if (on) 3.dp else 2.dp, if (on) Accent else hue, CircleShape)
                    .semantics { contentDescription = NAMES[letter] ?: letter }
                    .toggleable(value = on, role = Role.Checkbox) { onToggle(letter) },
                contentAlignment = Alignment.Center,
            ) {
                ManaSymbol(
                    letter,
                    size = 22.dp,
                    text = Design.SMALL.sp,
                    modifier = Modifier.alpha(if (on) 1f else 0.55f),
                )
            }
        }
    }
}

/** Whether a mana colour is pale enough to need dark text on it. */
private fun isLight(letter: String) = letter == "W" || letter == "C"

private val NAMES = mapOf(
    "W" to "White", "U" to "Blue", "B" to "Black",
    "R" to "Red", "G" to "Green", "C" to "Colourless",
)

@Composable
private fun TextBox(tag: String, value: String, hint: String, onInput: (String) -> Unit) =
    Field(value, onInput, hint, Modifier.testTag("box-$tag"))

/**
 * A number, typed as text.
 *
 * A number keyboard, but a text field: a numeric field reports nothing
 * for anything not yet a valid number and the controlled binding then
 * erases what is being typed. The same reason the web's is text.
 */
@Composable
private fun Num(tag: String, value: String, hint: String, onInput: (String) -> Unit) =
    NumberField(value, onInput, hint, Modifier.testTag("box-$tag"))

@Composable
private fun Range(
    tag: String,
    min: String,
    max: String,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(min, onMin, "min", Modifier.weight(1f).testTag("min-$tag"))
        NumberField(max, onMax, "max", Modifier.weight(1f).testTag("max-$tag"))
    }
}

@Composable
private fun Stat(
    tag: String,
    op: String,
    value: String,
    onOp: (String) -> Unit,
    onValue: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seg(listOf(">=" to "≥", "<=" to "≤", "=" to "=", ">" to ">", "<" to "<"), op, onOp)
        NumberField(value, onValue, "any", Modifier.weight(1f).testTag("box-$tag"))
    }
}

@Composable
private fun NumberField(
    value: String,
    onInput: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onInput,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RadiusSm,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        placeholder = { Text(hint, color = Ink3, fontSize = Design.MINI.sp) },
        colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Bg,
    unfocusedContainerColor = Bg,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Line2,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    cursorColor = Accent,
)

/**
 * A list to tick, out of what the collection actually holds.
 *
 * Whatever is already chosen is an option even before the facet queries
 * land — otherwise a link arrives with a filter applied and every box
 * beneath it unticked. A tick is a shape, which is the point: nothing
 * here is told apart by hue.
 */
@Composable
private fun Checks(
    tag: String,
    values: List<String>,
    selected: List<String>,
    cols: Int = 2,
    onChange: (List<String>) -> Unit,
) {
    val options = (values + selected.filterNot { it in values }).distinct()
    if (options.isEmpty()) {
        Hint("loading…")
        return
    }
    FlowRow(Modifier.fillMaxWidth().testTag("checks-$tag")) {
        options.forEach { v ->
            Row(
                Modifier.fillMaxWidth(1f / cols)
                    .testTag("check-$tag-$v")
                    .toggleable(value = v in selected, role = Role.Checkbox) {
                        onChange(selected.toggle(v))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = v in selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Accent,
                        uncheckedColor = Line2,
                        checkmarkColor = c(Design.ON_ACCENT),
                    ),
                )
                Text(v, color = Ink2, fontSize = Design.MINI.sp)
            }
        }
    }
}

/**
 * A free list, typed one at a time and shown as removable chips. A set
 * picker with six hundred tags is worse than typing two of them.
 */
@Composable
private fun Tokens(
    tag: String,
    values: List<String>,
    hint: String,
    typed: String,
    onTyped: (String) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = onTyped,
            modifier = Modifier.fillMaxWidth().testTag("token-$tag"),
            singleLine = true,
            shape = RadiusSm,
            placeholder = { Text(hint, color = Ink3, fontSize = Design.MINI.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val v = typed.trim()
                if (v.isNotEmpty() && v !in values) onChange(values + v)
                onTyped("")
            }),
            colors = fieldColors(),
        )
        if (values.isEmpty()) {
            Hint("type and press enter")
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.forEach { v ->
                    Ghost("$v ×", on = true) { onChange(values - v) }
                }
            }
        }
    }
}

/** Three-valued, and unset means either. */
@Composable
private fun TriRow(tag: String, label: String, value: Tri, onPick: (Tri) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("tri-$tag"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(132.dp), color = Ink2, fontSize = Design.MINI.sp)
        Seg(Tri.entries.map { it.name.lowercase() to it.name.lowercase() }, value.name.lowercase()) { v ->
            onPick(Tri.entries.first { it.name.lowercase() == v })
        }
    }
}

/**
 * A `select`, as a field that opens a menu.
 *
 * A value chosen before its list arrived has to still be the one
 * showing, the same as the web's.
 */
@Composable
internal fun Dropdown(
    tag: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val opts =
        if (selected.isBlank() || options.any { it.first == selected }) options
        else options + (selected to selected)
    val shown = opts.firstOrNull { it.first == selected }?.second ?: selected
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .testTag("select-$tag")
                .background(Bg, RadiusSm)
                .border(1.dp, Line2, RadiusSm)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown, color = if (enabled) Ink else Ink3, fontSize = Design.SMALL.sp)
            Spacer(Modifier.weight(1f))
            Text("▾", color = if (enabled) Ink3 else Line2, fontSize = Design.MINI.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            opts.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == selected) Accent2 else Ink,
                            fontSize = Design.SMALL.sp,
                            fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}
