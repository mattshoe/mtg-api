package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.RenameState
import org.mattshoe.mtg.core.Tally

/**
 * Editing a deck's list, on Android. Sibling of `DeckEditDialog` on the
 * web.
 *
 * Two presses, always: the first asks the server what would happen, the
 * second commits what it said. `DeckEditState` decides when each is
 * offered, so neither platform can save something that was never
 * previewed.
 */
@Composable
fun DeckEditDialog(
    state: DeckEditState,
    onState: (DeckEditState) -> Unit,
    onReview: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit list") },
        confirmButton = {
            TextButton(
                onClick = { if (state.canSave) onSave() else onReview() },
                enabled = if (state.canSave) true else state.canReview,
            ) {
                Text(
                    when {
                        state.busy -> "Working…"
                        state.canSave -> "Save list"
                        else -> "Review changes"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Replaces ${state.deckName}'s list entirely — what is in the box is " +
                        "what the deck becomes.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = state.commander,
                    onValueChange = { onState(state.typeCommander(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Commander") },
                )
                OutlinedTextField(
                    value = state.list,
                    onValueChange = { onState(state.typeList(it)) },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    label = { Text("The 99 — ${state.lineCount} lines") },
                )
                state.plan?.takeIf { !state.stale }?.let { PlanSummary(it, state.saved) }
                state.error?.let { Text((listOf(it) + state.errors).joinToString("\n"), fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun PlanSummary(plan: DeckPlan, saved: Boolean) {
    Text(
        if (saved) "saved" else "preview — nothing saved yet",
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    )
    Text("${plan.rows} rows · ${plan.cardCount} cards · ${plan.ownedCount} owned", fontSize = 13.sp)
    Tallies("Added to the deck", plan.added) { "+${it.qty} ${it.name}" }
    Tallies("Removed from the deck", plan.removed) { "−${it.qty} ${it.name}" }
    if (plan.changed.isNotEmpty()) {
        Text("Quantity changed (${plan.changed.size})", fontSize = 12.sp)
        Text(plan.changed.joinToString(", ") { "${it.name} ${it.before}→${it.after}" }, fontSize = 12.sp)
    }
    Tallies("Back to bulk", plan.returned) { "${it.qty}× ${it.name}" }
    if (plan.acquired.isNotEmpty()) {
        // The one that changes what the collection contains.
        Text(
            "Bulk has no spare copy of these, so saving records them as acquired",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Text(plan.acquired.joinToString(", ") { "+${it.qty} ${it.name}" }, fontSize = 12.sp)
    }
    if (plan.newlyMissing.isNotEmpty()) {
        Text("No longer owned (${plan.newlyMissing.size})", fontSize = 12.sp)
        Text(plan.newlyMissing.joinToString(", "), fontSize = 12.sp)
    }
    if (plan.commanderChanged) {
        Text("Commander → ${plan.commander ?: "none"}", fontSize = 13.sp)
    }
    if (plan.nothingChanges) Text("No changes.", fontSize = 13.sp)
}

@Composable
private fun Tallies(label: String, items: List<Tally>, text: (Tally) -> String) {
    if (items.isEmpty()) return
    Text("$label (${items.size})", fontSize = 12.sp)
    Text(items.take(40).joinToString(", ", transform = text), fontSize = 12.sp)
    if (items.size > 40) Text("…and ${items.size - 40} more", fontSize = 12.sp)
}

/**
 * Taking a deck apart. Sibling of `DisassembleDialog` on the web.
 *
 * Irreversible and there is no undo, so it asks first, and what it
 * shows is the server's own dry run rather than a number worked out
 * here.
 */
@Composable
fun DisassembleDialog(state: DisassembleState, onGo: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Disassemble this deck?") },
        confirmButton = {
            TextButton(onClick = onGo, enabled = state.canGo) {
                Text(if (state.busy) "Working…" else "Disassemble · free ${state.plan?.freed ?: 0}")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.warning, fontSize = 13.sp)
                when {
                    state.plan == null && state.busy -> Text("Checking…", fontSize = 13.sp)
                    state.plan?.cards?.isNotEmpty() == true ->
                        state.plan!!.cards.forEach { Text("${it.qty}× ${it.name}", fontSize = 13.sp) }
                    state.plan != null -> Text("It is not holding anything you own.", fontSize = 13.sp)
                }
                state.error?.let { Text(it, fontSize = 13.sp) }
            }
        },
    )
}

/**
 * Renaming a deck, on Android. Sibling of `RenameDialog` on the web.
 *
 * It says where the deck will live afterwards, because the slug moves
 * with the name and that changes every link anybody already has to
 * it. `RenameState` decides when Rename is reachable — a blank name,
 * a name with nothing sluggable in it, and a name that has not
 * actually changed are all refused in `:core`, so the phone cannot be
 * more permissive than the website.
 */
@Composable
fun RenameDialog(
    state: RenameState,
    onState: (RenameState) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (state.done) "Renamed" else "Rename deck") },
        confirmButton = {
            if (state.done) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("rename-done")) {
                    Text("Done")
                }
            } else {
                // Tagged because the deck header behind the dialog has
                // a "Rename" of its own, and a test that goes by the
                // word presses that one and proves nothing.
                TextButton(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.testTag("rename-save"),
                ) {
                    Text(if (state.busy) "Renaming…" else "Rename")
                }
            }
        },
        dismissButton = {
            if (!state.done) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("rename-cancel")) {
                    Text("Cancel")
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.done) {
                    Text(
                        "${state.was} is now ${state.name}, at #/decks/${state.nextSlug}",
                        fontSize = 13.sp,
                    )
                } else {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = { onState(state.typed(it)) },
                        label = { Text("Deck name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (state.nextSlug.isEmpty()) {
                            "That name has no letters or digits in it."
                        } else {
                            "It will live at #/decks/${state.nextSlug}"
                        },
                        fontSize = 13.sp,
                    )
                    state.error?.let { Text(it, fontSize = 13.sp) }
                }
            }
        },
    )
}
