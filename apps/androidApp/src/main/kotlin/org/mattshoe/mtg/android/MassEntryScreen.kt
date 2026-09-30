package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Step

/**
 * The mass entry wizard, on Android.
 *
 * The sibling of `MassEntryPage` in the web module. Both are handed the
 * same `MassEntry` and both ask it the same questions — `canLeaveWhich`,
 * `canPreview`, `canApply` — so the only thing that can differ between
 * the two platforms is what it looks like. Whether Apply is on screen at
 * all is not this file's decision to get wrong.
 */
@Composable
fun MassEntryScreen(
    state: MassEntry,
    onState: (MassEntry) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    history: EntryHistory = EntryHistory(),
    onPickFile: () -> Unit = {},
    onReuse: (HistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Mass entry", fontSize = 26.sp)
        Text(
            when (state.direction) {
                null -> "Cards in or cards out, from a list or a file."
                Direction.ADD -> "Resolved against Scryfall, then written to the collection."
                Direction.REMOVE -> "Matched against printings you already own."
            },
            fontSize = 14.sp,
        )

        Stepper(state) { onState(state.goTo(it)) }

        when {
            state.busy != null -> Panel { Line(state.busy!!) }
            state.step == Step.WHICH -> Which(state, onState)
            state.step == Step.LIST -> {
                ListStep(state, onState, onPickFile)
                // The history table puts an old list back in the box, so
                // it belongs on the step that has the box.
                HistoryPanel(history, onReuse, onClearHistory)
            }
            state.step == Step.WHO -> Who(state, onState, onPreview)
            state.step == Step.REVIEW -> Review(state, onState, onApply)
            state.step == Step.DONE -> Done(state) { onState(state.again()) }
        }

        state.error?.let { Text(it, fontSize = 14.sp) }
    }
}

@Composable
private fun Stepper(s: MassEntry, go: (Step) -> Unit) {
    val at = Step.wizard.indexOf(s.step).let { if (it == -1) Step.wizard.size else it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Step.wizard.forEachIndexed { i, step ->
            val done = i < at
            OutlinedButton(
                onClick = { go(step) },
                // Same rule the web stepper uses, from the same object.
                enabled = done && s.reachable(step),
            ) { Text("${if (done) "✓" else "${i + 1}"} ${step.label}", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Which(s: MassEntry, onState: (MassEntry) -> Unit) {
    Panel(head = "Adding or removing?", note = if (s.cardCount > 0) "${s.cardCount} cards already in the box" else null) {
        Choice("Add to the collection", s.direction == Direction.ADD) { onState(s.choose(Direction.ADD)) }
        Choice("Remove from the collection", s.direction == Direction.REMOVE) { onState(s.choose(Direction.REMOVE)) }
        Spacer(Modifier.height(8.dp))
        Primary(
            if (s.canLeaveWhich) "Continue →" else "Pick one to continue",
            s.canLeaveWhich,
        ) { onState(s.goTo(Step.LIST)) }
        if (!s.canLeaveWhich) Text("Nothing is preselected on purpose.", fontSize = 13.sp)
    }
}

@Composable
private fun ListStep(s: MassEntry, onState: (MassEntry) -> Unit, onPickFile: () -> Unit) {
    val kind = if (s.isCsv) " · CSV" else ""
    val over = if (s.overLimit) " — over the ${MassEntry.MAX_CARDS} line limit" else ""
    Panel(head = s.direction!!.question, note = "${s.tally.lines} lines$kind$over") {
        OutlinedTextField(
            value = s.list,
            onValueChange = { onState(s.type(it)) },
            modifier = Modifier.fillMaxWidth().height(260.dp),
            placeholder = { Text("One card per line.") },
        )
        Spacer(Modifier.height(8.dp))
        // What the box adds up to. A line count is what the request
        // size is limited by, not what anybody pasting a deck wants:
        // "4 Lightning Bolt" is four cards on one line.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Figure("${s.tally.cards}", if (s.tally.cards == 1) "card" else "cards")
            Figure("${s.tally.unique}", "unique")
            Figure("${s.tally.lines}", if (s.tally.lines == 1) "line" else "lines")
        }
        Spacer(Modifier.height(8.dp))
        // Reading a file only fills the box. It never submits and never
        // advances a step, the same as the web.
        OutlinedButton(onClick = onPickFile) { Text("Upload a file") }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onState(s.goTo(Step.WHICH)) }) { Text("← Back") }
            Primary("Continue →", s.canLeaveList) { onState(s.goTo(Step.WHO)) }
        }
    }
}

@Composable
private fun Who(s: MassEntry, onState: (MassEntry) -> Unit, preview: () -> Unit) {
    Panel(head = "Whose collection?", note = "${s.tally.cards} cards on the list") {
        Owner.entries.forEach { o -> Choice(o.label, s.owner == o) { onState(s.assign(o)) } }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onState(s.goTo(Step.LIST)) }) { Text("← Back") }
            Primary(
                if (s.canPreview) "Preview changes · ${s.owner!!.slug} →" else "Pick one to continue",
                s.canPreview,
                preview,
            )
        }
        if (!s.canPreview) Text("Pick whose collection this goes to.", fontSize = 13.sp)
    }
}

@Composable
private fun Review(s: MassEntry, onState: (MassEntry) -> Unit, apply: () -> Unit) {
    val p = s.preview
    Panel(head = "Preview — nothing written yet", note = s.owner?.slug) {
        if (p == null) {
            Text("No preview yet.")
        } else {
            Text("${p.resolved} resolved, ${p.failed} failed, ${p.changes.size} printings")
            Spacer(Modifier.height(8.dp))
            // Selectable, the same as the web rendering. A canvas
            // renderer would have taken this away.
            SelectionContainer {
                Text(
                    p.changes.joinToString("\n") {
                        "${it.name} (${it.set} ${it.collectorNumber}) ${it.before} → ${it.after}"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            if (p.errors.isNotEmpty()) Text(p.errors.joinToString("\n"), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onState(s.goTo(Step.WHO)) }) { Text("← Back") }
            Primary(
                if (s.canApply) {
                    "${s.direction!!.verb} ${p!!.changes.size} printings · ${s.owner!!.slug}"
                } else {
                    "Nothing to apply"
                },
                s.canApply,
                apply,
            )
        }
    }
}

@Composable
private fun Done(s: MassEntry, again: () -> Unit) {
    val r = s.result!!
    Panel(head = if (r.applied) "Applied" else "Nothing applied") {
        Text("${r.resolved} resolved, ${r.failed} failed, ${r.changes.size} printings")
        if (r.errors.isNotEmpty()) Text(r.errors.joinToString("\n"), fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Primary("Enter more", true, again)
    }
}

// -------------------------------------------------------------- pieces

/**
 * One big decision per screen, which is what the wizard is for. The
 * same `.owner-opt` shape the web uses on these steps.
 */
@Composable
private fun Choice(label: String, on: Boolean, click: () -> Unit) {
    Line(
        label,
        color = if (on) Accent2 else Ink,
        size = 15,
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (on) AccentDim else Bg2, Radius)
            .border(1.dp, if (on) Accent else Line2, Radius)
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    )
}

/** What was entered recently, and putting it back in the box. */
@Composable
private fun HistoryPanel(
    history: EntryHistory,
    onReuse: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    if (history.isEmpty) return
    Panel(head = "Recent", note = "${history.entries.size} kept on this device") {
        history.recent.forEach { e ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.at.replace('T', ' ').take(16), fontSize = 12.sp)
                Text(e.direction, fontSize = 12.sp)
                Text(e.owner, fontSize = 12.sp)
                Text("${e.count}", fontSize = 12.sp)
                OutlinedButton(onClick = { onReuse(e) }) { Text("Reuse", fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClear) { Text("Clear") }
    }
}

/** One number and what it counts, for the tally under the box. */
@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Line(value, Ink, org.mattshoe.mtg.core.Design.H2, androidx.compose.ui.text.font.FontWeight.SemiBold)
        Line(label.uppercase(), Ink3, org.mattshoe.mtg.core.Design.MINI)
    }
}
