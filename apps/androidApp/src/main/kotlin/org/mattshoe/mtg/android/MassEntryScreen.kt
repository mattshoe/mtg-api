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
    /** The first question's third answer. See `Which`. */
    onNewDeck: () -> Unit = {},
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
                null -> if (state.startingADeck) {
                    "A deck, built from a list and checked before anything moves."
                } else {
                    "Cards in or cards out, or a whole new deck."
                }
                Direction.ADD -> "Resolved against Scryfall, then written to the collection."
                Direction.REMOVE -> "Matched against printings you already own."
            },
            fontSize = 14.sp,
        )

        WizardSteps(
            labels = Step.wizard.map { it.label },
            at = Step.wizard.indexOf(state.step).let { if (it == -1) Step.wizard.size else it },
            canGo = { i -> state.reachable(Step.wizard[i]) },
        ) { i -> onState(state.goTo(Step.wizard[i])) }

        when {
            // "Working", headed, the same as the web — a panel with no
            // head is not the same screen as one that says what it is.
            state.busy != null -> Panel(head = "Working") { Line(state.busy!!) }
            state.step == Step.WHICH -> Which(state, onState, onNewDeck)
            state.step == Step.LIST -> {
                ListStep(state, onState, onPreview, onPickFile)
                // The history table puts an old list back in the box, so
                // it belongs on the step that has the box.
                HistoryPanel(history, onReuse, onClearHistory)
            }
            state.step == Step.REVIEW -> Review(state, onState, onApply)
            state.step == Step.DONE -> Done(state) { onState(state.again()) }
        }

        state.error?.let { Text(it, fontSize = 14.sp) }
    }
}

@Composable
private fun Which(s: MassEntry, onState: (MassEntry) -> Unit, onNewDeck: () -> Unit) {
    // Cards, not lines. "4 Lightning Bolt" is four cards already in
    // the box, and `cardCount` counts the line — which is the number
    // the request size is limited by and not the one to say here.
    Panel(
        head = "What are you doing?",
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
        // The third answer. It used to live as a button on the decks
        // list, which is the one screen you are on when you already
        // have the decks — Matt: "On the entry screen, we need a new
        // option 'new deck' that launches the new deck flow. Then get
        // rid of the one on the decks list page."
        Choice(
            "New deck",
            "Build one from a list, checked against the collection.",
            s.startingADeck,
        ) { onState(s.startADeck()) }
        // One label, enabled or not. A button whose words change is a
        // different button, and the web's says "Continue →" either way.
        // Where it goes is the answer's business, not the button's.
        Foot(hint = if (!s.canContinue) "Nothing is preselected on purpose." else null) {
            Primary("Continue →", s.canContinue) {
                if (s.startingADeck) onNewDeck() else onState(s.goTo(Step.LIST))
            }
        }
    }
}

@Composable
private fun ListStep(
    s: MassEntry,
    onState: (MassEntry) -> Unit,
    preview: () -> Unit,
    onPickFile: () -> Unit,
) {
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
        // said out loud rather than assumed — that one more step
        // stands between this box and the collection.
        Foot(
            hint = when {
                !s.canLeaveList && s.tally.cards == 0 -> "Paste a list, or drop a file on the box."
                s.unsaved -> "Nothing is written until you press the button on the last step."
                else -> null
            },
        ) {
            Ghost("← Back") { onState(s.goTo(Step.WHICH)) }
            // The step after this one asked whose collection the list
            // was going to, and asked for the dry run from there. The
            // list is the last thing anybody has to say, so the dry
            // run is asked for from here.
            Primary("Preview changes →", s.canPreview, preview)
        }
    }
}

@Composable
private fun Review(s: MassEntry, onState: (MassEntry) -> Unit, apply: () -> Unit) {
    val p = s.preview
    Panel(head = "Preview — nothing written yet", note = "${s.tally.cards} cards on the list") {
        if (p == null) Text("No preview yet.") else Outcome(p)
        Foot {
            Ghost("← Back") { onState(s.goTo(Step.LIST)) }
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
    Panel(head = if (moved) "Applied" else "Nothing applied") {
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
