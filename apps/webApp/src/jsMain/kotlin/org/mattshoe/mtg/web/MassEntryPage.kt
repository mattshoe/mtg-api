package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Input
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.HistoryEntry
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

    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("page-head") }) {
            H1 { Text("Mass entry") }
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
    Panel("Adding or removing?", note = if (s.cardCount > 0) "${s.cardCount} cards already in the box" else null) {
        Div(attrs = { classes("owner-pick") }) {
            Choice("Add to the collection", s.direction == Direction.ADD) { pick(Direction.ADD) }
            Choice("Remove from the collection", s.direction == Direction.REMOVE) { pick(Direction.REMOVE) }
        }
        Div(attrs = { classes("flex-wrap") }) {
            Primary(if (s.canLeaveWhich) "Continue →" else "Pick one to continue", s.canLeaveWhich, next)
            if (!s.canLeaveWhich) {
                Span(attrs = { classes("small", "says") }) { Text("Nothing is preselected on purpose.") }
            }
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
    val over = if (s.overLimit) " — over the ${MassEntry.MAX_CARDS} limit" else ""
    Panel(s.direction!!.question, note = "${s.cardCount} cards$kind$over") {
        TextArea(value = s.list, attrs = {
            classes("field")
            rows(14)
            onInput { type(it.value) }
        })
        FileDrop(onFiles)
        Div(attrs = { classes("flex-wrap") }) {
            Ghost("← Back") { go(Step.WHICH) }
            Primary("Continue →", s.canLeaveList) { go(Step.WHO) }
        }
    }
}

@Composable
private fun WhoStep(s: MassEntry, assign: (Owner) -> Unit, go: (Step) -> Unit, preview: () -> Unit) {
    Panel("Whose collection?", note = "${s.cardCount} cards on the list") {
        Div(attrs = { classes("owner-pick") }) {
            Owner.entries.forEach { o -> Choice(o.label, s.owner == o) { assign(o) } }
        }
        Div(attrs = { classes("flex-wrap") }) {
            Ghost("← Back") { go(Step.LIST) }
            Primary(
                if (s.canPreview) "Preview changes · ${s.owner!!.slug} →" else "Pick one to continue",
                s.canPreview,
                preview,
            )
            if (!s.canPreview) {
                Span(attrs = { classes("small", "says") }) { Text("Pick whose collection this goes to.") }
            }
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
            Div { Text("${p.resolved} resolved, ${p.failed} failed, ${p.changes.size} printings") }
            Pre(attrs = { classes("out") }) {
                Text(p.changes.joinToString("\n") {
                    "${it.name} (${it.set} ${it.collectorNumber}) ${it.before} → ${it.after}"
                })
            }
            if (p.errors.isNotEmpty()) {
                Div(attrs = { classes("err") }) { Text(p.errors.joinToString("\n")) }
            }
        }
        Div(attrs = { classes("flex-wrap") }) {
            Ghost("← Back") { go(Step.WHO) }
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
private fun DoneStep(s: MassEntry, again: () -> Unit) {
    val r = s.result!!
    Panel(if (r.applied) "Applied" else "Nothing applied") {
        Div { Text("${r.resolved} resolved, ${r.failed} failed, ${r.changes.size} printings") }
        if (r.errors.isNotEmpty()) Div(attrs = { classes("err") }) { Text(r.errors.joinToString("\n")) }
        Div(attrs = { classes("flex-wrap") }) { Primary("Enter more", true, again) }
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

@Composable
private fun Choice(label: String, on: Boolean, click: () -> Unit) {
    Button(attrs = {
        classes("owner-opt")
        if (on) classes("on")
        onClick { click() }
    }) { Text(label) }
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
@Composable
private fun FileDrop(onFiles: (List<File>) -> Unit) {
    Div(attrs = {
        classes("dropzone")
        onDragOver { it.preventDefault() }
        onDrop { e ->
            e.preventDefault()
            onFiles(e.dataTransfer?.files.toList())
        }
    }) {
        Input(type = InputType.File) {
            // Hidden by being tiny and transparent, NOT by
            // `display: none`. Android Chrome will not open a picker for
            // an input that is not rendered, so a display:none input is
            // a button that does nothing at all.
            classes("file-in")
            attr("accept", "*/*")
            attr("multiple", "")
            onChange { e ->
                val el = e.target
                onFiles(el.files.toList())
                el.value = ""
            }
        }
        Span(attrs = { classes("dz-icon") }) { Text("⤓") }
        Div {
            Div(attrs = { classes("dz-main") }) { Text("Upload a file") }
            Div(attrs = { classes("dz-sub", "small", "muted") }) { Text("or drop one here") }
        }
    }
}

private fun FileList?.toList(): List<File> =
    if (this == null) emptyList() else (0 until length).mapNotNull { item(it) }

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
