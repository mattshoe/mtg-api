package org.mattshoe.mtg.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Share
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.mattshoe.mtg.core.Design
import kotlin.math.roundToInt

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

    onSearch: () -> Unit,
    onOpenDeck: (String) -> Unit,
    onPreviewEntry: () -> Unit,
    onApplyEntry: () -> Unit,
    /** Hand that account the role it does not have, on Admin Settings. */
    onChangeRole: (org.mattshoe.mtg.core.Person) -> Unit = {},
    /** The Library's filtered set, copied or downloaded. */
    onExport: (ExportTo) -> Unit = {},
    onOpenCard: (CardRow) -> Unit = {},
    onOpenFound: (Found) -> Unit = {},
    /** A card tapped in a deck list. Carries its own `name_norm`. */
    onOpenNamed: (String, String, String) -> Unit = { _, _, _ -> },
    /** "Full details" on the carousel's sheet. See `onDetails` below. */
    onOpenPeeked: (PeekCard) -> Unit = {},
    /** Sign in with Google, from the profile. See `GoogleSignIn`. */
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
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
    /** Hand over a link to the open card. The website's `CardPage` has always had one. */
    onShareCard: (String) -> Unit = {},
    onCheckNames: () -> Unit = {},
    onCreateDeck: () -> Unit = {},
    /** The new deck wizard's commander box, which has its own suggestions. */
    onCommanderTyped: (Completion) -> Unit = {},
    onExit: () -> Unit = {},
) {
    var showFilters by remember { mutableStateOf(false) }
    // One hamburger at every width, so there is one behaviour to keep
    // straight rather than two. See `NavMenu`.
    var menuOpen by remember { mutableStateOf(false) }
    // Where the bar ends, in root pixels, so the menu hangs off the
    // bottom of it rather than off a guess. The bar sits inside
    // `safeDrawingPadding`, whose size is the status bar's and is not
    // known here.
    var barBottom by remember { mutableStateOf(0) }
    // Asked only once back has nowhere left to go but out, and only
    // when `AppState.wouldExitWithUnsavedEntry` says there is a
    // pasted list that exit would throw away — see `BackHandler`
    // below.
    var confirmDiscardOnExit by remember { mutableStateOf(false) }
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

    // One `ScrollState` per destination: the shelf of decks keeps its
    // place under the empty-slug key, and every deck keeps its own
    // under its slug. Reading deck A, opening a card, coming back,
    // closing it and opening deck B must not hand B deck A's offset.
    //
    // The keying alone was not enough, because the list and the open
    // deck were one composable inside one scrolling `Column` — so
    // whichever offset the container happened to be holding was the
    // one the next thing rendered at. They are two destinations now
    // (`DecksListScreen` and `DeckDetailScreen`), each with its own
    // container, which is what actually makes an inherited offset
    // impossible rather than merely unlikely.
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
    // A null `back()` used to go straight to `onExit()`, which is how
    // a pasted-but-unapplied Mass Entry list disappeared without a
    // word: Matt, after the fact — "if you get to exit early, i want
    // you to alert the user that the changes will not be saved."
    // `wouldExitWithUnsavedEntry` is the one check for whether this
    // particular null is that kind of exit; everything else about
    // when to leave is still `AppState.back`'s call alone.
    BackHandler {
        val next = state.back()
        when {
            next != null -> onState(next)
            state.wouldExitWithUnsavedEntry -> confirmDiscardOnExit = true
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                // Otherwise the bar sits under the clock.
                .safeDrawingPadding()
            .focusRequester(keys)
            .focusable()
            // Bubbling, not preview, on purpose: a focused text field
            // eats the keystroke first, which is exactly the "not while
            // typing" rule the web gets from checking the target's tag.
            .onKeyEvent { event -> event.handledBy(state, onState) },
    ) {
            // The bar: the way to everywhere, the way home, and what
            // you are looking at. The web's `AppNav`, in the same
            // order — burger, mark, title.
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .onGloballyPositioned { barBottom = it.boundsInRoot().bottom.roundToInt() },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeMark {
                    menuOpen = false
                    onState(state.navigate(View.DEFAULT))
                }
                // `.topbar-title`. One line, clipped rather than
                // wrapped: a deck called "Alela, Artful Provocateur"
                // would otherwise push the bar to two rows and move
                // every screen down with it.
                //
                // `titleBeside` and not `title`, because the bar at
                // the bottom prints the current view's label already
                // and this was printing it again — "Library /
                // Library" on every page. The web passes no bar and
                // gets the title unchanged.
                Text(
                    state.titleBeside(state.admin.bar),
                    Modifier.weight(1f).padding(start = 4.dp).testTag("topbar-title"),
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Who you are, on the right, where a profile lives.
                ProfileButton(menuOpen, state.admin.account?.avatar) { menuOpen = !menuOpen }
            }

            // The screens take the room that is left and the bar keeps
            // its own. Without a weight here the screens claim the whole
            // column and the bar measures zero — present in the tree,
            // invisible on the phone.
            Box(Modifier.weight(1f)) {
                when (state.view) {
                    View.LIBRARY -> LibraryScreen(
                        state = state.library,
                        onState = { onState(state.copy(library = it)) },
                        onSearch = onSearch,
                        // The carousel, the same as a deck's rows.
                        // Matt: "let's use the same carousel for the
                        // library page."
                        onOpen = { row -> onState(state.peekRow(row)) },
                        showFilters = showFilters,
                        onToggleFilters = { showFilters = !showFilters },
                        onExport = onExport,
                        complete = state.complete,
                        onName = { c ->
                            onState(state.typedCardName(c))
                            if (c.worthAsking) onLookup(c.term)
                            onSearch()
                        },
                        // Close the list and nothing else — not
                        // `typedCardName`, which would also hand the name
                        // filter whatever term this frame drew, and not a
                        // search either. The only thing a tap somewhere
                        // else asked for is the list out of the way.
                        onDismissName = { onState(state.copy(complete = state.complete.closed())) },
                        onCheatsheet = { onState(state.opening(Overlay.CHEATSHEET)) },
                        // Without this every facet list in the panel — types,
                        // set types, layouts, frames, borders, the deck and
                        // format dropdowns — renders empty on the phone while
                        // the website fills them from the same state.
                        facets = state.facets,
                        gridState = libraryGridState,
                    )

                    // Two destinations under one `View`, composed one at
                    // a time, and **the route decides which** — not
                    // whether the data has arrived. That is the whole
                    // point of calling it a destination: the address says
                    // where you are, and a deck whose cards are still in
                    // flight is still the deck you navigated to.
                    //
                    // Reading it off `state.decks.open` instead had the
                    // list reappear under you for as long as the fetch
                    // took, and left `← Decks` unable to get back at all
                    // when the deck was still loaded in state.
                    View.DECKS -> {
                        val openDeck = state.decks.open.takeIf { state.route.rest.isNotEmpty() }
                        if (state.route.rest.isEmpty()) {
                            DecksListScreen(
                                state = state.decks,
                                scrollState = deckScrollState,
                                admin = state.canEdit,
                                onOpen = { onOpenDeck(it.slug) },
                            )
                        } else if (openDeck == null) {
                            // The address names a deck whose cards have
                            // not landed. Still the deck's destination,
                            // with the deck's own way back — not the
                            // shelf, which would take the gesture.
                            DeckLoadingScreen(
                                slug = state.route.rest,
                                error = state.decks.error,
                                onClose = { onState(state.navigate(Route(View.DECKS))) },
                            )
                        } else {
                            DeckDetailScreen(
                                state = state.decks,
                                open = openDeck,
                                scrollState = deckScrollState,
                                admin = state.canEdit,
                                onClose = { onState(state.navigate(Route(View.DECKS))) },
                                onEdit = { onEditDeck(it.slug) },
                                onDisassemble = { onAskDisassemble(it.slug) },
                                onRename = { onAskRename(it.slug) },
                                // The carousel, not the card's page.
                                // Matt: "this should be what happens
                                // when you tap a card in the deck
                                // list. This is not a special feature
                                // that launch." The page is a button
                                // inside it.
                                onOpenCard = { card, _ -> onState(state.peekCard(card)) },
                                onAddCard = onAddCard,
                                onTweak = onTweak,
                                onShare = onShare,
                            )
                        }
                    }

                    View.STATS -> StatsScreen(state.stats)


                    View.ADMIN -> AdminScreen(
                        state = state.people,
                        me = state.admin.account?.slug,
                        onChange = onChangeRole,
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
                        // The card's own address, which is the card alone
                        // — `openCard` never puts the deck underneath into
                        // the route, so this link is the same whichever
                        // way the card was reached.
                        onShare = { onShareCard(Share.link(state)) },
                    )

                    // Two screens under one `View`, the way DECKS
                    // holds the shelf and an open deck. The deck
                    // wizard is reached from the entry wizard's first
                    // question and is drawn in the same shape, so
                    // putting it anywhere but here would be a second
                    // place for the same flow to live.
                    //
                    // Still an overlay rather than a route, which is
                    // what keeps Back stepping it — see
                    // `AppState.back`.
                    View.ENTRY -> if (Overlay.NEW_DECK in state.overlays) {
                        NewDeckScreen(
                            state = state.newDeck,
                            onState = { onState(state.copy(newDeck = it)) },
                            onCheck = onCheckNames,
                            onCreate = onCreateDeck,
                            onClose = { onState(state.closing(Overlay.NEW_DECK)) },
                            onCommanderTyped = onCommanderTyped,
                            onPickFile = onPickFile,
                        )
                    } else {
                        MassEntryScreen(
                            state = state.entry,
                            onState = { onState(state.copy(entry = it)) },
                            onPreview = onPreviewEntry,
                            onApply = onApplyEntry,
                            onNewDeck = { onState(state.opening(Overlay.NEW_DECK)) },
                            history = state.history,
                            onPickFile = onPickFile,
                            onReuse = onReuse,
                            onClearHistory = onClearHistory,
                        )
                    }
                }

                // Floated over the current screen rather than appended
                // to the end of it — and inside this box rather than over
                // the whole shell, so it can never cover the bar. On a
                // wide screen it docks bottom-end, which after the bar
                // arrived was exactly where the tabs are: its own test
                // pressed Decks through it and got the Library.
                state.toast?.let { message ->
                    ToastTray(message, onDismiss = { onState(state.say(null)) })
                }
            }

            BottomNav(
                items = state.admin.bar,
                current = state.view,
                onGo = { view ->
                    menuOpen = false
                    onState(state.navigate(view))
                },
            )
        }

        // Over the page rather than inside the bar, so the menu is
        // not clipped by a bar one row high and nothing underneath it
        // takes the press meant for the backdrop.
        if (menuOpen) {
            // A press anywhere else closes it, the way a menu is
            // expected to behave. `detectTapGestures` and not
            // `clickable`: a full-screen `clickable` sets
            // `shouldMergeDescendantSemantics`, which folds the whole
            // page beneath it into one node and blinds every test
            // that reads a tag.
            Box(
                Modifier.fillMaxSize()
                    .testTag("nav-backdrop")
                    .pointerInput(Unit) { detectTapGestures { menuOpen = false } },
            )
            NavMenu(
                state = state,
                top = barBottom,
                // Closes even when the view picked is the one already
                // showing — otherwise the menu sits open over the page.
                onPick = { next -> menuOpen = false; onState(next) },
                onSignIn = onSignIn,
                onSignOut = onSignOut,
            )
        }

        // Over everything, the bar included, because it is modal in a
        // way the nav menu is not: the deck shows through the scrim
        // so you can see where you are, and the only ways out are the
        // scrim, Back and the buttons on the sheet.
        if (Overlay.CARD_PEEK in state.overlays) {
            CardCarousel(
                cards = state.peekRun,
                at = state.peek.at,
                place = state.peekPlace,
                admin = state.canEdit,
                onSwipe = { onState(state.peekTo(it)) },
                // Through `onOpenNamed`, the same way every other card
                // is opened — `openPeeked` only moves the state, and a
                // card page whose detail was never fetched spins
                // forever.
                // One callback, because leaving the carousel for the
                // card's page costs different things on the two
                // platforms — the website has a history entry per open
                // overlay to account for, and the phone does not.
                onDetails = { state.peeked?.let(onOpenPeeked) },
                onTweak = { card, how -> onTweak(card, how) },
            )
        }
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

    // The password dialog was here. There is no password: you sign
    // in with Google, which leaves the app rather than opening a box
    // over it.

    // Not an overlay: `AppState` has nothing open to track this
    // against, because there is nothing left open by the time back
    // gets here. The dialog itself is the only thing standing between
    // the press and `onExit()`, so the safe choice — keep editing —
    // is both the dismiss button and what an outside tap or a second
    // back press does, and the destructive one has to be picked on
    // purpose.
    if (confirmDiscardOnExit) {
        AlertDialog(
            onDismissRequest = { confirmDiscardOnExit = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your list has not been written to the collection yet.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscardOnExit = false; onExit() }) { Text("Leave anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardOnExit = false }) { Text("Keep editing") }
            },
        )
    }
}

// ------------------------------------------------------------- the nav

// Every tag below is the website's own class name — `nav-burger`,
// `app-menu`, `app-menu-sep`, `app-menu-group`, `topbar-title`,
// `brand-mark` — so a test on either platform names the same thing.

/**
 * The profile: who you are, and the way in and out of being admin.
 *
 * This is where the hamburger used to be, and it is not the same
 * thing wearing a new icon. The hamburger was a list of places, which
 * is the bottom bar's job now. What is left is everything about
 * *you* — whether admin is on, how to turn it on or off, and the one
 * screen that only exists because it is.
 */
@Composable
private fun ProfileButton(open: Boolean, avatar: String?, onClick: () -> Unit) {
    Box(
        Modifier.size(TouchTarget)
            .testTag("profile")
            .semantics {
                role = Role.Button
                contentDescription = "Profile"
            }
            .background(if (open) AccentDim else Color.Transparent, RadiusSm)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Avatar(avatar, open, size = 26.dp)
    }
}

/**
 * Whoever is signed in, as their own picture.
 *
 * Google sends one with the profile and it is a better answer than a
 * drawn glyph: it says *which* account at a glance rather than that
 * there is one. The glyph is what a visitor gets, and what somebody
 * whose Google account has no picture gets — Matt: "If they don't
 * have one then the existing image is fine."
 *
 * Shared rather than private: `AdminScreen` draws one per row in its
 * list of accounts, and two of these drifting apart would be two
 * different ideas of what an account looks like.
 */
@Composable
fun Avatar(url: String?, on: Boolean, size: Dp) {
    if (url.isNullOrBlank()) {
        ProfileIcon(if (on) Accent2 else Ink2, size = size)
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(size)
            .testTag("profile-avatar")
            .clip(CircleShape)
            .border(1.dp, if (on) Accent else Line2, CircleShape),
        contentScale = ContentScale.Crop,
    )
}

/**
 * The bottom bar.
 *
 * One row, one item per place, each an icon above a single word —
 * which is why `View.label` is enforced at one short word in
 * `:core`. The item you are on carries a pill behind its icon as
 * well as a lighter tint, because the owner is colourblind and a
 * selected tab that differs only in colour is not selected at all to
 * him.
 */
@Composable
private fun BottomNav(items: List<View>, current: View, onGo: (View) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .testTag("bottom-nav")
            .background(Bg2)
            .padding(top = 1.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { view ->
            val on = view == current
            Column(
                Modifier.weight(1f)
                    .heightIn(min = TouchTarget)
                    .testTag("nav-item")
                    // Its own tag as well as the shared one: a test
                    // asking for "the Decks tab" otherwise has to
                    // find it by text, and the label, the icon and
                    // the item are three nodes deep in a merged tree.
                    .semantics { role = Role.Tab; contentDescription = view.label }
                    .clickable { onGo(view) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier.then(
                        if (on) {
                            Modifier.testTag("nav-current")
                                .background(AccentDim, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = 14.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NavIcon(view, if (on) Accent2 else Ink2)
                }
                Line(
                    view.label,
                    if (on) Accent2 else Ink2,
                    Design.TINY,
                    if (on) FontWeight.SemiBold else FontWeight.Normal,
                    Modifier.testTag("nav-label-${view.label}"),
                )
            }
        }
    }
}

/**
 * `.brand`: the app's own icon, and it goes home.
 *
 * The foreground layer rather than the adaptive icon beside it —
 * `painterResource` wants something it can decode, and an
 * `<adaptive-icon>` is a composition of two other drawables.
 */
@Composable
private fun HomeMark(onClick: () -> Unit) {
    Box(
        Modifier.sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .testTag("brand-mark")
            .semantics { role = Role.Button; contentDescription = "Home" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            // `brand_mark`: the lotus and its five pips, cut out of
            // the launcher icon by `scripts/make-brand-mark.py`.
            //
            // It used to be `ic_launcher_foreground`, which is two
            // problems under one name. It is not a foreground: every
            // pixel is opaque `#0E1116`, corners included, so on a
            // dark bar it drew a black tile behind the lotus. And an
            // adaptive icon's foreground keeps its art inside a safe
            // zone the launcher masks away, roughly the middle two
            // thirds, so what was left looked tiny in a 34dp box.
            //
            // The website's `icons/icon-32.png` has the same plate —
            // right for a favicon, wrong here — so copying it only
            // moved the black square. The plate is flooded in from
            // the edges rather than colour-keyed, because one of the
            // five pips is black and a key would have deleted it,
            // and the result is cropped, which is what fixes the
            // safe zone.
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
    }
}

/**
 * `.app-menu`: everywhere you can go, in one list.
 *
 * The ungated views first, then the admin half behind a rule and a
 * heading so it reads as a different kind of thing rather than three
 * more places to go, then the lock. The same order as `AppNav`, off
 * the same `state.admin.visible`, so neither platform can offer a
 * screen the other does not.
 *
 * Hung off the bottom of the bar at a measured offset. `Popup` would
 * place it too, but the suggestion list is already one and its
 * dismissal is wound through `AppState.back`; a second window with
 * its own idea of what back means is how that gets undone.
 */
@Composable
private fun NavMenu(
    state: AppState,
    top: Int,
    onPick: (AppState) -> Unit,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                // Under the profile, which is on the right. The inset
                // used to be measured from the left, where the
                // hamburger was, so the menu opened against the far
                // edge from the control that opened it.
                .align(Alignment.TopEnd)
                .offset { IntOffset(-MENU_INSET.roundToPx(), top + MENU_GAP.roundToPx()) }
                // Fixed rather than min-width: the rows fill it, and
                // a row that fills a width nothing has decided takes
                // the whole screen.
                .width(MENU_WIDTH)
                .background(Bg2, Radius)
                .border(1.dp, Line2, Radius)
                .padding(7.dp)
                .testTag("app-menu"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // Who you are. Not a list of places any more — the bar
            // below is that — so the first thing this says is who is
            // here, because everything else in here follows from it.
            //
            // An account says its own name and the address its
            // collection lives at. The password says "Admin", because
            // that is all it can say: it is not anybody.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(state.admin.account?.avatar, state.admin.signedIn, size = 26.dp)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Line(
                        state.admin.shownName ?: "Not signed in",
                        if (state.admin.signedIn) Accent2 else Ink,
                        Design.SMALL,
                        FontWeight.SemiBold,
                        Modifier.testTag("profile-status"),
                    )
                    Line(
                        state.admin.account?.let { "/c/${it.slug}" } ?: "Read only",
                        Ink3,
                        Design.MINI,
                    )
                }
            }

            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)
                    .height(1.dp)
                    .background(Line2)
                    .testTag("app-menu-sep"),
            )

            // What being admin gets you, which today is the log. It
            // sits here rather than in the bar because it is a screen
            // you open when something is wrong, not one you move
            // between.
            state.admin.behindProfile.forEach { view ->
                MenuTab(view.label, state.view == view) { onPick(state.navigate(view)) }
            }

            // One row, because there is one way in. There was a second
            // beneath it for the operator's password — a shared secret
            // that could write to anybody's cards — and a menu
            // offering both was offering two ways to be somebody, one
            // of which was a way to be everybody. Matt: "Why the FUCK
            // would you have log in AND sign in with Google?!"
            MenuTab(if (state.admin.signedIn) "Log out" else "Sign in with Google", on = false) {
                onPick(state)
                if (state.admin.signedIn) onSignOut() else onSignIn()
            }
        }
    }
}

/** `.app-tab` inside the menu: a full-width row, gold while it is the current view. */
@Composable
private fun MenuTab(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            // A row of text is about 34dp on its own, which is the
            // web's `.app-tab` and three quarters of a fingertip.
            .heightIn(min = TouchTarget)
            .background(if (on) AccentDim else Color.Transparent, RadiusSm)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = if (on) Accent2 else Ink2,
            fontSize = Design.SMALL.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** `top: calc(100% + 7px); left: 0` against the bar's own 8dp inset. */
private val MENU_GAP = 7.dp
private val MENU_INSET = 8.dp
private val MENU_WIDTH = 220.dp

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

@Composable
private fun ToastTray(message: String, onDismiss: () -> Unit) {
    val latest by rememberUpdatedState(message)
    val onLatestDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(message) {
        delay(AppState.TOAST_MS)
        if (latest == message) onLatestDismiss()
    }

    val narrow = LocalConfiguration.current.screenWidthDp < 600
    Box(
        Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = if (narrow) Alignment.TopCenter else Alignment.BottomEnd,
    ) {
        Row(
            Modifier
                .widthIn(max = 380.dp)
                .background(Bg2, RadiusSm)
                .border(1.5.dp, Accent, RadiusSm)
                .semantics {
                    contentDescription = "Dismiss"
                    role = Role.Button
                }
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), color = Ink, fontSize = Design.SMALL.sp)
            Text("×", color = Ink2, fontSize = 17.sp)
        }
    }
}
