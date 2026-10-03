package org.mattshoe.mtg.android

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
                state.error?.let { Text(it, fontSize = 13.sp) }
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
            OutlinedButton(onClick = { go(step) }, enabled = s.reachable(step)) {
                Text("${i + 1} ${step.label}", fontSize = 11.sp)
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
    )
    Text("A ${s.format?.label} deck needs one, and the server checks it too.", fontSize = 12.sp)
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit, onPickFile: () -> Unit) {
    OutlinedTextField(
        value = s.list,
        onValueChange = { onState(s.type(it)) },
        modifier = Modifier.fillMaxWidth().height(220.dp),
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
        v.ok -> Text("All ${v.checked} names are real cards", fontWeight = FontWeight.SemiBold)
        else -> {
            Text("${v.unknown} not found", fontWeight = FontWeight.SemiBold)
            Text(v.bad.joinToString(", ") { it.name }, fontSize = 12.sp)
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
    Text(
        "${plan.size - adding.size} from bulk · ${adding.size} added to bulk",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    // Every line, not the first two hundred of them. A list longer
    // than the cap silently lost rows off the bottom, which on the
    // one screen whose job is to say what will happen to each card is
    // the worst place to be approximate.
    plan.forEach { line ->
        Text("${line.qty}  ${line.name} — ${line.from.label}", fontSize = 12.sp)
    }
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
    Text("Created", fontWeight = FontWeight.SemiBold)
    Text("${s.name} is at #/decks/${s.slug}", fontSize = 12.sp)
}

@Composable
private fun Pick(label: String, on: Boolean, click: () -> Unit) {
    Button(
        onClick = click,
        colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
    ) {
        // The tick, the way the web marks the chosen option. Fill
        // against outline is a lightness difference and a colour one,
        // and neither is any use to somebody who cannot tell this
        // app's two greens apart — a character can be read.
        Text(if (on) "✓ $label" else label, fontSize = 12.sp)
    }
}
