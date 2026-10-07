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
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekCard
import org.mattshoe.mtg.core.ShareWhat
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

    onSearch: () -> Unit,
    onOpenDeck: (String) -> Unit,
    onPreviewEntry: () -> Unit,
    onApplyEntry: () -> Unit,
    /** Hand that account the role it does not have, on Admin Settings. */
    onChangeRole: (org.mattshoe.mtg.core.Person) -> Unit = {},
    onExport: (ExportTo) -> Unit = {},
    onOpenCard: (CardRow) -> Unit = {},
    onOpenFound: (Found) -> Unit = {},
    /** A card tapped in a deck list. Carries its own `name_norm`. */
    onOpenNamed: (String, String, String) -> Unit = { _, _, _ -> },
    /** "Full details" on the carousel's sheet. See `onDetails` below. */
    onOpenPeeked: (PeekCard) -> Unit = {},
    /** Copy a link to whatever is on screen. */
    onShare: () -> Unit = {},
    /** The deck has four ways to hand itself over rather than one. */
    onShareDeck: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
    onFind: (String) -> Unit = {},
    onLookup: (String) -> Unit = {},
    /** The card-name box changed, for whoever holds the live state. */
    onTypedName: ((Completion) -> Unit)? = null,
    /** The suggestion list should go away, and nothing else should move. */
    onDismissNames: (() -> Unit)? = null,
    /** The commander box in the new deck wizard changed. */
    onCommanderTyped: (Completion) -> Unit = {},
    /** A file dropped on the new deck wizard's card list. */
    onDeckFiles: (List<File>) -> Unit = {},
    onFiles: (List<File>) -> Unit = {},
    onReuse: (HistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
    onReviewDeck: () -> Unit = {},
    onSaveDeck: () -> Unit = {},
    onAskDisassemble: (String) -> Unit = {},
    /** Open the rename box for a deck. */
    onAskRename: (String) -> Unit = {},
    /** Write the new name. */
    onSaveRename: () -> Unit = {},
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
            // The carousel, the same as a deck's rows.
            onOpen = { row -> onState(state.peekRow(row)) },
            onExport = onExport,
            complete = state.complete,
            // Whoever holds the live state decides what a change to
            // the name means. Deciding it here means deciding it
            // against `state`, and `state` is this composition's
            // copy — one frame behind the moment anything writes. Two
            // edits inside a frame compared the new name against
            // itself, concluded nothing had changed and skipped the
            // search, so clearing the box left the narrowed results
            // sitting under an empty field.
            //
            // A bare mount with no owner searches on every change,
            // which is what this did before any of it.
            onName = { c ->
                val was = state.complete.term
                onState(state.typedCardName(c))
                if (c.term != was) {
                    if (c.worthAsking) onLookup(c.term)
                    onSearch()
                }
            },
            onDismissNames = onDismissNames ?: { onState(state.copy(complete = state.complete.closed())) },
            facets = state.facets,
        )

        View.DECKS -> DecksPage(
            state = state.decks,
            onOpen = { onOpenDeck(it.slug) },
            onClose = { onState(state.copy(decks = state.decks.close())) },
            admin = state.canEdit,
            onEdit = { onEditDeck(it.slug) },
            onRename = { onAskRename(it.slug) },
            onDisassemble = { onAskDisassemble(it.slug) },
            // The carousel, not the card's page. The page is a
            // button inside it. Same rule as the phone, and the rule
            // itself is `AppState.peekCard` in the shared core.
            onOpenCard = { card, _ -> onState(state.peekCard(card)) },
            onShare = onShareDeck,
            onAddCard = onAddCard,
            onTweak = onTweak,
        )

        View.STATS -> StatsPage(state.stats)

        View.ADMIN -> AdminPage(
            state = state.people,
            me = state.admin.account?.slug,
            onChange = onChangeRole,
        )

        View.LOGS -> LogsPage(state.logs) { onState(state.copy(logs = it)) }

        View.CARD -> CardPage(
            card = state.card ?: CardDetail(name = state.route.rest).loading(),
            onShare = onShare,
            onBack = { onState(state.leaveCard()) },
            previous = state.previousCard,
            next = state.nextCard,
            place = state.cardPlace,
            onStep = { c -> onOpenNamed(c.name, c.nameNorm, "") },
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
            onNewDeck = { onState(state.opening(Overlay.NEW_DECK)) },
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
                if (state.toastFailed) classes("bad")
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

    if (Overlay.CARD_PEEK in state.overlays) {
        CardCarousel(
            cards = state.peekRun,
            at = state.peek.at,
            place = state.peekPlace,
            admin = state.canEdit,
            onSwipe = { onState(state.peekTo(it)) },
            // Through `onOpenNamed`, the same way every other card is
            // opened — `openPeeked` only moves the state, and a card
            // page whose detail was never fetched spins forever.
            // One callback, because leaving the carousel for the
            // card's page costs different things on the two
            // platforms — the website has a history entry per open
            // overlay to account for, and the phone does not.
            onDetails = { state.peeked?.let(onOpenPeeked) },
            onTweak = { card, how -> onTweak(card, how) },
        )
    }

    if (Overlay.NEW_DECK in state.overlays) {
        NewDeckDialog(
            state = state.newDeck,
            onState = { onState(state.copy(newDeck = it)) },
            onCheck = onCheckNames,
            onCreate = onCreateDeck,
            onClose = { onState(state.closing(Overlay.NEW_DECK)) },
            onCommanderTyped = onCommanderTyped,
            onFiles = onDeckFiles,
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

    // The password dialog was here. There is no password: you sign
    // in with Google, and that leaves the page rather than opening a
    // box over it.
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
