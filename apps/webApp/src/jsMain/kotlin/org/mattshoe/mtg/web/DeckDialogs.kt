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
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.Tally

/**
 * Editing a deck's list, on the web. Sibling of `DeckEditDialog` on
 * Android.
 *
 * A textarea rather than a row of per-card widgets, because a decklist
 * is what this data already is and what every other tool in the app
 * speaks. Replace, not merge: what is in the box is what the deck
 * becomes, and the server does the diff.
 */
@Composable
fun DeckEditDialog(
    state: DeckEditState,
    onState: (DeckEditState) -> Unit,
    onReview: () -> Unit,
    onSave: () -> Unit,
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
                H2 { Text("Edit list") }
                Span(attrs = { classes("spacer") }) {}
                Span(attrs = { classes("muted", "small") }) {
                    Text("${state.lineCount} line${if (state.lineCount == 1) "" else "s"}")
                }
            }
            Div(attrs = { classes("panel-body", "stack") }) {
                Div(attrs = { classes("muted", "small") }) {
                    Text(
                        "Replaces ${state.deckName}'s list entirely — what is in the box is " +
                            "what the deck becomes.",
                    )
                }

                Div(attrs = { classes("field") }) {
                    Span(attrs = { classes("muted", "small") }) { Text("Commander") }
                    Input(type = InputType.Text) {
                        classes("field")
                        placeholder("e.g. Alela, Cunning Conqueror")
                        value(state.commander)
                        onInput { onState(state.typeCommander(it.value)) }
                    }
                }

                Div(attrs = { classes("field") }) {
                    Span(attrs = { classes("muted", "small") }) { Text("The 99") }
                    TextArea(value = state.list, attrs = {
                        classes("field", "mono")
                        rows(18)
                        onInput { onState(state.typeList(it.value)) }
                    })
                }

                state.plan?.takeIf { !state.stale }?.let { PlanSummary(it, state.saved) }

                state.error?.let { message ->
                    Div(attrs = { classes("err") }) {
                        Text((listOf(message) + state.errors).joinToString("\n"))
                    }
                }

                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        if (state.busy || !(if (state.canSave) true else state.canReview)) disabled()
                        onClick { if (state.canSave) onSave() else onReview() }
                    }) {
                        Text(
                            when {
                                state.busy -> "Working…"
                                state.canSave -> "Save list"
                                else -> "Review changes"
                            },
                        )
                    }
                    Button(attrs = {
                        classes("btn", "ghost")
                        onClick { onClose() }
                    }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PlanSummary(plan: DeckPlan, saved: Boolean) {
    Div(attrs = { classes("flex-wrap") }) {
        Span(attrs = { classes("tag", if (saved) "ok" else "info") }) {
            Text(if (saved) "saved" else "preview — nothing saved yet")
        }
        Span(attrs = { classes("muted", "small") }) {
            Text("${plan.rows} rows · ${plan.cardCount} cards · ${plan.ownedCount} owned")
        }
    }
    Chips("Added to the deck", plan.added) { "+${it.qty} ${it.name}" }
    Chips("Removed from the deck", plan.removed) { "−${it.qty} ${it.name}" }
    if (plan.changed.isNotEmpty()) {
        Div(attrs = { classes("small", "muted") }) { Text("Quantity changed (${plan.changed.size})") }
        Div(attrs = { classes("chips") }) {
            plan.changed.forEach {
                Span(attrs = { classes("chip", "mini") }) { Text("${it.name} ${it.before}→${it.after}") }
            }
        }
    }
    Chips("Back to bulk", plan.returned) { "${it.qty}× ${it.name}" }
    if (plan.acquired.isNotEmpty()) {
        // The one that changes what the collection contains.
        Div(attrs = { classes("small") }) {
            Span(attrs = { classes("tag", "warn") }) { Text("added to the collection") }
            Text(" bulk has no spare copy of these, so saving records them as acquired")
        }
        Div(attrs = { classes("chips") }) {
            plan.acquired.forEach {
                Span(attrs = { classes("chip", "mini", "bad") }) { Text("+${it.qty} ${it.name}") }
            }
        }
    }
    if (plan.newlyMissing.isNotEmpty()) {
        Div(attrs = { classes("small", "muted") }) { Text("No longer owned (${plan.newlyMissing.size})") }
        Div(attrs = { classes("chips") }) {
            plan.newlyMissing.forEach { Span(attrs = { classes("chip", "mini", "bad") }) { Text(it) } }
        }
    }
    if (plan.commanderChanged) {
        Div(attrs = { classes("small") }) {
            Span(attrs = { classes("tag", "warn") }) { Text("commander") }
            Text(" → ${plan.commander ?: "none"}")
        }
    }
    if (plan.nothingChanges) {
        Div(attrs = { classes("small", "muted") }) { Text("No changes.") }
    }
}

@Composable
private fun Chips(label: String, items: List<Tally>, text: (Tally) -> String) {
    if (items.isEmpty()) return
    Div(attrs = { classes("small", "muted") }) { Text("$label (${items.size})") }
    Div(attrs = { classes("chips") }) {
        items.take(40).forEach { Span(attrs = { classes("chip", "mini") }) { Text(text(it)) } }
    }
    if (items.size > 40) {
        Div(attrs = { classes("small", "muted") }) { Text("…and ${items.size - 40} more") }
    }
}

/**
 * Taking a deck apart.
 *
 * Irreversible and there is no undo, so it asks first, and what it
 * shows is the server's own dry run rather than a number worked out
 * here.
 */
@Composable
fun DisassembleDialog(state: DisassembleState, onGo: () -> Unit, onClose: () -> Unit) {
    Div(attrs = {
        classes("palette-scrim")
        onClick { onClose() }
    }) {
        Div(attrs = {
            classes("palette")
            onClick { it.stopPropagation() }
        }) {
            Div(attrs = { classes("panel-body", "stack") }) {
                H2 { Text("Disassemble this deck?") }
                Div(attrs = { classes("muted", "small") }) { Text(state.warning) }

                when {
                    state.plan == null && state.busy -> Div(attrs = { classes("empty") }) { Text("Checking…") }
                    state.plan?.cards?.isNotEmpty() == true -> Div(attrs = { classes("table-wrap") }) {
                        state.plan!!.cards.forEach { c ->
                            Div(attrs = { classes("flex-wrap", "small") }) {
                                Span(attrs = { classes("t-name") }) { Text(c.name) }
                                Span(attrs = { classes("tag", "mini") }) { Text("${c.qty}") }
                            }
                        }
                    }
                    state.plan != null -> Div(attrs = { classes("small", "muted") }) {
                        Text("It is not holding anything you own.")
                    }
                }

                state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }

                Div(attrs = { classes("flex-wrap") }) {
                    Button(attrs = {
                        classes("btn", "danger")
                        if (!state.canGo) disabled()
                        onClick { onGo() }
                    }) {
                        Text(if (state.busy) "Working…" else "Disassemble · free ${state.plan?.freed ?: 0}")
                    }
                    Button(attrs = {
                        classes("btn", "ghost")
                        onClick { onClose() }
                    }) { Text("Cancel") }
                }
            }
        }
    }
}
