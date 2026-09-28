package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.files.File

/**
 * The shell: the nav, the lock, whichever screen is current, and
 * whatever is on top of it.
 *
 * Sibling of `AppShell` on Android. The visible tabs come from `Admin`
 * in the shared core and the overlay stack comes from `Overlays`, so a
 * gated view cannot be absent on one platform and present on the other,
 * and back cannot dismiss different things.
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
    onFiles: (List<File>) -> Unit = {},
    onReuse: (HistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
    onReviewDeck: () -> Unit = {},
    onSaveDeck: () -> Unit = {},
    onAskDisassemble: (String) -> Unit = {},
    onDisassemble: () -> Unit = {},
    onCheckNames: () -> Unit = {},
    onCreateDeck: () -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }

    // `app-nav`, not `tabs`: the hand-written stylesheet collapses
    // `.tabs` into a hamburger drawer below 720px and opens it from the
    // header's own JavaScript, so borrowing that class left the phone
    // with no navigation at all.
    Nav(attrs = { classes("app-nav") }) {
        state.admin.visible.forEach { view ->
            Button(attrs = {
                classes("app-tab")
                if (state.view == view) classes("on")
                onClick { onState(state.navigate(view)) }
            }) { Text(view.label) }
        }
        Button(attrs = {
            classes("btn", "sm", "ghost", "app-tool")
            onClick {
                if (state.admin.unlocked) {
                    onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                } else {
                    onState(state.opening(Overlay.UNLOCK))
                }
            }
        }) { Text(if (state.admin.unlocked) "Lock" else "Unlock") }
        Button(attrs = {
            classes("btn", "sm", "ghost", "app-tool")
            onClick { onState(state.opening(Overlay.PALETTE).copy(palette = state.palette.opened())) }
        }) { Text("Find") }
    }

    when (state.view) {
        View.LIBRARY -> LibraryPage(
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

        View.DECKS -> DecksPage(
            state = state.decks,
            onOpen = { onOpenDeck(it.slug) },
            onClose = { onState(state.copy(decks = state.decks.close())) },
            admin = state.admin.unlocked,
            onNew = { onState(state.opening(Overlay.NEW_DECK)) },
            onEdit = { onEditDeck(it.slug) },
            onDisassemble = { onAskDisassemble(it.slug) },
        )

        View.STATS -> StatsPage(state.stats) { owner: Owner? ->
            onState(state.copy(stats = state.stats.scopedTo(owner).loading()))
        }

        View.CONSOLE -> ConsolePage(
            state = state.console,
            onState = { onState(state.copy(console = it)) },
            onRun = onRunSql,
            onCheatsheet = { onState(state.opening(Overlay.CHEATSHEET)) },
        )

        View.LOGS -> LogsPage(state.logs) { onState(state.copy(logs = it)) }

        View.ENTRY -> MassEntryPage(
            state = state.entry,
            onState = { onState(state.copy(entry = it)) },
            onPreview = onPreviewEntry,
            onApply = onApplyEntry,
            history = state.history,
            onFiles = onFiles,
            onReuse = onReuse,
            onClearHistory = onClearHistory,
        )
    }

    state.toast?.let { Div(attrs = { classes("toast") }) { Text(it) } }

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
        Div(attrs = { classes("palette-scrim") }) {
            Div(attrs = { classes("palette") }) {
                H2 { Text("Admin mode") }
                Div(attrs = { classes("muted", "small") }) {
                    Text(
                        "The password, once. It is exchanged for a token that does not " +
                            "expire, and the password itself is never stored.",
                    )
                }
                Input(type = InputType.Password) {
                    classes("field")
                    placeholder("Password")
                    value(password)
                    onInput { password = it.value }
                }
                Div(attrs = { classes("flex") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        onClick {
                            onState(state.closing(Overlay.UNLOCK))
                            onUnlock(password)
                            password = ""
                        }
                    }) { Text("Unlock") }
                    Button(attrs = {
                        classes("btn", "ghost")
                        onClick { onState(state.closing(Overlay.UNLOCK)); password = "" }
                    }) { Text("Cancel") }
                }
            }
        }
    }
}

/** Where a deck tap goes, as a route rather than a special case. */
fun AppState.openDeck(slug: String) = navigate(Route(View.DECKS, slug))

/** For the tests, and for anything that wants the history without the shell. */
fun AppState.withHistory(h: EntryHistory) = copy(history = h)
