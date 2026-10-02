package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Input
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Step
import org.w3c.files.File
import org.w3c.files.FileList

/**
 * The mass entry wizard, in the browser.
 *
 * Every rule about what is reachable comes from `MassEntry` in the shared
 * core — which step is next, whether Apply may be offered at all, what a
 * back button throws away. This file decides what it looks like and
 * nothing else, which is what keeps it from drifting away from Android.
 *
 * The class names are the ones the existing stylesheet already defines,
 * so the two renderings look the same without a second stylesheet to
 * keep in step.
 */
@Composable
fun MassEntryPage(
    state: MassEntry,
    onState: (MassEntry) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    history: EntryHistory = EntryHistory(),
    onFiles: (List<File>) -> Unit = {},
    onReuse: (HistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    // State is hoisted, the same as every other screen and the same as
    // the Android sibling. The shell owns it, so a share can put a list
    // in before this is ever composed.
    val s = state

    Div(attrs = { classes("wrap", "wizard") }) {
        Div(attrs = { classes("page-head") }) {
            Span(attrs = { classes("sub") }) {
                Text(
                    when (s.direction) {
                        null -> "Cards in or cards out, from a list or a file."
                        Direction.ADD -> "Resolved against Scryfall, then written to the collection."
                        Direction.REMOVE -> "Matched against printings you already own."
                    },
                )
            }
        }

        Stepper(s) { target -> onState(s.goTo(target)) }

        when {
            s.busy != null -> Panel("Working") { Text(s.busy!!) }
            s.step == Step.WHICH -> WhichStep(s, { onState(s.choose(it)) }, { onState(s.goTo(Step.LIST)) })
            s.step == Step.LIST -> {
                ListStep(s, { onState(s.type(it)) }, { onState(s.goTo(it)) }, onFiles)
                // The history table puts an old list back in the box, so
                // it belongs on the step that has the box.
                HistoryPanel(history, onReuse, onClearHistory)
            }
            s.step == Step.WHO -> WhoStep(s, { onState(s.assign(it)) }, { onState(s.goTo(it)) }, onPreview)
            s.step == Step.REVIEW -> ReviewStep(s, { onState(s.goTo(it)) }, onApply)
            s.step == Step.DONE -> DoneStep(s) { onState(s.again()) }
        }

        s.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
    }
}

@Composable
private fun Stepper(s: MassEntry, go: (Step) -> Unit) {
    Div(attrs = { classes("steps") }) {
        Step.wizard.forEachIndexed { i, step ->
            val at = Step.wizard.indexOf(s.step).let { if (it == -1) Step.wizard.size else it }
            val done = i < at
            Button(attrs = {
                classes("step")
                if (i == at) classes("on")
                if (done) classes("done")
                // Reachability is the core's call, not a guess from the index.
                if (!done || !s.reachable(step)) disabled()
                onClick { go(step) }
            }) {
                Span(attrs = { classes("step-n") }) { Text(if (done) "✓" else "${i + 1}") }
                Text(step.label)
            }
        }
    }
}

@Composable
private fun WhichStep(s: MassEntry, pick: (Direction) -> Unit, next: () -> Unit) {
    Panel(
        "Adding or removing?",
        note = if (s.tally.cards > 0) "${s.tally.cards} cards already in the box" else null,
    ) {
        Div(attrs = { classes("pick") }) {
            Choice(
                "Add to the collection",
                "Cards you bought, opened or were given.",
                s.direction == Direction.ADD,
            ) { pick(Direction.ADD) }
            Choice(
                "Remove from the collection",
                "Cards you sold, traded away or lost.",
                s.direction == Direction.REMOVE,
            ) { pick(Direction.REMOVE) }
        }
        Foot {
            Primary("Continue →", s.canLeaveWhich, next)
            if (!s.canLeaveWhich) Hint("Nothing is preselected on purpose.")
        }
    }
}

@Composable
private fun ListStep(
    s: MassEntry,
    type: (String) -> Unit,
    go: (Step) -> Unit,
    onFiles: (List<File>) -> Unit,
) {
    val kind = if (s.isCsv) " · CSV" else ""
    val over = if (s.overLimit) " — over the ${MassEntry.MAX_CARDS} line limit" else ""
    Panel(s.direction!!.question, note = "${s.tally.lines} lines$kind$over") {
        TextArea(value = s.list, attrs = {
            classes("field")
            rows(14)
            onInput { type(it.value) }
        })
        Counts(s)
        FileDrop(onFiles)
        Foot {
            Ghost("← Back") { go(Step.WHICH) }
            Primary("Continue →", s.canLeaveList)  { go(Step.WHO) }
            if (!s.canLeaveList && s.tally.cards == 0) {
                Hint("Paste a list, or drop a file on the box.")
            } else if (s.unsaved) {
                // Said out loud rather than assumed: two more steps
                // before anything reaches the collection.
                Hint("Nothing is written until you press the button on the last step.")
            }
        }
    }
}

@Composable
private fun WhoStep(s: MassEntry, assign: (Owner) -> Unit, go: (Step) -> Unit, preview: () -> Unit) {
    Panel("Whose collection?", note = "${s.tally.cards} cards on the list") {
        Div(attrs = { classes("pick") }) {
            Owner.entries.forEach { o -> Choice(o.label, null, s.owner == o) { assign(o) } }
        }
        Foot {
            Ghost("← Back") { go(Step.LIST) }
            Primary("Preview changes →", s.canPreview, preview)
            if (!s.canPreview) Hint("Pick whose collection this goes to.")
        }
    }
}

@Composable
private fun ReviewStep(s: MassEntry, go: (Step) -> Unit, apply: () -> Unit) {
    val p = s.preview
    Panel("Preview — nothing written yet", note = s.owner?.slug) {
        if (p == null) {
            Text("No preview yet.")
        } else {
            Outcome(p)
        }
        Foot {
            Ghost("← Back") { go(Step.WHO) }
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
 * made.
 */
@Composable
private fun DoneStep(s: MassEntry, again: () -> Unit) {
    val r = s.result!!
    val moved = r.applied && r.changes.isNotEmpty()
    Panel(if (moved) "Applied" else "Nothing applied", note = s.owner?.slug) {
        Outcome(r)
        Foot { Primary("Enter more", true, again) }
    }
}

// ------------------------------------------------------------- pieces

@Composable
private fun Panel(title: String, note: String? = null, body: @Composable () -> Unit) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text(title) }
            Span(attrs = { classes("spacer") }) {}
            note?.let { Span(attrs = { classes("muted", "small") }) { Text(it) } }
        }
        Div(attrs = { classes("panel-body") }) { body() }
    }
}

/**
 * One thing you can pick.
 *
 * A row rather than a slab: a mark, a label and a line of help, at
 * the height of a button instead of the height of a card. What is
 * chosen is said by a filled mark as well as by colour, because
 * colour alone is not a signal everybody can read.
 */
@Composable
private fun Choice(label: String, help: String?, on: Boolean, click: () -> Unit) {
    Button(attrs = {
        classes("opt")
        if (on) classes("on")
        attr("aria-pressed", on.toString())
        onClick { click() }
    }) {
        Span(attrs = { classes("opt-mark") }) { if (on) Text("✓") }
        Span(attrs = { classes("opt-text") }) {
            Span(attrs = { classes("opt-label") }) { Text(label) }
            help?.let { Span(attrs = { classes("opt-help") }) { Text(it) } }
        }
    }
}

/** The row of buttons that ends a step, always in the same place. */
@Composable
private fun Foot(body: @Composable () -> Unit) {
    Div(attrs = { classes("wiz-foot") }) { body() }
}

/** Why the button beside it is not doing anything yet. */
@Composable
private fun Hint(text: String) {
    Span(attrs = { classes("small", "says") }) { Text(text) }
}

@Composable
private fun Primary(label: String, enabled: Boolean, click: () -> Unit) {
    Button(attrs = {
        classes("btn", "primary")
        if (!enabled) disabled()
        onClick { click() }
    }) { Text(label) }
}

@Composable
private fun Ghost(label: String, click: () -> Unit) {
    Button(attrs = {
        classes("btn", "ghost")
        onClick { click() }
    }) { Text(label) }
}

/**
 * A file into the list box.
 *
 * Reading one only fills the box — it never submits and never advances
 * a step. The input accepts everything: a narrow list greys out the
 * file you actually want in Android's picker, and the same guesswork
 * about MIME types that broke the share sheet would break this too.
 */

/** What was entered recently, and putting it back in the box. */
@Composable
private fun HistoryPanel(
    history: EntryHistory,
    onReuse: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    if (history.isEmpty) return
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("Recent") }
            Span(attrs = { classes("spacer") }) {}
            Button(attrs = {
                classes("btn", "sm", "ghost")
                onClick { onClear() }
            }) { Text("Clear") }
        }
        Div(attrs = { classes("panel-body") }) {
            history.recent.forEach { e ->
                Div(attrs = { classes("flex-wrap", "small") }) {
                    Span(attrs = { classes("muted", "nowrap") }) { Text(e.at.replace('T', ' ').take(16)) }
                    Span(attrs = { classes("tag", "mini", if (e.isAdd) "ok" else "bad") }) {
                        Text(e.direction)
                    }
                    Span { Text(e.owner) }
                    Span(attrs = { classes("tag", "mini") }) { Text("${e.count}") }
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        onClick { onReuse(e) }
                    }) { Text("Reuse") }
                }
            }
        }
    }
}

/**
 * What is in the box, counted two ways.
 *
 * A line count is what the request size is limited by and is not the
 * number anybody pasting a deck wants: "4 Lightning Bolt" is four
 * cards on one line, and the same card written twice with different
 * set codes is one card you own two of.
 */
@Composable
private fun Counts(s: MassEntry) {
    val t = s.tally
    Div(attrs = { classes("tally") }) {
        Figure("${t.cards}", if (t.cards == 1) "card" else "cards")
        Figure("${t.unique}", if (t.unique == 1) "unique" else "unique")
        Figure("${t.lines}", if (t.lines == 1) "line" else "lines")
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Div(attrs = { classes("tally-cell") }) {
        Div(attrs = { classes("tally-n") }) { Text(value) }
        Div(attrs = { classes("tally-l") }) { Text(label) }
    }
}

/**
 * What the server says it did, or would do.
 *
 * This was one `<pre>` of 248 lines and nobody can check a wall of
 * text on a phone. It is a row per printing now: the card and where
 * it is from on the left, how many moved on the right, with the
 * count at the top so the whole thing can be sanity-checked without
 * reading any of it.
 */
@Composable
private fun Outcome(r: Applied) {
    Div(attrs = { classes("tally") }) {
        Figure("${r.changes.size}", if (r.changes.size == 1) "printing" else "printings")
        Figure("${r.copies}", if (r.copies == 1) "copy" else "copies")
        Figure("${r.fresh}", "new")
    }

    if (r.failed > 0) {
        Div(attrs = { classes("tag", "bad") }) { Text("${r.failed} could not be resolved") }
    }

    Div(attrs = { classes("changes") }) {
        r.changes.forEach { c ->
            Div(attrs = { classes("chg") }) {
                // The art says which card faster than the name does,
                // which is the whole point of scanning 248 of these.
                Div(attrs = { classes("chg-thumb") }) {
                    c.art?.let { url ->
                        Img(src = url, alt = "", attrs = {
                            attr("loading", "lazy")
                            attr("decoding", "async")
                        })
                    }
                }
                Div(attrs = { classes("chg-what") }) {
                    Div(attrs = { classes("chg-name") }) { Text(c.name) }
                    Div(attrs = { classes("chg-where") }) {
                        Span(attrs = { classes("mono") }) { Text("${c.set.uppercase()} ${c.collectorNumber}") }
                        if (c.finish != "nonfoil") {
                            Span(attrs = { classes("tag", "mini") }) { Text(c.finish) }
                        }
                        if (c.isNew) Span(attrs = { classes("tag", "mini", "ok") }) { Text("new") }
                        if (c.isGone) Span(attrs = { classes("tag", "mini", "warn") }) { Text("last one") }
                    }
                }
                Div(attrs = { classes("chg-n") }) {
                    Div(attrs = {
                        classes("chg-delta")
                        if (c.delta < 0) classes("down")
                    }) { Text(c.sign) }
                    Div(attrs = { classes("chg-was") }) { Text("${c.before} → ${c.after}") }
                }
            }
        }
    }

    if (r.errors.isNotEmpty()) {
        Div(attrs = { classes("err") }) {
            r.errors.forEach { Div { Text(it) } }
        }
    }
}
