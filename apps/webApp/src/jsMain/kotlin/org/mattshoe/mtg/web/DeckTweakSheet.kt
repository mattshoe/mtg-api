package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Tweak

/**
 * One card in, one card out, or one number changed.
 *
 * Three beats, the same as every other write here: say what you
 * want, see what it would do, let it happen. The middle one is not
 * ceremony — putting a card in a deck can mean buying it, and this
 * is where that gets said before it is true.
 */
@Composable
fun DeckTweakSheet(
    state: DeckTweak,
    onState: (DeckTweak) -> Unit,
    onFind: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
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
                H2 { Text(state.kind?.title ?: "Change this card") }
                Span(attrs = { classes("spacer") }) {}
                Span(attrs = { classes("muted", "small") }) { Text(state.deckName) }
                Button(attrs = {
                    classes("btn", "sm", "ghost", "icon-only")
                    attr("title", "Close")
                    attr("aria-label", "Close")
                    onClick { onClose() }
                }) { CloseIcon() }
            }

            Div(attrs = { classes("panel-body", "stack") }) {
                when {
                    state.saved -> Done(state, onClose)
                    state.choosing -> Choose(state, onState)
                    else -> Body(state, onState, onFind, onPreview, onApply)
                }
            }
        }
    }
}

/**
 * What to do with the card that was tapped.
 *
 * The row carries one button rather than three, so this is where the
 * choice lives — as full-width rows a thumb can hit, the same shape
 * the entry flow uses.
 */
@Composable
private fun Choose(state: DeckTweak, onState: (DeckTweak) -> Unit) {
    state.subject?.let { Subject(it, null) }
    Div(attrs = { classes("pick") }) {
        Option("Swap it for another card", "Takes the same number out as it puts in.") {
            onState(state.doing(Tweak.SWAP))
        }
        Option("Change how many", "There ${if (state.qty == 1) "is" else "are"} ${state.qty} in the deck.") {
            onState(state.doing(Tweak.QUANTITY))
        }
        Option("Take it out of the deck", "The copies go back to bulk.") {
            onState(state.doing(Tweak.REMOVE))
        }
    }
}

@Composable
private fun Option(label: String, help: String, go: () -> Unit) {
    Button(attrs = {
        classes("opt")
        onClick { go() }
    }) {
        Span(attrs = { classes("opt-mark") }) {}
        Span(attrs = { classes("opt-text") }) {
            Span(attrs = { classes("opt-label") }) { Text(label) }
            Span(attrs = { classes("opt-help") }) { Text(help) }
        }
    }
}

@Composable
private fun Body(
    state: DeckTweak,
    onState: (DeckTweak) -> Unit,
    onFind: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    state.subject?.let { Subject(it, state.kind) }

    if (state.needsACard) Finder(state, onState, onFind)

    // Removing takes the whole row; everything else has a number.
    if (state.kind != Tweak.REMOVE) Counter(state, onState)

    Div(attrs = { classes("tweak-says") }) { Text(state.summary) }

    state.plan?.let { Plan(it) }
    state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
    state.errors.forEach { Div(attrs = { classes("err") }) { Text(it) } }

    Div(attrs = { classes("wiz-foot") }) {
        if (state.plan == null) {
            Button(attrs = {
                classes("btn", "primary")
                if (!state.ready) disabled()
                onClick { onPreview() }
            }) { Text(if (state.busy) "Working…" else "Preview →") }
            if (!state.ready && state.needsACard && state.pick == null) {
                Span(attrs = { classes("small", "says") }) { Text("Find the card first.") }
            }
        } else {
            Button(attrs = {
                classes("btn", "ghost")
                onClick { onState(state.copy(plan = null)) }
            }) { Text("← Change it") }
            Button(attrs = {
                classes("btn", "primary")
                if (!state.canApply) disabled()
                onClick { onApply() }
            }) { Text(if (state.busy) "Working…" else "${state.kind?.verb ?: "Do"} it") }
        }
    }
}

/** The card being acted on, so the sheet says what it is about. */
@Composable
private fun Subject(card: org.mattshoe.mtg.core.DeckCard, kind: Tweak?) {
    Div(attrs = { classes("deck-line") }) {
        Div(attrs = { classes("thumb") }) {
            card.art?.let {
                org.jetbrains.compose.web.dom.Img(src = it, alt = "", attrs = { attr("loading", "lazy") })
            }
        }
        Div(attrs = { classes("line-text") }) {
            Div(attrs = { classes("line-top") }) {
                Span(attrs = { classes("t-name") }) { Text(card.shown) }
            }
            card.knownTypeLine?.takeIf { it.isNotBlank() }?.let {
                Span(attrs = { classes("line-type") }) { Text(it) }
            }
        }
        Span(attrs = { classes("num") }) { Text("${card.qty}×") }
        if (kind == Tweak.SWAP) Span(attrs = { classes("tag", "mini") }) { Text("going out") }
    }
}

/** Search the collection for the card coming in. */
@Composable
private fun Finder(state: DeckTweak, onState: (DeckTweak) -> Unit, onFind: (String) -> Unit) {
    Input(type = InputType.Text) {
        classes("field")
        placeholder(if (state.kind == Tweak.SWAP) "Swap in…" else "Card name")
        value(state.term)
        attr("autocomplete", "off")
        onInput { e ->
            val next = state.typed(e.value)
            onState(next)
            if (e.value.trim().length >= DeckTweak.MIN_TERM) onFind(e.value)
        }
    }

    state.pick?.let { chosen ->
        Div(attrs = { classes("tweak-pick") }) {
            Span(attrs = { classes("t-name") }) { Text(chosen.name) }
            chosen.typeLine?.let { Span(attrs = { classes("line-type") }) { Text(it) } }
            if (chosen.qty > 0) {
                Span(attrs = { classes("tag", "mini") }) { Text("${chosen.qty} owned · ${chosen.owner}") }
            } else {
                Span(attrs = { classes("tag", "mini", "warn") }) { Text("not owned — would be bought") }
            }
        }
    }

    if (state.pick == null && state.found.isNotEmpty()) {
        Div(attrs = { classes("found") }) {
            state.found.forEach { f -> Hit(f) { onState(state.picked(f)) } }
        }
    }
}

@Composable
private fun Hit(f: Found, pick: () -> Unit) {
    Button(attrs = {
        classes("found-row")
        onClick { pick() }
    }) {
        Span(attrs = { classes("t-name") }) { Text(f.name) }
        f.typeLine?.let { Span(attrs = { classes("line-type") }) { Text(it) } }
        // Nobody owns it, so putting it in a deck means buying it.
        // The plan will say so; the row says it first.
        if (f.qty > 0) {
            Span(attrs = { classes("tag", "mini") }) { Text("${f.qty}× ${f.owner}") }
        } else {
            Span(attrs = { classes("tag", "mini", "warn") }) { Text("not owned") }
        }
    }
}

/** How many, with the two buttons a thumb actually wants. */
@Composable
private fun Counter(state: DeckTweak, onState: (DeckTweak) -> Unit) {
    Div(attrs = { classes("counter") }) {
        Button(attrs = {
            classes("btn", "sm")
            attr("aria-label", "One fewer")
            if (state.qty <= 0) disabled()
            onClick { onState(state.count(state.qty - 1)) }
        }) { Text("−") }
        Input(type = InputType.Text) {
            classes("field", "count")
            attr("inputmode", "numeric")
            attr("aria-label", "How many")
            value("${state.qty}")
            onInput { e -> onState(state.count(e.value.trim().toIntOrNull() ?: 0)) }
        }
        Button(attrs = {
            classes("btn", "sm")
            attr("aria-label", "One more")
            onClick { onState(state.count(state.qty + 1)) }
        }) { Text("+") }
    }
}

/**
 * What the server says it would do.
 *
 * The sourcing lines are the point: a card going into a deck has to
 * come from somewhere, and "this one would be bought" is worth
 * knowing before it is.
 */
@Composable
private fun Plan(p: org.mattshoe.mtg.core.DeckPlan) {
    Div(attrs = { classes("tally") }) {
        Figure("${p.cardCount}", "cards after")
        Figure("${p.added.size + p.changed.size}", "in")
        Figure("${p.removed.size}", "out")
    }
    if (p.acquired.isNotEmpty()) {
        Div(attrs = { classes("tag", "warn") }) {
            Text("${p.acquired.sumOf { it.qty }} to buy: " + p.acquired.joinToString(", ") { it.name })
        }
    }
    if (p.returned.isNotEmpty()) {
        Div(attrs = { classes("tag", "mini", "ok") }) {
            Text("back to bulk: " + p.returned.joinToString(", ") { "${it.qty}× ${it.name}" })
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Div(attrs = { classes("tally-cell") }) {
        Div(attrs = { classes("tally-n") }) { Text(value) }
        Div(attrs = { classes("tally-l") }) { Text(label) }
    }
}

@Composable
private fun Done(state: DeckTweak, onClose: () -> Unit) {
    Div(attrs = { classes("tweak-says") }) { Text("Done — ${state.summary}") }
    Div(attrs = { classes("wiz-foot") }) {
        Button(attrs = {
            classes("btn", "primary")
            onClick { onClose() }
        }) { Text("Back to the deck") }
    }
}
