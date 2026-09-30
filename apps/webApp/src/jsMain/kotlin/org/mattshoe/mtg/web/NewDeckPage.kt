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
                    state.step == DeckStep.COMMANDER -> CommanderStep(state, onState)
                    state.step == DeckStep.CARDS -> CardsStep(state, onState)
                    state.step == DeckStep.CHECK -> CheckStep(state, onState, onCheck)
                    state.step == DeckStep.REVIEW -> ReviewStep(state, onState, onCreate)
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
    Div(attrs = { classes("flex-wrap") }) {
        Format.entries.forEach { f ->
            Button(attrs = {
                classes("owner-opt")
                if (s.format == f) classes("on")
                onClick { onState(s.pick(f)) }
            }) { Text(f.label) }
        }
    }
    Next("Continue →", s.canLeaveFormat) { onState(s.goTo(DeckStep.OWNER)) }
}

@Composable
private fun OwnerStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Div(attrs = { classes("owner-pick") }) {
        Owner.entries.forEach { o ->
            Button(attrs = {
                classes("owner-opt")
                if (s.owner == o) classes("on")
                onClick { onState(s.assign(o)) }
            }) { Text(o.label) }
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
private fun CommanderStep(s: NewDeck, onState: (NewDeck) -> Unit) {
    Input(type = InputType.Text) {
        classes("field")
        placeholder("e.g. Alela, Artful Provocateur")
        value(s.commander)
        onInput { onState(s.setCommander(it.value)) }
    }
    Div(attrs = { classes("muted", "small") }) {
        Text("A ${s.format?.label} deck needs one, and the server checks it too.")
    }
    Next("Continue →", s.canLeaveCommander) { onState(s.goTo(DeckStep.CARDS)) }
}

@Composable
private fun CardsStep(s: NewDeck, onState: (NewDeck) -> Unit) {
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
                Div(attrs = { classes("small", "muted") }) { Text("Did you mean") }
                Div(attrs = { classes("chips") }) {
                    v.suggestions.forEach { (wrong, right) ->
                        Button(attrs = {
                            classes("chip", "mini")
                            // Fixing it in the box, because retyping a
                            // name the server already spelled for you is
                            // the kind of busywork that makes people
                            // skip the check.
                            onClick { onState(s.type(s.list.replace(wrong, right))) }
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
private fun ReviewStep(s: NewDeck, onState: (NewDeck) -> Unit, onCreate: () -> Unit) {
    Div(attrs = { classes("muted", "small") }) {
        Text(
            "${s.format?.label} · ${s.owner?.label} · ${s.cardCount} cards" +
                if (s.commander.isNotBlank()) " · ${s.commander}" else "",
        )
    }
    Div(attrs = { classes("small") }) {
        Text("Where each card comes from. Nothing is created until every line has an answer.")
    }
    Div(attrs = { classes("table-wrap") }) {
        s.undecided.take(60).forEach { line ->
            Div(attrs = { classes("flex-wrap", "small") }) {
                Span(attrs = { classes("t-name") }) { Text(line) }
                Source.entries.forEach { src ->
                    Button(attrs = {
                        classes("btn", "sm", "ghost")
                        onClick { onState(s.source(line, src)) }
                    }) { Text(src.label) }
                }
            }
        }
    }
    if (s.undecided.isEmpty()) {
        Div(attrs = { classes("tag", "ok") }) { Text("Every line has a source") }
    } else {
        Div(attrs = { classes("muted", "small") }) { Text("${s.undecided.size} still undecided") }
        Div(attrs = { classes("flex-wrap") }) {
            Source.entries.forEach { src ->
                Button(attrs = {
                    classes("btn", "sm", "ghost")
                    onClick { onState(s.undecided.fold(s) { acc, line -> acc.source(line, src) }) }
                }) { Text("All ${src.label.lowercase()}") }
            }
        }
    }
    if (s.buying.isNotEmpty()) {
        Div(attrs = { classes("small") }) {
            Span(attrs = { classes("tag", "warn") }) { Text("buying") }
            Text(" ${s.buying.size} line${if (s.buying.size == 1) "" else "s"} will be added to the collection")
        }
    }
    Next("Create ${s.name}", s.canCreate, onCreate)
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
