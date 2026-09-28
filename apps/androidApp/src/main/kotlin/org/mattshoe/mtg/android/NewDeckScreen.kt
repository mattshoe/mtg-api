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
                    state.step == DeckStep.COMMANDER -> CommanderStep(state, onState)
                    state.step == DeckStep.CARDS -> CardsStep(state, onState)
                    state.step == DeckStep.CHECK -> CheckStep(state, onState)
                    state.step == DeckStep.REVIEW -> ReviewStep(state, onState)
                    state.step == DeckStep.DONE -> Text("${state.name} is created.")
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
        DeckStep.CHECK ->
            if (s.canLeaveCheck) Next(true) { onState(s.goTo(DeckStep.REVIEW)) }
            else TextButton(onClick = onCheck, enabled = s.busy == null) {
                Text(if (s.checked == null) "Check the names" else "Check again")
            }
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
        s.steps.forEach { step ->
            OutlinedButton(onClick = { go(step) }, enabled = s.reachable(step) && step != s.step) {
                Text(step.label, fontSize = 11.sp)
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
private fun CommanderStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    OutlinedTextField(
        value = s.commander,
        onValueChange = { onState(s.setCommander(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Commander") },
    )
    Text("A ${s.format?.label} deck needs one, and the server checks it too.", fontSize = 12.sp)
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit) {
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
}

@Composable
private fun CheckStep(s: NewDeck, onState: (NewDeck) -> Unit) {
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
            v.suggestions.forEach { (wrong, right) ->
                OutlinedButton(onClick = { onState(s.type(s.list.replace(wrong, right))) }) {
                    Text("$wrong → $right", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Text(
        "${s.format?.label} · ${s.owner?.label} · ${s.cardCount} cards" +
            if (s.commander.isNotBlank()) " · ${s.commander}" else "",
        fontSize = 13.sp,
    )
    Text(
        "Where each card comes from. Nothing is created until every line has an answer.",
        fontSize = 12.sp,
    )
    if (s.undecided.isEmpty()) {
        Text("Every line has a source", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    } else {
        Text("${s.undecided.size} still undecided", fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Source.entries.forEach { src ->
                OutlinedButton(onClick = {
                    onState(s.undecided.fold(s) { acc, line -> acc.source(line, src) })
                }) { Text("All ${src.label.lowercase()}", fontSize = 11.sp) }
            }
        }
        s.undecided.take(40).forEach { line ->
            Text(line, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Source.entries.forEach { src ->
                    OutlinedButton(onClick = { onState(s.source(line, src)) }) {
                        Text(src.label, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (s.buying.isNotEmpty()) {
        Text(
            "${s.buying.size} line${if (s.buying.size == 1) "" else "s"} will be added to the collection",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Pick(label: String, on: Boolean, click: () -> Unit) {
    Button(
        onClick = click,
        colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
    ) { Text(label, fontSize = 12.sp) }
}
