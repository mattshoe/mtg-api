package org.mattshoe.mtg.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Source

/**
 * The new deck wizard, on Android. Sibling of `NewDeckDialog` on the
 * web.
 *
 * Creating a deck moves real cards — it pulls from bulk and records
 * what bulk cannot cover as bought — so every gate on the way is
 * `NewDeck` in the shared core rather than anything decided here.
 */
@Composable
fun NewDeckDialog(
    state: NewDeck,
    onState: (NewDeck) -> Unit,
    onCheck: () -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    /**
     * The commander box was typed into. The caller does the lookup,
     * exactly as on the web — the box itself still works without one,
     * it just has nothing to suggest.
     */
    onCommanderTyped: (Completion) -> Unit = {},
    /** A file for the card list. The web drops one on the same step. */
    onPickFile: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New deck · ${state.step.label}") },
        confirmButton = { Confirm(state, onState, onCheck, onCreate, onClose) },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stepper(state) { onState(state.goTo(it)) }
                when {
                    state.busy != null -> Text(state.busy!!)
                    state.step == DeckStep.FORMAT -> FormatStep(state, onState)
                    state.step == DeckStep.OWNER -> OwnerStep(state, onState)
                    state.step == DeckStep.NAME -> NameStep(state, onState)
                    state.step == DeckStep.COMMANDER ->
                        CommanderStep(state, onState, onCommanderTyped)
                    state.step == DeckStep.CARDS -> CardsStep(state, onState, onPickFile)
                    state.step == DeckStep.CHECK -> CheckStep(state, onState, onCheck)
                    state.step == DeckStep.REVIEW -> ReviewStep(state)
                    state.step == DeckStep.DONE -> DoneStep(state)
                }
                state.error?.let { ErrBox(it) }
            }
        },
    )
}

@Composable
private fun Confirm(
    s: NewDeck,
    onState: (NewDeck) -> Unit,
    onCheck: () -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    when (s.step) {
        DeckStep.FORMAT -> Next(s.canLeaveFormat) { onState(s.goTo(DeckStep.OWNER)) }
        DeckStep.OWNER -> Next(s.canLeaveOwner) { onState(s.goTo(DeckStep.NAME)) }
        DeckStep.NAME -> Next(s.canLeaveName) {
            onState(s.goTo(if (s.needsCommander) DeckStep.COMMANDER else DeckStep.CARDS))
        }
        DeckStep.COMMANDER -> Next(s.canLeaveCommander) { onState(s.goTo(DeckStep.CARDS)) }
        DeckStep.CARDS -> Next(s.canLeaveCards) { onState(s.goTo(DeckStep.CHECK)) }
        // Continue, always — and the check itself sits in the body
        // beside it, the way the web shows both. Swapping one button
        // for the other meant a verdict you had no way to ask for
        // twice, and the only way back was to edit the list, which
        // throws the verdict away.
        DeckStep.CHECK -> Next(s.canLeaveCheck) { onState(s.goTo(DeckStep.REVIEW)) }
        DeckStep.REVIEW -> TextButton(onClick = onCreate, enabled = s.canCreate) {
            Text("Create ${s.name}")
        }
        DeckStep.DONE -> TextButton(onClick = onClose) { Text("Done") }
    }
}

@Composable
private fun Next(enabled: Boolean, click: () -> Unit) {
    TextButton(onClick = click, enabled = enabled) { Text("Continue →") }
}

@Composable
private fun Stepper(s: NewDeck, go: (DeckStep) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Numbered, and gated on `reachable` alone — the same two
        // rules the web stepper draws. The step you are on stays
        // pressable there, so it does here.
        s.steps.forEachIndexed { i, step ->
            // `.steps .step.on`: the one you are on is marked. Android
            // drew all seven identically, so eight steps into a wizard
            // the only thing saying where you were was the title. The
            // mark is weight and a filled face, not a hue — and it is
            // `selected` in the semantics as well, which is what the
            // web's `.on` means and what a screen reader needs.
            val here = step == s.step
            OutlinedButton(
                onClick = { go(step) },
                enabled = s.reachable(step),
                // `.steps .step` is `8px 14px` round 13px text.
                // Material's own button is 40dp tall with 24dp of air
                // either side, which turned seven short words into
                // four rows and half the dialog — on the Cards step
                // it pushed the upload button clean off the bottom.
                modifier = Modifier
                    .semantics { selected = here }
                    .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                border = BorderStroke(1.dp, if (here) Accent else Line),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (here) AccentDim else Bg2,
                    contentColor = if (here) Accent2 else Ink2,
                    disabledContainerColor = Bg2,
                    disabledContentColor = Ink3,
                ),
            ) {
                Text(
                    "${i + 1} ${step.label}",
                    fontSize = Design.MINI.sp,
                    fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun FormatStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Format.entries.forEach { f ->
            Pick(f.label, s.format == f) { onState(s.pick(f)) }
        }
    }
}

@Composable
private fun OwnerStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Owner.entries.forEach { o -> Pick(o.label, s.owner == o) { onState(s.assign(o)) } }
    }
}

@Composable
private fun NameStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    OutlinedTextField(
        value = s.name,
        onValueChange = { onState(s.rename(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Deck name") },
    )
    if (s.name.isNotBlank()) Text("It will live at #/decks/${s.slug}", fontSize = 12.sp)
}

@Composable
private fun CommanderStep(
    s: NewDeck,
    onState: (NewDeck) -> Unit,
    onTyped: (Completion) -> Unit,
) {
    // The same suggestion field the Library uses, and the same one the
    // web wizard uses. This was a bare text box, so the one name in
    // the whole wizard that has to be spelt exactly right was the one
    // name with no help spelling it.
    AutocompleteField(
        label = "e.g. Alela, Artful Provocateur",
        state = s.hint,
        onState = { c ->
            onState(s.hinting(c))
            onTyped(c)
        },
        onPick = { name -> onState(s.setCommander(name)) },
        // Its own, because the dialog is its own window: the Library's
        // box and this one are never on screen together, and `hinting`
        // is where this one's `Completion` lives. `closed()` rather
        // than a rebuilt one, so the commander half-typed into the box
        // survives the list going away.
        onDismiss = { onState(s.hinting(s.hint.closed())) },
    )
    Text("A ${s.format?.label} deck needs one, and the server checks it too.", fontSize = 12.sp)
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit, onPickFile: () -> Unit) {
    OutlinedTextField(
        value = s.list,
        onValueChange = { onState(s.type(it)) },
        // `clamp(150px, 34vh, 320px)`. A dialog body capped at 460dp
        // is the short viewport that clamp has a floor for: at 220dp
        // the box and the stepper filled the step and what you do
        // next was off the bottom with nothing saying so.
        modifier = Modifier.fillMaxWidth().height(150.dp),
        label = { Text("One card per line") },
    )
    val wanted = s.format?.size ?: 0
    Text(
        "${s.cardCount} cards" + if (wanted > 0) " · ${s.format!!.label} wants $wanted" else "",
        fontSize = 12.sp,
    )
    // A deck you already have written down is in a file. Reading one
    // only fills the box — it never submits and never moves a step,
    // the same as the web and the same as mass entry.
    OutlinedButton(onClick = onPickFile) { Text("Upload a file") }
    if (s.needsCommander) {
        // Every decklist export puts the commander first, so cutting
        // it out by hand and retyping it is work the list has done.
        DeckList.firstCard(s.list)?.let { first ->
            OutlinedButton(onClick = { onState(s.commanderFromList()) }) {
                Text("First card is the commander (${first.name})", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CheckStep(s: NewDeck, onState: (NewDeck) -> Unit, onCheck: () -> Unit) {
    val v = s.checked
    when {
        v == null -> Text(
            "Every name is checked against the collection first, then Scryfall.",
            fontSize = 13.sp,
        )
        // `.tag.ok` / `.tag.bad`: the verdict is a tag on the website,
        // a stated fact with an edge round it, not a bold sentence
        // loose in the column. Which verdict it is is carried by the
        // words — "real cards" against "not found" — because the two
        // tones are a hue apart and that is no use to the person who
        // reads this.
        v.ok -> Tag("All ${v.checked} names are real cards", Ok)
        else -> {
            Tag("${v.unknown} not found", Bad)
            // `.chips`: one chip per name. Joined into "a, b, c" the
            // names ran together, and the one you had to find was in
            // the middle of a sentence instead of in its own box.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                v.bad.forEach { BadChip(it.name) }
            }
            if (v.suggestions.isNotEmpty()) {
                Text("Tap one to use it", fontSize = 12.sp)
            }
            v.suggestions.forEach { (wrong, right) ->
                // `correct` rather than a rewrite of the list, because
                // the commander lives in its own field — the name most
                // likely to be typed from memory was the one the
                // button could not fix.
                OutlinedButton(onClick = { onState(s.correct(wrong, right)) }) {
                    Text("$wrong → $right", fontSize = 12.sp)
                }
            }
        }
    }
    // The check itself, beside the verdict rather than instead of the
    // way on. A list that checked out can still be checked again.
    OutlinedButton(onClick = onCheck, enabled = s.busy == null) {
        Text(if (v == null) "Check the names" else "Check again")
    }
}

@Composable
private fun ReviewStep(s: NewDeck) {
    // One line per card and nothing to answer. The wizard used to ask
    // where every copy should come from and then not send it — the
    // create call has no `sources` field. What happens is decided by
    // what the collection already holds.
    Text(
        "${s.format?.label} · ${s.owner?.label} · ${s.cardCount} cards" +
            if (s.commander.isNotBlank()) " · ${s.commander}" else "",
        fontSize = 13.sp,
    )
    val plan = s.plan
    val adding = s.adding
    // Two figures, the way the website draws them: the number big
    // and what it counts small underneath. As one sentence it was
    // the same words and none of the glance.
    //
    // `.tally` is one bordered block with a hairline down it rather
    // than two tiles with air between them, and the difference is
    // whether the pair reads as one count split in two or as two
    // unrelated numbers.
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier.fillMaxWidth()
            .clip(RadiusSm)
            .border(1.dp, Line, RadiusSm)
            .background(Line),
    ) {
        PlanFigure("${plan.size - adding.size}", "from bulk", Modifier.weight(1f))
        PlanFigure("${adding.size}", "added to bulk", Modifier.weight(1f))
    }
    // Every line, not the first two hundred of them. A list longer
    // than the cap silently lost rows off the bottom, which on the
    // one screen whose job is to say what will happen to each card is
    // the worst place to be approximate.
    plan.forEach { line -> PlanRow(line) }
    if (adding.isNotEmpty()) {
        Text(
            "${adding.size} card${if (adding.size == 1) "" else "s"} " +
                "the collection does not hold yet will be added to bulk.",
            fontSize = 12.sp,
        )
    }
}

/** What the web's `DoneStep` says: created, and where it lives. */
@Composable
private fun DoneStep(s: NewDeck) {
    // `.tag.ok`, the same tag the check's verdict uses. It was a bold
    // word with nothing round it, which on the one screen that exists
    // to say "it worked" read as a heading for the line underneath.
    Tag("Created", Ok)
    Text("${s.name} is at #/decks/${s.slug}", fontSize = 12.sp)
}

/**
 * `.chip.mini.bad`: a name the check could not find.
 *
 * Says nothing when pressed, because it is not a control — the
 * suggestion buttons underneath are. Outlined in the bad tone, and
 * named in full, so which card is wrong is a thing you read rather
 * than a colour you have to see.
 */
@Composable
private fun BadChip(name: String) {
    Text(
        name,
        Modifier
            .clip(Pill)
            .background(Bg)
            .border(1.dp, Bad.copy(alpha = 0.55f), Pill)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = Bad,
        fontSize = Design.TINY.sp,
    )
}

@Composable
private fun Pick(label: String, on: Boolean, click: () -> Unit) {
    Button(
        onClick = click,
        // `aria-pressed`, which the web sets on every one of these and
        // Android said nothing about at all.
        modifier = Modifier.semantics { selected = on },
        // `.opt` and `.opt.on`: a tinted face and a gold edge, not a
        // button flooded with gold. Material's filled default was a
        // different thing from what the website draws.
        border = BorderStroke(1.dp, if (on) Accent else Line2),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) AccentDim else Bg2,
            contentColor = if (on) Accent2 else Ink,
        ),
    ) {
        // The tick, the way the web marks the chosen option. Fill
        // against outline is a lightness difference and a colour one,
        // and neither is any use to somebody who cannot tell this
        // app's two greens apart — a character can be read.
        Text(if (on) "✓ $label" else label, fontSize = 12.sp)
    }
}


/**
 * One of the two figures above the list.
 *
 * The website gives these a cell each, a big number and a small
 * uppercase label. They are the only part of the review anybody
 * reads at a glance, and as a run-on sentence they were not.
 */
@Composable
private fun PlanFigure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        // Square corners: `.tally-cell` is a plain cell and the block
        // around it owns the radius, so a rounded cell inside a
        // rounded block left the hairline between them looking broken.
        //
        // One cell, one thing read: the number and what it counts are
        // a figure, not two scraps of text that happen to be near
        // each other. A screen reader was stopping on each of them.
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .background(Bg2)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(
            label.uppercase(),
            fontSize = 10.5.sp,
            letterSpacing = 0.07.em,
            color = Ink3,
        )
    }
}

/**
 * One card of the review: how many, what it is called, and what
 * happens to it.
 *
 * The status is a tag rather than words after an em dash, and the
 * one that adds to the collection is outlined as well as tinted —
 * the same rule the website follows, and the reason is that hue
 * alone is no use to the person who reads this.
 */
@Composable
private fun PlanRow(line: NewDeck.Line) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // `.plan-row` is one row about one card. Unmarked, the three
        // cells were three separate things to a screen reader and to
        // anything else reading the tree, so "1", a card name and a
        // tag sat at the same level as the paragraph underneath.
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            "${line.qty}",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink2,
            modifier = Modifier.width(26.dp),
            textAlign = TextAlign.End,
        )
        Text(
            line.name,
            fontSize = 13.5.sp,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val adding = !line.owned
        Text(
            line.from.label,
            fontSize = 11.sp,
            color = if (adding) Accent2 else Ink2,
            // Clip, fill, then ring. The ring used to be drawn first
            // and the fill painted over the top of it, so the one
            // thing telling the two tags apart without a hue was
            // whatever of it happened to survive the rounding.
            modifier = Modifier
                .clip(Pill)
                .background(if (adding) AccentDim else Bg3)
                .then(if (adding) Modifier.border(1.dp, Accent, Pill) else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
