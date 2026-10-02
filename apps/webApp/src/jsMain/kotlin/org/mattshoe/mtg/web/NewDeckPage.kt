package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckList
import org.w3c.files.File
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Source

/**
 * The new deck wizard, on the web. Sibling of `NewDeckScreen`.
 *
 * Creating a deck moves real cards — it pulls from bulk and records
 * what bulk cannot cover as bought — so every gate on the way is
 * load-bearing, and every one of them is `NewDeck` in the shared core
 * rather than anything decided here.
 */
@Composable
fun NewDeckDialog(
    state: NewDeck,
    onState: (NewDeck) -> Unit,
    onCheck: () -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    onCommanderTyped: (Completion) -> Unit = {},
    onFiles: (List<File>) -> Unit = {},
) {
    Div(attrs = {
        classes("palette-scrim")
        onClick { onClose() }
    }) {
        Div(attrs = {
            classes("palette", "wide")
            onClick { it.stopPropagation() }
        }) {
            Div(attrs = { classes("panel-head") }) {
                H2 { Text("New deck") }
                Span(attrs = { classes("spacer") }) {}
                Button(attrs = {
                    classes("btn", "sm", "ghost", "icon-only")
                    attr("title", "Close")
                    attr("aria-label", "Close")
                    onClick { onClose() }
                }) { CloseIcon() }
            }

            Div(attrs = { classes("panel-body", "stack") }) {
                Stepper(state) { onState(state.goTo(it)) }

                when {
                    state.busy != null -> Div(attrs = { classes("empty") }) { Text(state.busy!!) }
                    state.step == DeckStep.FORMAT -> FormatStep(state, onState)
                    state.step == DeckStep.OWNER -> OwnerStep(state, onState)
                    state.step == DeckStep.NAME -> NameStep(state, onState)
                    state.step == DeckStep.COMMANDER -> CommanderStep(state, onState, onCommanderTyped)
                    state.step == DeckStep.CARDS -> CardsStep(state, onState, onFiles)
                    state.step == DeckStep.CHECK -> CheckStep(state, onState, onCheck)
                    state.step == DeckStep.REVIEW -> ReviewStep(state, onCreate)
                    state.step == DeckStep.DONE -> DoneStep(state, onClose)
                }

                state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
            }
        }
    }
}

@Composable
private fun Stepper(s: NewDeck, go: (DeckStep) -> Unit) {
    Div(attrs = { classes("steps") }) {
        s.steps.forEachIndexed { i, step ->
            Button(attrs = {
                classes("step")
                if (step == s.step) classes("on")
                if (!s.reachable(step)) disabled()
                onClick { go(step) }
            }) {
                Span(attrs = { classes("step-n") }) { Text("${i + 1}") }
                Text(step.label)
            }
        }
    }
}

@Composable
private fun FormatStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Div(attrs = { classes("pick") }) {
        Format.entries.forEach { f ->
            Button(attrs = {
                classes("opt")
                if (s.format == f) classes("on")
                attr("aria-pressed", (s.format == f).toString())
                onClick { onState(s.pick(f)) }
            }) {
                Span(attrs = { classes("opt-mark") }) { if (s.format == f) Text("✓") }
                Span(attrs = { classes("opt-text") }) {
                    Span(attrs = { classes("opt-label") }) { Text(f.label) }
                }
            }
        }
    }
    Next("Continue →", s.canLeaveFormat) { onState(s.goTo(DeckStep.OWNER)) }
}

@Composable
private fun OwnerStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Div(attrs = { classes("pick") }) {
        Owner.entries.forEach { o ->
            Button(attrs = {
                classes("opt")
                if (s.owner == o) classes("on")
                attr("aria-pressed", (s.owner == o).toString())
                onClick { onState(s.assign(o)) }
            }) {
                Span(attrs = { classes("opt-mark") }) { if (s.owner == o) Text("✓") }
                Span(attrs = { classes("opt-text") }) {
                    Span(attrs = { classes("opt-label") }) { Text(o.label) }
                }
            }
        }
    }
    Next("Continue →", s.canLeaveOwner) { onState(s.goTo(DeckStep.NAME)) }
}

@Composable
private fun NameStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Input(type = InputType.Text) {
        classes("field")
        placeholder("Deck name")
        value(s.name)
        onInput { onState(s.rename(it.value)) }
    }
    if (s.name.isNotBlank()) {
        Div(attrs = { classes("muted", "small") }) { Text("It will live at #/decks/${s.slug}") }
    }
    Next("Continue →", s.canLeaveName) {
        onState(s.goTo(if (s.needsCommander) DeckStep.COMMANDER else DeckStep.CARDS))
    }
}

@Composable
private fun CommanderStep(
    s: NewDeck,
    onState: (NewDeck) -> Unit,
    onTyped: (Completion) -> Unit,
) {
    // The same suggestion field the Library uses. This was a bare text
    // box, so the one name in the whole wizard that has to be spelt
    // exactly right was the one name with no help spelling it.
    AutocompleteField(
        hint = "e.g. Alela, Artful Provocateur",
        state = s.hint,
        onState = onTyped,
        onPick = { name -> onState(s.setCommander(name)) },
    )
    Div(attrs = { classes("muted", "small") }) {
        Text("A ${s.format?.label} deck needs one, and the server checks it too.")
    }
    Next("Continue →", s.canLeaveCommander) { onState(s.goTo(DeckStep.CARDS)) }
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit, onFiles: (List<File>) -> Unit) {
    TextArea(value = s.list, attrs = {
        classes("field", "mono")
        rows(16)
        onInput { onState(s.type(it.value)) }
    })
    Div(attrs = { classes("muted", "small") }) {
        val wanted = s.format?.size ?: 0
        Text(
            "${s.cardCount} cards" +
                if (wanted > 0) " · ${s.format!!.label} wants $wanted" else "",
        )
    }
    // A deck you already have written down is in a file, and the only
    // upload on the site used to live on another screen entirely.
    FileDrop(onFiles)
    if (s.needsCommander) {
        val first = DeckList.firstCard(s.list)
        if (first != null) {
            Div(attrs = { classes("flex-wrap") }) {
                Button(attrs = {
                    classes("btn", "sm", "ghost")
                    onClick { onState(s.commanderFromList()) }
                }) { Text("First card is the commander (${first.name})") }
            }
        }
    }
    Next("Continue →", s.canLeaveCards) { onState(s.goTo(DeckStep.CHECK)) }
}

@Composable
private fun CheckStep(s: NewDeck, onState: (NewDeck) -> Unit, onCheck: () -> Unit) {
    val v = s.checked
    when {
        v == null -> Div(attrs = { classes("muted", "small") }) {
            Text("Every name is checked against the collection first, then Scryfall.")
        }
        v.ok -> Div(attrs = { classes("tag", "ok") }) { Text("All ${v.checked} names are real cards") }
        else -> {
            Div(attrs = { classes("tag", "bad") }) { Text("${v.unknown} not found") }
            Div(attrs = { classes("chips") }) {
                v.bad.forEach { Span(attrs = { classes("chip", "mini", "bad") }) { Text(it.name) } }
            }
            if (v.suggestions.isNotEmpty()) {
                Div(attrs = { classes("small", "muted") }) { Text("Tap one to use it") }
                Div(attrs = { classes("chips") }) {
                    v.suggestions.forEach { (wrong, right) ->
                        Button(attrs = {
                            classes("chip", "mini", "fixit")
                            attr("title", "Use \"$right\"")
                            // Fixing it where it is, because retyping a
                            // name the server already spelled for you is
                            // the kind of busywork that makes people
                            // skip the check — and because the
                            // commander is its own field, so a fix that
                            // only rewrote the list did nothing at all
                            // for the one name most likely to be typed
                            // from memory.
                            onClick { onState(s.correct(wrong, right)) }
                        }) { Text("$wrong → $right") }
                    }
                }
            }
        }
    }
    Div(attrs = { classes("flex-wrap") }) {
        Button(attrs = {
            classes("btn", "primary")
            if (s.busy != null) disabled()
            onClick { onCheck() }
        }) { Text(if (v == null) "Check the names" else "Check again") }
        Button(attrs = {
            classes("btn", "ghost")
            if (!s.canLeaveCheck) disabled()
            onClick { onState(s.goTo(DeckStep.REVIEW)) }
        }) { Text("Continue →") }
    }
}

@Composable
private fun ReviewStep(s: NewDeck, onCreate: () -> Unit) {
    // One line per card, and no questions. The wizard used to ask
    // where every single copy should come from and then throw the
    // answers away — `createDeck` has never had a `sources` field.
    // What actually happens is decided by what the collection holds,
    // which the check has already established.
    val plan = s.plan
    val adding = s.adding
    Div(attrs = { classes("muted", "small") }) {
        Text(
            "${s.format?.label} · ${s.owner?.label} · ${s.cardCount} cards" +
                if (s.commander.isNotBlank()) " · ${s.commander}" else "",
        )
    }
    Div(attrs = { classes("tally", "two") }) {
        Figure("${plan.size - adding.size}", "from bulk")
        Figure("${adding.size}", "added to bulk")
    }
    Div(attrs = { classes("table-wrap") }) {
        plan.forEach { line ->
            Div(attrs = { classes("plan-row") }) {
                Span(attrs = { classes("plan-qty") }) { Text("${line.qty}") }
                Span(attrs = { classes("plan-name") }) { Text(line.name) }
                Span(attrs = {
                    classes("plan-from")
                    if (!line.owned) classes("new")
                }) { Text(line.from.label) }
            }
        }
    }
    if (adding.isNotEmpty()) {
        Div(attrs = { classes("muted", "small") }) {
            Text(
                "${adding.size} card${if (adding.size == 1) "" else "s"} " +
                    "the collection does not hold yet will be added to bulk.",
            )
        }
    }
    Next("Create ${s.name}", s.canCreate, onCreate)
}

@Composable
private fun Figure(value: String, label: String) {
    Div(attrs = { classes("tally-cell") }) {
        Div(attrs = { classes("tally-n") }) { Text(value) }
        Div(attrs = { classes("tally-l") }) { Text(label) }
    }
}

@Composable
private fun DoneStep(s: NewDeck, onClose: () -> Unit) {
    Div(attrs = { classes("tag", "ok") }) { Text("Created") }
    Div(attrs = { classes("muted", "small") }) { Text("${s.name} is at #/decks/${s.slug}") }
    Button(attrs = {
        classes("btn", "primary")
        onClick { onClose() }
    }) { Text("Done") }
}

@Composable
private fun Next(label: String, enabled: Boolean, click: () -> Unit) {
    Div(attrs = { classes("flex-wrap") }) {
        Button(attrs = {
            classes("btn", "primary")
            if (!enabled) disabled()
            onClick { click() }
        }) { Text(label) }
    }
}
