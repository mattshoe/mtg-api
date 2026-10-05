package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import org.mattshoe.mtg.core.Design
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
        // No heading: the tab says "Entry".
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
            // "Working", headed, the same as the web — a panel with no
            // head is not the same screen as one that says what it is.
            state.busy != null -> Panel(head = "Working") { Line(state.busy!!) }
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
    // `.steps`: bordered chips at the site's radius, not Material's
    // stadium. They wrap rather than squeeze, the same as the web's
    // `flex-wrap`, because four of these do not fit a phone in a row.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Step.wizard.forEachIndexed { i, step ->
            val done = i < at
            Btn(
                "${if (done) "✓" else "${i + 1}"} ${step.label}",
                // Same rule the web stepper uses, from the same object.
                enabled = done && s.reachable(step),
            ) { go(step) }
        }
    }
}

@Composable
private fun Which(s: MassEntry, onState: (MassEntry) -> Unit) {
    // Cards, not lines. "4 Lightning Bolt" is four cards already in
    // the box, and `cardCount` counts the line — which is the number
    // the request size is limited by and not the one to say here.
    Panel(
        head = "Adding or removing?",
        note = if (s.tally.cards > 0) "${s.tally.cards} cards already in the box" else null,
    ) {
        Choice(
            "Add to the collection",
            "Cards you bought, opened or were given.",
            s.direction == Direction.ADD,
        ) { onState(s.choose(Direction.ADD)) }
        Choice(
            "Remove from the collection",
            "Cards you sold, traded away or lost.",
            s.direction == Direction.REMOVE,
        ) { onState(s.choose(Direction.REMOVE)) }
        // One label, enabled or not. A button whose words change is a
        // different button, and the web's says "Continue →" either way.
        Foot(hint = if (!s.canLeaveWhich) "Nothing is preselected on purpose." else null) {
            Primary("Continue →", s.canLeaveWhich) { onState(s.goTo(Step.LIST)) }
        }
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
            // No placeholder, the same as the web's `<textarea>`. The
            // panel above the box already asks the question and the
            // tally below it already counts the lines; a third
            // sentence inside the box said what both of them say.
        )
        // What the box adds up to. A line count is what the request
        // size is limited by, not what anybody pasting a deck wants:
        // "4 Lightning Bolt" is four cards on one line.
        Tally(
            "${s.tally.cards}" to if (s.tally.cards == 1) "card" else "cards",
            "${s.tally.unique}" to "unique",
            "${s.tally.lines}" to if (s.tally.lines == 1) "line" else "lines",
        )
        // Reading a file only fills the box. It never submits and never
        // advances a step, the same as the web.
        Btn("Upload a file", onClick = onPickFile)
        // Why the button beside it is not doing anything yet, and —
        // said out loud rather than assumed — that two more steps
        // stand between this box and the collection.
        Foot(
            hint = when {
                !s.canLeaveList && s.tally.cards == 0 -> "Paste a list, or drop a file on the box."
                s.unsaved -> "Nothing is written until you press the button on the last step."
                else -> null
            },
        ) {
            Ghost("← Back") { onState(s.goTo(Step.WHICH)) }
            Primary("Continue →", s.canLeaveList) { onState(s.goTo(Step.WHO)) }
        }
    }
}

@Composable
private fun Who(s: MassEntry, onState: (MassEntry) -> Unit, preview: () -> Unit) {
    Panel(head = "Whose collection?", note = "${s.tally.cards} cards on the list") {
        Owner.entries.forEach { o -> Choice(o.label, null, s.owner == o) { onState(s.assign(o)) } }
        Foot(hint = if (!s.canPreview) "Pick whose collection this goes to." else null) {
            Ghost("← Back") { onState(s.goTo(Step.LIST)) }
            // The web's label, unchanged by whether it is pressable.
            // Who was named is said by the tick on the row above it,
            // and again by the note on the step after this one.
            Primary("Preview changes →", s.canPreview, preview)
        }
    }
}

@Composable
private fun Review(s: MassEntry, onState: (MassEntry) -> Unit, apply: () -> Unit) {
    val p = s.preview
    Panel(head = "Preview — nothing written yet", note = s.owner?.slug) {
        if (p == null) Text("No preview yet.") else Outcome(p)
        Foot {
            Ghost("← Back") { onState(s.goTo(Step.WHO)) }
            Primary(
                if (s.canApply) "${s.direction!!.verb} ${p!!.changes.size} printings" else "Nothing to apply",
                s.canApply,
                apply,
            )
        }
    }
}

/**
 * What the write did, and the way back to the start.
 *
 * `applied` is the server answering for the call, not for the cards.
 * A removal of printings somebody has already removed comes back
 * applied with an empty change list, and "Applied" over nought
 * printings and nought copies reads as a write that landed. So the
 * title asks whether anything moved rather than whether the call was
 * made — the same question the web page asks.
 */
@Composable
private fun Done(s: MassEntry, again: () -> Unit) {
    val r = s.result!!
    val moved = r.applied && r.changes.isNotEmpty()
    Panel(head = if (moved) "Applied" else "Nothing applied", note = s.owner?.slug) {
        Outcome(r)
        Foot { Primary("Enter more", true, again) }
    }
}

/**
 * What the write did, or would do.
 *
 * A row per printing rather than one block of monospace — the same
 * shape as the web, because a column of 248 lines is not something
 * anybody can check on a phone.
 */
@Composable
private fun Outcome(r: org.mattshoe.mtg.core.Applied) {
    Tally(
        "${r.changes.size}" to if (r.changes.size == 1) "printing" else "printings",
        "${r.copies}" to if (r.copies == 1) "copy" else "copies",
        "${r.fresh}" to "new",
    )
    // `.tag.bad`: a stated fact in a chip, the way the web states it.
    // A red sentence and a red chip are not the same thing to read,
    // and a chip survives being unable to see the red at all.
    if (r.failed > 0) Tag("${r.failed} could not be resolved", Bad)
    SelectionContainer {
        Column(Modifier.fillMaxWidth()) {
            // `.changes` is ruled along the top as well, so the first
            // row has an edge above it and the block reads as a table
            // rather than as text that happens to start here.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            r.changes.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The art says which card faster than the name does.
                    Box(
                        Modifier.size(width = 44.dp, height = 32.dp)
                            .background(Bg3, RadiusSm)
                            .border(1.dp, Line, RadiusSm)
                            .clip(RadiusSm),
                    ) {
                        c.art?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Line(c.name, Ink, Design.SMALL, FontWeight.Medium)
                        // `.chg-where`: the printing in mono, then a
                        // tag apiece for what is true of it. This was
                        // one grey run-on sentence joined with " · ",
                        // which is not what the website shows and not
                        // something anybody can pick a word out of.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "${c.set.uppercase()} ${c.collectorNumber}",
                                color = Ink3,
                                fontSize = Design.TINY.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (c.finish != "nonfoil") Tag(c.finish)
                            if (c.isNew) Tag("new", Ok)
                            // The collection has none of this printing
                            // left. The web says so and this did not.
                            if (c.isGone) Tag("last one", Warn)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        // Mono, so a column of them lines up on the
                        // sign and the digits, the same as `.chg-delta`.
                        Text(
                            c.sign,
                            color = if (c.delta < 0) Bad else Ok,
                            fontSize = Design.H3.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${c.before} → ${c.after}",
                            color = Ink3,
                            fontSize = Design.TINY.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                // `.chg` is ruled off at the bottom: without it 248 of
                // these are one block of text and no row has an edge.
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        }
    }
    if (r.errors.isNotEmpty()) {
        r.errors.forEach { Line(it, Bad, Design.SMALL) }
    }
}

// -------------------------------------------------------------- pieces

/**
 * One thing you can pick.
 *
 * The same `.opt` row the web draws: a mark, a label and a line of
 * help, at the height of a button rather than the height of a card.
 *
 * What is chosen is said by a filled tick as well as by a colour,
 * because colour on its own is not a signal everybody can read — the
 * web asserts that and so does the Android suite. `selected` in the
 * semantics tree is what `aria-pressed` is in the DOM, so the same
 * fact is checkable on both.
 */
@Composable
private fun Choice(label: String, help: String?, on: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (on) AccentDim else Bg2, Radius)
            .border(1.dp, if (on) Accent else Line2, Radius)
            .clickable(onClick = click)
            .semantics(mergeDescendants = true) { selected = on; role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `.opt-mark`: the ring is drawn whether or not it is filled,
        // so an unpicked option still looks like something you pick
        // and the label does not shift when the tick arrives. Filled
        // and dark-ticked when it is on — a shape and a lightness
        // step, not a change of colour.
        Box(
            Modifier.size(19.dp)
                .background(if (on) Accent else Color.Transparent, CircleShape)
                .border(1.5.dp, if (on) Accent else Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Line("✓", c(Design.ON_ACCENT), Design.TINY, FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Line(label, if (on) Accent2 else Ink, 15)
            help?.let { Line(it, Ink3, Design.MINI) }
        }
    }
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
            // The web states the direction and the count as tags and
            // leaves only the timestamp and the owner as prose.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Line(e.at.replace('T', ' ').take(16), Ink3, Design.MINI)
                Tag(e.direction, if (e.isAdd) Ok else Bad)
                Line(e.owner, Ink, Design.MINI)
                Tag("${e.count}")
                Ghost("Reuse") { onReuse(e) }
            }
        }
        Ghost("Clear", onClick = onClear)
    }
}

/**
 * `.tally`: three figures, as a grid of cells.
 *
 * The website draws these as a ruled, bordered block of equal cells —
 * a big mono number over a small uppercase label, hairlines between.
 * This was three loose columns bunched against the left margin with
 * nothing around them, which reads as a row of stray numbers rather
 * than as the summary of what is in the box.
 */
@Composable
private fun Tally(vararg cells: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth()
            // The gridlines are the background showing through the
            // 1dp gaps between cells, which is what `gap: 1px` over a
            // `--line` ground does on the web.
            .background(Line, RadiusSm)
            .border(1.dp, Line, RadiusSm)
            .clip(RadiusSm),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.forEach { (value, label) ->
            Column(
                Modifier.weight(1f).background(Bg2).padding(vertical = 9.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    value,
                    color = Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Line(label.uppercase(), Ink3, Design.TINY)
            }
        }
    }
}

/**
 * `.wiz-foot`: every step ends the same way, in the same place.
 *
 * Ruled off from the body above it, and the reason a button is dead
 * on its own full-width line underneath — which is where the web puts
 * it, and where it is not mistaken for part of the button.
 */
@Composable
private fun Foot(hint: String? = null, buttons: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { buttons() }
        hint?.let { Line(it, Ink, Design.SMALL) }
    }
}
