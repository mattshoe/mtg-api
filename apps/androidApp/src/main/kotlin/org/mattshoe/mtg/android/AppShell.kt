package org.mattshoe.mtg.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.Tweak
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
    /** A card tapped in a deck list. Carries its own `name_norm`. */
    onOpenNamed: (String, String, String) -> Unit = { _, _, _ -> },
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
    /** Rename the open deck. The slug moves with the name. */
    onAskRename: (String) -> Unit = {},
    onSaveRename: () -> Unit = {},
    /** Maintenance on one card, without leaving the deck. */
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onTweakFind: (String) -> Unit = {},
    onTweakPreview: () -> Unit = {},
    onTweakApply: () -> Unit = {},
    /** The deck, as a link or as a list, copied or downloaded. */
    onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
    onCheckNames: () -> Unit = {},
    onCreateDeck: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    var showFilters by remember { mutableStateOf(false) }
    val keys = remember { FocusRequester() }

    // Where the Library grid and each deck's card list were scrolled
    // to, kept here rather than inside `LibraryScreen` or
    // `DecksScreen`.
    //
    // The `when` below composes exactly one screen at a time. Opening
    // a card switches `state.view` to `CARD`, which stops composing
    // whichever screen was showing — and a `LazyGridState` or
    // `ScrollState` remembered inside that screen dies with it, so
    // coming back built a fresh one at offset zero. These two live
    // here instead, above the `when`, so they survive a trip through
    // `View.CARD` the same way `AppShell` itself does.
    //
    // Nothing here ever calls `scrollTo`: the state objects are
    // simply kept rather than recreated, so there is no restore step
    // to fire too early or to fight a scroll already in progress —
    // the two rules the web's `Scroll` object exists to enforce only
    // apply to a world that rebuilds the page from the network on
    // every navigation. Android's doesn't: `state.library.rows` and
    // `state.decks.cards` are still sitting in memory when the screen
    // comes back, so reusing the same state object is restoring.
    val libraryGridState = remember { LazyGridState() }

    // One `ScrollState` per deck (plus one for the list itself, under
    // the empty-slug key), so reading deck A, opening a card, coming
    // back, closing the deck and opening deck B does not hand B deck
    // A's old offset.
    val deckScrollStates = remember { mutableMapOf<String, ScrollState>() }
    val deckScrollState = deckScrollStates.getOrPut(state.decks.openSlug.orEmpty()) { ScrollState(0) }

    // Nothing focuses this by hand, so the shortcuts work from the
    // moment the app opens rather than after the first tap.
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    // The order is `AppState.back`, in the shared core, so the phone
    // cannot have its own idea of what back means. It used to: the
    // order was written out here, and it asked whether a deck was
    // open before it asked whether a card was on screen. Reading a
    // card from a deck both are true, so the first press closed the
    // deck underneath and left the card up — a press that visibly did
    // nothing — and the second fell through to Library.
    BackHandler { onState(state.back() ?: run { onExit(); return@BackHandler }) }

    Column(
        Modifier.fillMaxSize()
            // Otherwise the tab row sits under the clock.
            .safeDrawingPadding()
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
                NavPill(view.label, state.view == view) { onState(state.navigate(view)) }
            }
            Ghost(if (state.admin.unlocked) "Lock" else "Unlock") {
                if (state.admin.unlocked) onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                else onState(state.opening(Overlay.UNLOCK))
            }
            Ghost("Find") {
                onState(state.opening(Overlay.PALETTE).copy(palette = state.palette.opened()))
            }
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
                onName = { c ->
                    onState(state.typedCardName(c))
                    if (c.worthAsking) onLookup(c.term)
                    onSearch()
                },
                onCheatsheet = { onState(state.opening(Overlay.CHEATSHEET)) },
                // Without this every facet list in the panel — types,
                // set types, layouts, frames, borders, the deck and
                // format dropdowns — renders empty on the phone while
                // the website fills them from the same state.
                facets = state.facets,
                gridState = libraryGridState,
            )

            View.DECKS -> DecksScreen(
                state = state.decks,
                scrollState = deckScrollState,
                onOpen = { onOpenDeck(it.slug) },
                onClose = { onState(state.navigate(Route(View.DECKS))) },
                admin = state.admin.unlocked,
                onNew = { onState(state.opening(Overlay.NEW_DECK)) },
                onEdit = { onEditDeck(it.slug) },
                onDisassemble = { onAskDisassemble(it.slug) },
                onRename = { onAskRename(it.slug) },
                onOpenCard = { card, owner -> onOpenNamed(card.name, card.nameNorm, owner) },
                onAddCard = onAddCard,
                onTweak = onTweak,
                onShare = onShare,
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

            // A card is a destination here too, so the system back
            // gesture leaves it the way it leaves any other screen.
            View.CARD -> CardSheet(
                card = state.card ?: org.mattshoe.mtg.core.CardDetail(name = state.route.rest).loading(),
                onClose = { onState(state.leaveCard()) },
                // Reading a deck a card at a time, the same three
                // controls the web page puts under the card.
                previous = state.previousCard,
                next = state.nextCard,
                place = state.cardPlace,
                onStep = { c -> onOpenNamed(c.name, c.nameNorm, "") },
            )

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

        state.toast?.let { Muted(it, Modifier.padding(16.dp)) }
    }

    // ------------------------------------------------------- overlays

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

    state.deckTweak?.takeIf { Overlay.DECK_TWEAK in state.overlays }?.let { t ->
        DeckTweakSheet(
            state = t,
            onState = { onState(state.copy(deckTweak = it)) },
            onFind = onTweakFind,
            onPreview = onTweakPreview,
            onApply = onTweakApply,
            onClose = { onState(state.closing(Overlay.DECK_TWEAK)) },
        )
    }

    state.rename?.takeIf { Overlay.RENAME in state.overlays }?.let { r ->
        RenameDialog(
            state = r,
            onState = { onState(state.copy(rename = it)) },
            onSave = onSaveRename,
            onClose = { onState(state.closing(Overlay.RENAME)) },
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
                TextButton(
                    enabled = password.isNotBlank() && state.admin.canTry,
                    onClick = {
                        onState(state.closing(Overlay.UNLOCK))
                        onUnlock(password)
                    },
                ) { Text(if (state.admin.trying) "Unlocking…" else "Unlock") }
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
