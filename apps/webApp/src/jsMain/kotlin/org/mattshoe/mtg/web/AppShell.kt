package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.ShareWhat
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
    onExport: (ExportTo) -> Unit = {},
    onOpenCard: (CardRow) -> Unit = {},
    onOpenFound: (Found) -> Unit = {},
    /** A card tapped in a deck list. Carries its own `name_norm`. */
    onOpenNamed: (String, String, String) -> Unit = { _, _, _ -> },
    /** Copy a link to whatever is on screen. */
    onShare: () -> Unit = {},
    /** The deck has four ways to hand itself over rather than one. */
    onShareDeck: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
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
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onTweakState: (DeckTweak) -> Unit = {},
    onTweakFind: (String) -> Unit = {},
    onTweakPreview: () -> Unit = {},
    onTweakApply: () -> Unit = {},
) {
    var password by remember { mutableStateOf("") }

    // The page underneath an overlay holds still. `overscroll-behavior`
    // on the overlay only stops the chaining once the overlay itself
    // reaches its end; a swipe that starts on the scrim never touches
    // the overlay at all and went straight through to the results.
    DisposableEffect(state.overlays.stack.isEmpty()) {
        lockPage(state.overlays.stack.isNotEmpty())
        onDispose { lockPage(false) }
    }

    // `app-nav`, not `tabs`: the hand-written stylesheet collapses
    // `.tabs` into a hamburger drawer below 720px and opens it from the
    // header's own JavaScript, so borrowing that class left the phone
    // with no navigation at all.
    when (state.view) {
        View.LIBRARY -> LibraryPage(
            state = state.library,
            onState = { onState(state.copy(library = it)) },
            onSearch = onSearch,
            onOpen = onOpenCard,
            onExport = onExport,
            complete = state.complete,
            onName = { c ->
                // Only when the name itself moved. The suggestion
                // list reports every change it has — a row
                // highlighted, the list closed because you pressed
                // somewhere else — and all of those were running a
                // fresh search against the database. On a phone that
                // meant the page reloading under your thumb on every
                // single touch, for as long as there was a name in
                // the box.
                val was = state.complete.term
                onState(state.typedCardName(c))
                if (c.term != was) {
                    if (c.worthAsking) onLookup(c.term)
                    onSearch()
                }
            },
            facets = state.facets,
        )

        View.DECKS -> DecksPage(
            state = state.decks,
            onOpen = { onOpenDeck(it.slug) },
            onClose = { onState(state.copy(decks = state.decks.close())) },
            admin = state.admin.unlocked,
            onNew = { onState(state.opening(Overlay.NEW_DECK)) },
            onEdit = { onEditDeck(it.slug) },
            onDisassemble = { onAskDisassemble(it.slug) },
            onOpenCard = { card, owner -> onOpenNamed(card.name, card.nameNorm, owner) },
            onShare = onShareDeck,
            onAddCard = onAddCard,
            onTweak = onTweak,
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

        View.CARD -> CardPage(
            card = state.card ?: CardDetail(name = state.route.rest).loading(),
            onShare = onShare,
            onBack = { onState(state.leaveCard()) },
        )

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

    // The `.toasts` wrapper is what `position: fixed` lives on; a
    // bare `.toast` rendered in flow, off the bottom of the page.
    //
    // The toast itself is a button, and tapping anywhere on it puts it
    // away. It used to sit in the bottom corner and stay there, which
    // on a phone is full width directly over whatever the screen's own
    // action bar is — "Apply" sat underneath "staging.txt — decklist,
    // 15 cards" with no way to move it. It also goes away on its own
    // now, and on a narrow screen it comes down from the top instead.
    state.toast?.let {
        Div(attrs = { classes("toasts") }) {
            Button(attrs = {
                classes("toast")
                attr("title", "Dismiss")
                attr("aria-label", "Dismiss")
                onClick { onState(state.say(null)) }
            }) {
                Span(attrs = { classes("toast-says") }) { Text(it) }
                Span(attrs = { classes("toast-x") }) { Text("×") }
            }
        }
    }

    // ------------------------------------------------------- overlays

    state.deckTweak?.takeIf { Overlay.DECK_TWEAK in state.overlays }?.let { tweak ->
        DeckTweakSheet(
            state = tweak,
            onState = onTweakState,
            onFind = onTweakFind,
            onPreview = onTweakPreview,
            onApply = onTweakApply,
            onClose = { onState(state.closing(Overlay.DECK_TWEAK)) },
        )
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
        Div(attrs = {
            classes("palette-scrim")
            onClick { onState(state.closing(Overlay.UNLOCK)); password = "" }
        }) {
            // `panel-head` and `panel-body`, the same as every other
            // dialog. Loose children of `.palette` get no padding at
            // all, which is why this one had its text against the edge.
            Div(attrs = {
                classes("palette")
                onClick { it.stopPropagation() }
            }) {
                Div(attrs = { classes("panel-head") }) { H2 { Text("Admin mode") } }
                Div(attrs = { classes("panel-body", "stack") }) {
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
                    Div(attrs = { classes("flex-wrap") }) {
                        Button(attrs = {
                            classes("btn", "primary")
                            // Blank passwords were offered to the
                            // server, and a second press while the
                            // first was still out sent it again.
                            if (password.isBlank() || !state.admin.canTry) disabled()
                            onClick {
                                onState(state.closing(Overlay.UNLOCK))
                                onUnlock(password)
                                password = ""
                            }
                        }) { Text(if (state.admin.trying) "Unlocking…" else "Unlock") }
                        Button(attrs = {
                            classes("btn", "ghost")
                            onClick { onState(state.closing(Overlay.UNLOCK)); password = "" }
                        }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Where a deck tap goes, as a route rather than a special case. */
fun AppState.openDeck(slug: String) = navigate(Route(View.DECKS, slug))

/** For the tests, and for anything that wants the history without the shell. */
fun AppState.withHistory(h: EntryHistory) = copy(history = h)

/**
 * Stops the page behind an overlay scrolling.
 *
 * A class on `body` rather than an inline style, so the rule lives in
 * the stylesheet with everything else and a half-torn-down
 * composition cannot leave the page stuck.
 */
private fun lockPage(locked: Boolean) {
    val body = kotlinx.browser.document.body ?: return
    if (locked) body.classList.add("overlay-open") else body.classList.remove("overlay-open")
}
