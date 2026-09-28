package org.mattshoe.mtg.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View

/**
 * The shell: the nav, the lock, whichever screen is current, and
 * whatever is on top of it.
 *
 * Which views exist and which are hidden while locked comes from
 * `Admin` in the shared core, and what back dismisses comes from
 * `Overlays`, so this cannot offer a tab the web does not, leave an
 * admin screen reachable after locking, or make the back gesture mean
 * something different.
 */
@Composable
fun AppShell(
    state: AppState,
    onState: (AppState) -> Unit,
    onUnlock: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenDeck: (String) -> Unit,
    onRunSql: () -> Unit,
    onPreviewEntry: () -> Unit,
    onApplyEntry: () -> Unit,
    onExport: () -> Unit = {},
    onOpenCard: (CardRow) -> Unit = {},
    onOpenFound: (Found) -> Unit = {},
    onFind: (String) -> Unit = {},
    onLookup: (String) -> Unit = {},
    onPickFile: () -> Unit = {},
    onReuse: (HistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
    onReviewDeck: () -> Unit = {},
    onSaveDeck: () -> Unit = {},
    onAskDisassemble: (String) -> Unit = {},
    onDisassemble: () -> Unit = {},
    onCheckNames: () -> Unit = {},
    onCreateDeck: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    var showFilters by remember { mutableStateOf(false) }
    val keys = remember { FocusRequester() }

    // Nothing focuses this by hand, so the shortcuts work from the
    // moment the app opens rather than after the first tap.
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    // Back dismisses what is on top, then backs out of a deck, then
    // leaves. The same order the web's history stack produces, from the
    // same `Overlays`.
    BackHandler {
        val next = state.dismissTop()
        when {
            next != null -> onState(next)
            state.decks.openSlug != null -> onState(state.copy(decks = state.decks.close()))
            state.view != View.DEFAULT -> onState(state.navigate(View.DEFAULT))
            else -> onExit()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .focusRequester(keys)
            .focusable()
            // Bubbling, not preview, on purpose: a focused text field
            // eats the keystroke first, which is exactly the "not while
            // typing" rule the web gets from checking the target's tag.
            .onKeyEvent { event -> event.handledBy(state, onState) },
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Only the views the lock allows. Gated ones are absent, not
            // greyed out — the same as the web.
            state.admin.visible.forEach { view ->
                val on = state.view == view
                if (on) {
                    Button(onClick = {}) { Text(view.label, fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { onState(state.navigate(view)) }) {
                        Text(view.label, fontSize = 13.sp)
                    }
                }
            }
            OutlinedButton(onClick = {
                if (state.admin.unlocked) onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                else onState(state.opening(Overlay.UNLOCK))
            }) { Text(if (state.admin.unlocked) "Lock" else "Unlock", fontSize = 13.sp) }
            OutlinedButton(onClick = {
                onState(state.opening(Overlay.PALETTE).copy(palette = state.palette.opened()))
            }) { Text("Find", fontSize = 13.sp) }
        }

        when (state.view) {
            View.LIBRARY -> LibraryScreen(
                state = state.library,
                onState = { onState(state.copy(library = it)) },
                onSearch = onSearch,
                onOpen = onOpenCard,
                showFilters = showFilters,
                onToggleFilters = { showFilters = !showFilters },
                onExport = onExport,
                complete = state.complete,
                onComplete = { c ->
                    onState(state.copy(complete = c))
                    if (c.worthAsking) onLookup(c.term)
                },
                onCheatsheet = { onState(state.opening(Overlay.CHEATSHEET)) },
            )

            View.DECKS -> DecksScreen(
                state = state.decks,
                onOpen = { onOpenDeck(it.slug) },
                onClose = { onState(state.copy(decks = state.decks.close())) },
                admin = state.admin.unlocked,
                onNew = { onState(state.opening(Overlay.NEW_DECK)) },
                onEdit = { onEditDeck(it.slug) },
                onDisassemble = { onAskDisassemble(it.slug) },
            )

            View.STATS -> StatsScreen(state.stats) { owner: Owner? ->
                onState(state.copy(stats = state.stats.scopedTo(owner).loading()))
            }

            View.CONSOLE -> ConsoleScreen(
                state = state.console,
                onState = { onState(state.copy(console = it)) },
                onRun = onRunSql,
                onCheatsheet = { onState(state.opening(Overlay.CHEATSHEET)) },
            )

            View.LOGS -> LogsScreen(state.logs) { onState(state.copy(logs = it)) }

            View.ENTRY -> MassEntryScreen(
                state = state.entry,
                onState = { onState(state.copy(entry = it)) },
                onPreview = onPreviewEntry,
                onApply = onApplyEntry,
                history = state.history,
                onPickFile = onPickFile,
                onReuse = onReuse,
                onClearHistory = onClearHistory,
            )
        }

        state.toast?.let { Text(it, Modifier.padding(16.dp), fontSize = 13.sp) }
    }

    // ------------------------------------------------------- overlays

    state.card?.takeIf { Overlay.CARD in state.overlays }?.let { card ->
        CardSheet(card) { onState(state.closing(Overlay.CARD)) }
    }

    if (Overlay.PALETTE in state.overlays) {
        PaletteDialog(
            state = state.palette,
            onState = { p ->
                onState(state.copy(palette = p))
                if (p.worthAsking) onFind(p.term)
            },
            onOpen = onOpenFound,
            onClose = { onState(state.closing(Overlay.PALETTE)) },
        )
    }

    if (Overlay.CHEATSHEET in state.overlays) {
        CheatsheetDialog { onState(state.closing(Overlay.CHEATSHEET)) }
    }

    state.deckEdit?.takeIf { Overlay.DECK_EDIT in state.overlays }?.let { edit ->
        DeckEditDialog(
            state = edit,
            onState = { onState(state.copy(deckEdit = it)) },
            onReview = onReviewDeck,
            onSave = onSaveDeck,
            onClose = { onState(state.closing(Overlay.DECK_EDIT)) },
        )
    }

    state.disassemble?.takeIf { Overlay.DISASSEMBLE in state.overlays }?.let { d ->
        DisassembleDialog(
            state = d,
            onGo = onDisassemble,
            onClose = { onState(state.closing(Overlay.DISASSEMBLE)) },
        )
    }

    if (Overlay.NEW_DECK in state.overlays) {
        NewDeckDialog(
            state = state.newDeck,
            onState = { onState(state.copy(newDeck = it)) },
            onCheck = onCheckNames,
            onCreate = onCreateDeck,
            onClose = { onState(state.closing(Overlay.NEW_DECK)) },
        )
    }

    if (Overlay.UNLOCK in state.overlays) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onState(state.closing(Overlay.UNLOCK)) },
            title = { Text("Admin mode") },
            text = {
                Column {
                    Text(
                        "The password, once. It is exchanged for a token that does not " +
                            "expire, and the password itself is never stored.",
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onState(state.closing(Overlay.UNLOCK))
                    onUnlock(password)
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { onState(state.closing(Overlay.UNLOCK)) }) { Text("Cancel") }
            },
        )
    }
}

/** Where a deck tap goes, as a route rather than a special case. */
fun AppState.openDeck(slug: String) = navigate(Route(View.DECKS, slug))

/**
 * One keystroke, through the shared shortcut table.
 *
 * The names match the browser's `KeyboardEvent.key`, because
 * `Shortcuts` is written against those and having two spellings of
 * "Escape" is how the two platforms would drift.
 */
private fun androidx.compose.ui.input.key.KeyEvent.handledBy(
    state: AppState,
    onState: (AppState) -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val name = when {
        key == Key.Escape -> "Escape"
        utf16CodePoint in 0x20..0x7E -> utf16CodePoint.toChar().toString()
        else -> return false
    }
    val next = state.onKey(name, typing = false, meta = isMetaPressed, ctrl = isCtrlPressed)
        ?: return false
    onState(next)
    return true
}
