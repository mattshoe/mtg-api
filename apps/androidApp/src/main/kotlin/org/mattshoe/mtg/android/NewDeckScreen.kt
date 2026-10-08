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
import org.mattshoe.mtg.core.Source

/**
 * The new deck wizard, on Android, in the entry wizard's shape.
 *
 * It was an `AlertDialog`: a stepper of outlined chips, a scrolling
 * body capped at 460dp, and the way on living in the dialog's
 * confirm slot where the Cancel button sat beside it. Matt: "I like
 * the format of the entry flow, so make sure the new deck flow
 * matches that style exactly."
 *
 * So it is a page now, built out of the same pieces the entry
 * wizard is built out of — `WizardSteps`, `Panel`, `Choice`, `Foot`
 * — which live in `Theme.kt` precisely so "exactly" is something
 * the compiler keeps true rather than something two files have to
 * agree about by hand.
 *
 * Creating a deck moves real cards — it pulls from bulk and records
 * what bulk cannot cover as bought — so every gate on the way is
 * `NewDeck` in the shared core rather than anything decided here.
 */
@Composable
fun NewDeckScreen(
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
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(blurb(state), fontSize = 14.sp)

        val steps = state.steps
        WizardSteps(
            labels = steps.map { it.label },
            at = steps.indexOf(state.step).let { if (it == -1) steps.size else it },
            canGo = { i -> state.reachable(steps[i]) },
        ) { i -> onState(state.goTo(steps[i])) }

        when {
            state.busy != null -> Panel(head = "Working") { Line(state.busy!!) }
            state.step == DeckStep.FORMAT -> FormatStep(state, onState, onClose)
            state.step == DeckStep.NAME -> NameStep(state, onState)
            state.step == DeckStep.COMMANDER -> CommanderStep(state, onState, onCommanderTyped)
            state.step == DeckStep.CARDS -> CardsStep(state, onState, onPickFile)
            state.step == DeckStep.CHECK -> CheckStep(state, onState, onCheck)
            state.step == DeckStep.REVIEW -> ReviewStep(state, onState, onCreate)
            state.step == DeckStep.DONE -> DoneStep(state, onClose)
        }

        // `ErrBox` and not a bare line, which is what the website
        // draws and what the dialog drew before this was a page. The
        // tinted box with an edge round it is the only thing marking
        // a failure as a failure for somebody who cannot see the
        // tone, so it survives the restyle.
        state.error?.let { ErrBox(it) }
    }
}

/** The one line under the bar, the way the entry wizard opens. */
private fun blurb(s: NewDeck): String = when (s.step) {
    DeckStep.DONE -> "Created. It is in the deck list now."
    DeckStep.REVIEW -> "What will happen to every card, before anything happens to any of them."
    else -> "A deck, built from a list and checked before anything moves."
}

/** Back to the step behind this one, or out if there is none. */
@Composable
private fun DeckFoot(
    s: NewDeck,
    onState: (NewDeck) -> Unit,
    onClose: () -> Unit,
    hint: String? = null,
    forward: @Composable () -> Unit,
) {
    Foot(hint = hint) {
        val back = s.previousStep
        if (back == null) {
            Ghost("Cancel", onClick = onClose)
        } else {
            Ghost("← Back") { onState(s.goTo(back)) }
        }
        forward()
    }
}

@Composable
private fun Next(s: NewDeck, onState: (NewDeck) -> Unit, enabled: Boolean, to: DeckStep) {
    Primary("Continue →", enabled) { onState(s.goTo(to)) }
}

@Composable
private fun FormatStep(s: NewDeck, onState: (NewDeck) -> Unit, onClose: () -> Unit) {
    Panel(head = "Which format?") {
        // Rows, not chips. `Choice` is the entry wizard's option and
        // it carries its own help line, which is where the deck size
        // belongs — it was only ever in the Cards step's small print
        // before, three screens after the question that decides it.
        Format.entries.forEach { f ->
            Choice(
                f.label,
                "${f.size} cards" + if (f.wantsCommander) ", with a commander" else "",
                s.format == f,
            ) { onState(s.pick(f)) }
        }
        DeckFoot(
            s,
            onState,
            onClose,
            hint = if (!s.canLeaveFormat) "Nothing is preselected on purpose." else null,
        ) {
            Next(s, onState, s.canLeaveFormat, DeckStep.NAME)
        }
    }
}

@Composable
private fun NameStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Panel(
        head = "What is it called?",
        note = if (s.name.isNotBlank()) "Any name. Another collection may have a deck called the same." else null,
    ) {
        OutlinedTextField(
            value = s.name,
            onValueChange = { onState(s.rename(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Deck name") },
        )
        DeckFoot(s, onState, {}) {
            Next(
                s,
                onState,
                s.canLeaveName,
                if (s.needsCommander) DeckStep.COMMANDER else DeckStep.CARDS,
            )
        }
    }
}

@Composable
private fun CommanderStep(s: NewDeck, onState: (NewDeck) -> Unit, onTyped: (Completion) -> Unit) {
    Panel(
        head = "Who leads it?",
        note = "A ${s.format?.label} deck needs one, and the server checks it too.",
    ) {
        // The same suggestion field the Library uses, and the same one
        // the web wizard uses. This was a bare text box, so the one
        // name in the whole wizard that has to be spelt exactly right
        // was the one name with no help spelling it.
        AutocompleteField(
            label = "e.g. Alela, Artful Provocateur",
            state = s.hint,
            onState = { c ->
                onState(s.hinting(c))
                onTyped(c)
            },
            onPick = { name -> onState(s.setCommander(name)) },
            // `closed()` rather than a rebuilt one, so a commander
            // half-typed into the box survives the list going away.
            onDismiss = { onState(s.hinting(s.hint.closed())) },
        )
        DeckFoot(s, onState, {}) { Next(s, onState, s.canLeaveCommander, DeckStep.CARDS) }
    }
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit, onPickFile: () -> Unit) {
    val wanted = s.format?.size ?: 0
    Panel(
        head = "The decklist",
        note = "one card per line" + if (wanted > 0) " · ${s.format!!.label} wants $wanted" else "",
    ) {
        OutlinedTextField(
            value = s.list,
            onValueChange = { onState(s.type(it)) },
            // The entry wizard's box, at the entry wizard's height.
            // It was 150dp because a dialog body capped at 460dp had
            // nowhere else to put it; a page has the room.
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        Tally(
            "${s.cardCount}" to if (s.cardCount == 1) "card" else "cards",
            "$wanted" to "wanted",
        )
        // A deck you already have written down is in a file. Reading
        // one only fills the box — it never submits and never moves a
        // step, the same as the web and the same as mass entry.
        Btn("Upload a file", onClick = onPickFile)
        if (s.needsCommander) {
            // Every decklist export puts the commander first, so
            // cutting it out by hand and retyping it is work the list
            // has already done.
            DeckList.firstCard(s.list)?.let { first ->
                Btn("First card is the commander (${first.name})") {
                    onState(s.commanderFromList())
                }
            }
        }
        DeckFoot(
            s,
            onState,
            {},
            hint = if (!s.canLeaveCards) "Paste a list, or drop a file on the box." else null,
        ) {
            Next(s, onState, s.canLeaveCards, DeckStep.CHECK)
        }
    }
}

@Composable
private fun CheckStep(s: NewDeck, onState: (NewDeck) -> Unit, onCheck: () -> Unit) {
    val v = s.checked
    Panel(head = "Are they real cards?", note = "${s.cardCount} names") {
        when {
            v == null -> Line(
                "Every name is checked against the collection first, then Scryfall.",
                Ink2,
                Design.SMALL,
            )
            // `.tag.ok` / `.tag.bad`: the verdict is a tag, a stated
            // fact with an edge round it. Which verdict it is is
            // carried by the words — "real cards" against "not found"
            // — because the two tones are a hue apart and that is no
            // use to the person who reads this.
            v.ok -> Tag("All ${v.checked} names are real cards", Ok)
            else -> {
                Tag("${v.unknown} not found", Bad)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    v.bad.forEach { BadChip(it.name) }
                }
                if (v.suggestions.isNotEmpty()) Line("Tap one to use it", Ink3, Design.MINI)
                v.suggestions.forEach { (wrong, right) ->
                    // `correct` rather than a rewrite of the list,
                    // because the commander lives in its own field.
                    Btn("$wrong → $right") { onState(s.correct(wrong, right)) }
                }
            }
        }
        // The check itself, beside the verdict rather than instead of
        // the way on. A list that checked out can still be checked
        // again.
        Btn(if (v == null) "Check the names" else "Check again", enabled = s.busy == null) {
            onCheck()
        }
        DeckFoot(
            s,
            onState,
            {},
            hint = if (!s.canLeaveCheck) "Every name has to be found before anything moves." else null,
        ) {
            Next(s, onState, s.canLeaveCheck, DeckStep.REVIEW)
        }
    }
}

@Composable
private fun ReviewStep(s: NewDeck, onState: (NewDeck) -> Unit, onCreate: () -> Unit) {
    val plan = s.plan
    val adding = s.adding
    Panel(
        head = "Ready?",
        note = "${s.format?.label} · ${s.cardCount} cards" +
            if (s.commander.isNotBlank()) " · ${s.commander}" else "",
    ) {
        // Two figures, the way the website draws them: the number big
        // and what it counts small underneath. As one sentence it was
        // the same words and none of the glance.
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
        // Every line, not the first two hundred of them. A list
        // longer than the cap silently lost rows off the bottom,
        // which on the one screen whose job is to say what will
        // happen to each card is the worst place to be approximate.
        plan.forEach { line -> PlanRow(line) }
        if (adding.isNotEmpty()) {
            Line(
                "${adding.size} card${if (adding.size == 1) "" else "s"} " +
                    "the collection does not hold yet will be added to bulk.",
                Ink3,
                Design.MINI,
            )
        }
        DeckFoot(s, onState, {}, hint = "This is the press that moves cards.") {
            Primary("Create ${s.name}", s.canCreate, onCreate)
        }
    }
}

/** What the web's `DoneStep` says: created, and where it lives. */
@Composable
private fun DoneStep(s: NewDeck, onClose: () -> Unit) {
    Panel(head = "Created") {
        Tag("Created", Ok)
        Line("${s.name} is at #/decks/${s.key}", Ink2, Design.SMALL)
        Foot { Primary("Done", onClick = onClose) }
    }
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
