package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.COLOR_LETTERS
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.ColorTarget
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Tri

/**
 * The filter sheet, on Android. Sibling of `FilterPanel`.
 *
 * Only edits a `Filters`; what any of it means in SQL is the shared
 * core's business, which is why the two platforms cannot disagree about
 * what "at most these colours" returns.
 */
@Composable
fun FilterSheet(f: Filters, onChange: (Filters) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Filters", fontSize = 22.sp)

        Label("Words")
        Field("Name", f.q) { onChange(f.copy(q = it)) }
        Field("Oracle text", f.text) { onChange(f.copy(text = it)) }
        Field("Text contains", f.textLike) { onChange(f.copy(textLike = it)) }
        Field("Flavour", f.flavor) { onChange(f.copy(flavor = it)) }
        Field("Artist", f.artist) { onChange(f.copy(artist = it)) }
        Field("Watermark", f.watermark) { onChange(f.copy(watermark = it)) }
        Field("Type line", f.typeLine) { onChange(f.copy(typeLine = it)) }
        Field("Mana cost", f.manaCost) { onChange(f.copy(manaCost = it)) }

        Label("Colour")
        Chips(ColorTarget.entries.map { it to if (it == ColorTarget.IDENTITY) "Identity" else "Printed" },
            f.colorTarget) { onChange(f.copy(colorTarget = it)) }
        Chips(ColorMode.entries.map { it to it.label }, f.colorMode) { onChange(f.copy(colorMode = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (COLOR_LETTERS + "C").forEach { c ->
                Toggle(c, c in f.colors) {
                    onChange(f.copy(colors = if (c in f.colors) f.colors - c else f.colors + c))
                }
            }
        }
        Text(f.colorMode.explains, fontSize = 12.sp)
        Range("Colours in identity", f.ciMin, f.ciMax,
            { onChange(f.copy(ciMin = it)) }, { onChange(f.copy(ciMax = it)) })

        Label("Numbers")
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
        Compare("Power", f.powOp, f.pow, { onChange(f.copy(powOp = it)) }, { onChange(f.copy(pow = it)) })
        Compare("Toughness", f.touOp, f.tou, { onChange(f.copy(touOp = it)) }, { onChange(f.copy(tou = it)) })
        Compare("Loyalty", f.loyOp, f.loy, { onChange(f.copy(loyOp = it)) }, { onChange(f.copy(loy = it)) })

        Label("Pool")
        Chips(Pool.entries.map { it to it.slug.replaceFirstChar(Char::uppercase) }, f.pool) {
            onChange(f.copy(pool = it))
        }
        Field("At least this many free", f.freeMin) { onChange(f.copy(freeMin = it)) }
        Field("Deck slug", f.deck) { onChange(f.copy(deck = it)) }
        Field("Finish", f.finish) { onChange(f.copy(finish = it)) }

        Label("Printing")
        CommaList("Rarities", f.rarities) { onChange(f.copy(rarities = it)) }
        CommaList("Sets", f.sets) { onChange(f.copy(sets = it)) }
        CommaList("Types", f.types) { onChange(f.copy(types = it)) }
        CommaList("Not types", f.typesNot) { onChange(f.copy(typesNot = it)) }
        CommaList("Supertypes", f.supertypes) { onChange(f.copy(supertypes = it)) }
        CommaList("Subtypes", f.subtypes) { onChange(f.copy(subtypes = it)) }
        CommaList("Keywords", f.keywords) { onChange(f.copy(keywords = it)) }
        CommaList("Tags", f.tags) { onChange(f.copy(tags = it)) }
        Field("Collector number", f.collnum) { onChange(f.copy(collnum = it)) }

        Label("Flags")
        Flag.entries.forEach { flag ->
            val t = f.flags[flag] ?: Tri.ANY
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(flag.label, fontSize = 12.sp, modifier = Modifier.width(140.dp))
                Tri.entries.forEach { v ->
                    Toggle(v.name.lowercase(), t == v) {
                        onChange(f.copy(flags = f.flags + (flag to v)))
                    }
                }
            }
        }

        Label("Legality")
        Field("Format", f.format) { onChange(f.copy(format = it)) }
        Field("Status", f.legality) { onChange(f.copy(legality = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Has rulings", fontSize = 12.sp)
            Tri.entries.forEach { v ->
                Toggle(v.name.lowercase(), f.hasRulings == v) { onChange(f.copy(hasRulings = v)) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone) { Text("Apply") }
            OutlinedButton(onClick = { onChange(Filters()) }) { Text("Reset everything") }
        }
    }
}

@Composable private fun Label(s: String) = Text(s, fontSize = 16.sp)

@Composable
private fun Field(label: String, value: String, onInput: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onInput,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
    )
}

@Composable
private fun Range(label: String, min: String, max: String, onMin: (String) -> Unit, onMax: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        OutlinedTextField(min, onMin, Modifier.width(80.dp), singleLine = true,
            placeholder = { Text("min", fontSize = 11.sp) })
        OutlinedTextField(max, onMax, Modifier.width(80.dp), singleLine = true,
            placeholder = { Text("max", fontSize = 11.sp) })
    }
}

@Composable
private fun Compare(label: String, op: String, value: String, onOp: (String) -> Unit, onValue: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(90.dp))
        listOf(">=", "<=", "=").forEach { o -> Toggle(o, op == o) { onOp(o) } }
        OutlinedTextField(value, onValue, Modifier.width(70.dp), singleLine = true)
    }
}

/** Comma separated: a picker with six hundred tags is worse than typing two. */
@Composable
private fun CommaList(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    Field(label, values.joinToString(", ")) { raw ->
        onChange(raw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) -> Toggle(label, value == selected) { onPick(value) } }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, click: () -> Unit) {
    Button(
        onClick = click,
        colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
    ) { Text(label, fontSize = 11.sp) }
}
