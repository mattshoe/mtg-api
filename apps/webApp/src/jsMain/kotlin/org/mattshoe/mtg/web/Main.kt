package org.mattshoe.mtg.web

import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.mattshoe.mtg.core.Account
import org.w3c.fetch.RequestInit
import org.w3c.fetch.RequestCredentials
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckQueries
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Export
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.FacetQueries
import org.mattshoe.mtg.core.FilterUrl
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekCard
import org.mattshoe.mtg.core.PaletteQueries
import org.mattshoe.mtg.core.RenameState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.Share
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.StatsQueries
import org.mattshoe.mtg.core.Store
import org.mattshoe.mtg.core.Upload
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.query
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

/**
 * The web app, on the shared core.
 *
 * Owns `AppState`, runs the queries the core hands it, and renders the
 * shell. Every rule it obeys — which tabs are visible, what a screen
 * needs, whether Apply may be offered, what back dismisses — comes from
 * `:core`, shared with Android.
 */
@JsExport
object MtgApp {

    private var composition: Composition? = null
    private var navComposition: Composition? = null

    /**
     * The network, swappable.
     *
     * Not for tidiness: the shell is where the bugs have been — the
     * address bar, the history stack, what back and Close do to each
     * other — and none of that was reachable by a test because
     * mounting the real thing meant hitting the real database. A test
     * can now drive `MtgApp` itself over a stub and press the buttons.
     */
    private var api = MtgApi()
    private var scryfall = Scryfall()

    internal fun useForTesting(api: MtgApi, scryfall: Scryfall) {
        this.api = api
        this.scryfall = scryfall
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * The state lives out here rather than inside the composition
     * because the window listeners — keydown, popstate — have to reach
     * it, and a listener registered per recomposition is a listener
     * registered a hundred times.
     */
    private var held by mutableStateOf(AppState())

    /**
     * The state, and the address bar following it.
     *
     * Every write goes through here rather than through the one
     * callback the composition owns. That callback only fires for
     * things the user clicked *in* a screen — opening a card writes
     * `app` directly from `openNamed`, so the card-in-the-URL support
     * existed and nothing ever drove it. A link to a card was
     * unshareable because the bar never said there was one.
     */
    private var app: AppState
        get() = held
        set(value) {
            val was = held
            held = value
            // Every write lands here, so the token in `localStorage`
            // cannot fall out of step with the one in memory — not the
            // nav menu's Lock button, not Cmd+L, not whatever reaches
            // this property next. See `AdminToken`.
            AdminToken.sync(store, was.admin, value.admin)
            if (value.toast != null && value.toast != was.toast) fadeToast(value.toast!!)
            if (value.hash() == was.hash()) return
            // One rule for the whole app: going somewhere is a step
            // you can come back from, and everything else rewrites
            // where you already are.
            //
            // Opening a deck used to go through here as "everything
            // else", because the push lived in the one callback the
            // composition owns and `openDeck` writes `app` directly.
            // So the deck replaced the list in the address bar and
            // back skipped straight past it — the card drawer had the
            // same bug for the same reason.
            if (value.isAStepFrom(was)) pushHash(value) else replaceHash(value)
            // A new screen starts at the top, and one you have been
            // on before opens where you left it — the same rule the
            // back button follows, so the page's own ← Back and the
            // browser's do not land in two different places.
            if (value.route.view != was.route.view || value.route.rest != was.route.rest) {
                Scroll.remember(was.hash())
                Scroll.restore(value.hash())
            }
        }

    private var toastJob: Job? = null

    /**
     * A toast says its piece and goes.
     *
     * It used to stay until something else replaced it, which on a
     * phone meant a full-width bar parked over whatever the screen's
     * own buttons were. The message it carries is a receipt, not a
     * decision, so nothing is lost by it leaving — and it can be put
     * away by hand before then.
     */
    private fun fadeToast(mine: String) {
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(AppState.TOAST_MS)
            if (app.toast == mine) app = app.say(null)
        }
    }

    private var listening = false
    /**
     * Long enough to finish a word, short enough that a toggle feels
     * immediate. Every filter change asks for a search and a text
     * field changes on every keystroke.
     */
    /** Long enough to read a sentence, short enough to stop mattering. */

    private val searchDebounceMs = 250L
    private var searchJob: Job? = null
    private var lookupJob: Job? = null
    private var commanderJob: Job? = null
    private var findJob: Job? = null
    private var tweakJob: Job? = null

    private val store: Store = BrowserStore()

    fun mount(root: HTMLElement, sharedList: String?, token: String) {
        unmount()
        val opening = routeFromHash()
        app = AppState(admin = Admin(token.ifBlank { null }), history = EntryHistory.load(store))
            // A search is a link: the filters travel in the hash, so a
            // URL somebody sent opens the search they were looking at.
            .let { it.restoredSearch(FilterUrl.fromHash(opening.query)) }
            .let { if (sharedList.isNullOrBlank()) it.navigate(opening) else it.withShare(sharedList) }
        listen()
        loadFor(app)
        loadFacets()
        // Asked for rather than awaited: the page is useful to a
        // stranger, so nothing waits to find out whether this one is.
        loadAccount()

        composition = renderComposable(root = root) {
            val state = app

            // Every overlay that opens pushes a history entry, so the
            // back gesture dismisses what is on top instead of
            // navigating out from under it.
            //
            // Except the card, which is in the address bar and gets
            // its entry from there. Counted here as well, it pushed a
            // second one — and Close then popped back to an address
            // that still said `card=`, which the hashchange after it
            // dutifully reopened.
            LaunchedEffect(state.overlays.historyDepth) {
                OverlayHistory.sync(state.overlays.historyDepth)
            }

            AppShell(
                state = state,
                onState = { next ->
                    val was = app
                    app = next
                    if (next.view != was.view || next.route.rest != was.route.rest) loadFor(next)
                },
                // Every one of these marks itself busy *here*, before
                // the coroutine starts, rather than inside it.
                // Launching first and setting the flag in the suspend
                // body leaves a whole frame in which the button is
                // still live, and a frame is all a double tap needs —
                // each call carries its own idempotency key, so two
                // presses are two writes the server is happy to make.

                onSetRole = { person, role ->
                    // One row at a time: the press greys out the row
                    // it was made on and leaves the rest live.
                    if (app.people.changing == null) {
                        app = app.copy(people = app.people.changing(person.slug))
                        scope.launch {
                            app = try {
                                api.setRole(token(), person.slug, role)
                                app.copy(people = app.people.changed(person.slug, role))
                                    .say("${person.shownName} is now $role")
                            } catch (ex: ApiFailure) {
                                app.copy(people = app.people.refused(ex.message ?: "that did not work"))
                                    .say(ex.message ?: "that did not work", failed = true)
                            }
                        }
                    }
                },
                onSearch = { searchSoon() },
                onOpenDeck = { slug -> work { openDeck(app, slug) } },
                onPreviewEntry = {
                    if (app.entry.canPreview) {
                        app = app.copy(entry = app.entry.working("Checking…"))
                        work {
                            val s = app.entry
                            app.copy(
                                entry = s.previewed(
                                    api.cards(token(), s.direction!!, app.viewing, s.list, dryRun = true),
                                ),
                            )
                        }
                    }
                },
                onApplyEntry = {
                    if (app.entry.canApply) {
                        app = app.copy(entry = app.entry.working("Applying…"))
                        work {
                            val s = app.entry
                            val done = app.copy(
                                entry = s.finished(
                                    api.cards(token(), s.direction!!, app.viewing, s.list, dryRun = false),
                                ),
                            ).shareUsed().recordEntry(now())
                            EntryHistory.save(store, done.history)
                            done
                        }
                    }
                },
                onExport = { where -> work { exportList(app, where) } },
                onShare = { share() },
                onShareDeck = { what, where -> work { shareDeck(app, what, where) } },
                onOpenCard = { row -> openCard(row) },
                onOpenFound = { found -> openFound(found) },
                onOpenNamed = { name, norm, owner -> openNamed(name, norm, owner) },
                onOpenPeeked = { card -> openFromCarousel(card) },
                onTypedName = { c -> nameTyped(c) },
                onDismissNames = { app = app.copy(complete = app.complete.closed()) },
                onCommanderTyped = { c -> commanderTyped(c) },
                onDeckFiles = { files -> readDeckFiles(files) },
                onFind = { term -> find(term) },
                onLookup = { term -> lookup(term) },
                onFiles = { files -> readFiles(files) },
                onReuse = { e -> app = app.copy(entry = app.history.reuse(e)).navigate(View.ENTRY) },
                onClearHistory = {
                    app = app.copy(history = app.history.cleared())
                    EntryHistory.save(store, app.history)
                },
                onEditDeck = { slug -> editDeck(slug) },
                onAddCard = { startTweak(null, Tweak.ADD) },
                onTweak = { card, kind -> startTweak(card, kind) },
                onTweakState = { next -> app = app.copy(deckTweak = next) },
                onTweakFind = { term -> findForTweak(term) },
                onTweakPreview = {
                    claim(
                        app.deckTweak?.ready == true,
                        { app.copy(deckTweak = app.deckTweak?.working()) },
                    ) { planTweak(app) }
                },
                onTweakApply = {
                    claim(
                        app.deckTweak?.canApply == true,
                        { app.copy(deckTweak = app.deckTweak?.working()) },
                    ) { applyTweak(app) }
                },
                onReviewDeck = {
                    claim(
                        app.deckEdit?.canReview == true,
                        { app.copy(deckEdit = app.deckEdit?.working()) },
                    ) { reviewDeck(app) }
                },
                onSaveDeck = {
                    claim(
                        app.deckEdit?.canSave == true,
                        { app.copy(deckEdit = app.deckEdit?.working()) },
                    ) { saveDeck(app) }
                },
                onAskDisassemble = { slug -> askDisassemble(slug) },
                onAskRename = { slug -> askRename(slug) },
                onSaveRename = {
                    claim(
                        app.rename?.canSave == true,
                        { app.copy(rename = app.rename?.working()) },
                    ) { renameDeck(app) }
                },
                onDisassemble = {
                    claim(
                        app.disassemble?.canGo == true,
                        { app.copy(disassemble = app.disassemble?.working()) },
                    ) { disassemble(app) }
                },
                onCheckNames = {
                    claim(
                        app.newDeck.busy == null,
                        { app.copy(newDeck = app.newDeck.working("Checking every name…")) },
                    ) { checkNames(app) }
                },
                onCreateDeck = {
                    claim(
                        app.newDeck.canCreate,
                        { app.copy(newDeck = app.newDeck.working("Creating…")) },
                    ) { createDeck(app) }
                },
            )
        }
    }

    /**
     * The menu, into the top bar's own slot.
     *
     * A second composition rather than part of the shell, because the
     * header is static markup and a menu drawn at the top of the page
     * hangs below the bar it belongs to. Both read the one `app`, so
     * they cannot disagree about which view is current.
     */
    fun mountNav(root: HTMLElement) {
        navComposition?.dispose()
        navComposition = renderComposable(root = root) {
            AppNav(
                app,
                onState = { next ->
                    val was = app
                    app = next
                    if (next.view != was.view || next.route.rest != was.route.rest) loadFor(next)
                },
                onSignIn = { window.location.href = api.signInUrl(window.location.hash) },
                onSignOut = { signOut() },
            )
        }
    }

    fun unmount() {
        // The offsets belong to the app that recorded them.
        Scroll.forget()
        navComposition?.dispose()
        navComposition = null
        composition?.dispose()
        composition = null
        // Nothing to unlock here: the shell's `DisposableEffect`
        // releases the page as the composition goes away.
    }

    // ------------------------------------------------------- listeners

    private fun listen() {
        if (listening) return
        listening = true

        window.addEventListener("keydown", { raw ->
            val e = raw as KeyboardEvent
            // Left and right step along a deck while a card is open.
            // Only there, and never while something is being typed
            // into, where the arrows move a caret.
            if (app.view == View.CARD && !e.defaultPrevented && !typingInto(e)) {
                val step = when (e.key) {
                    "ArrowLeft" -> app.previousCard
                    "ArrowRight" -> app.nextCard
                    else -> null
                }
                if (step != null) {
                    e.preventDefault()
                    openNamed(step.name, step.nameNorm, "")
                    return@addEventListener
                }
            }
            val next = app.onBrowserKey(e)
            if (next != null) {
                e.preventDefault()
                val was = app
                app = next
                if (next.view != was.view) loadFor(next)
            }
        })

        // Closing the tab is the one exit that loses the list: every
        // other way out keeps it, because the state lives above the
        // wizard. The browser will only show its own wording, but it
        // will not let the page go without asking.
        window.addEventListener("beforeunload", { raw ->
            if (!app.entry.unsaved) return@addEventListener
            raw.preventDefault()
            raw.asDynamic().returnValue = "Your list has not been written to the collection yet."
        })

        // A hand on the screen outranks a restore still in progress.
        // The chase runs for two seconds so it can outlast a slow
        // search, and without this it would drag the page back under
        // somebody who had already started scrolling.
        listOf("touchstart", "wheel").forEach { name ->
            window.addEventListener(name, { Scroll.theyTookOver() }, js("({passive: true})"))
        }

        window.addEventListener("popstate", {
            // An entry this code popped on purpose, closing an overlay
            // by its own X. The overlay is already gone.
            if (OverlayHistory.expected()) return@addEventListener
            app.dismissTop()?.let { app = it }
        })

        window.addEventListener("hashchange", {
            // `held` rather than `app` throughout: the setter would
            // write the address back while the browser is telling us
            // it changed.
            val route = routeFromHash()
            if (route.view != app.view || route.rest != app.route.rest) {
                Scroll.remember(app.hash())
                held = app.navigate(route)
                    .let { if (route.view != View.LIBRARY) it else it.restoredSearch(FilterUrl.fromHash(route.query)) }
                loadFor(app)
                // The browser has no navigation to restore a scroll
                // offset for, so going back lands wherever the screen
                // being left happened to be.
                Scroll.restore(app.hash())
            }
        })
    }

    /**
     * The lists the filter panel offers, read once.
     *
     * Twelve small reads rather than a bespoke endpoint that would
     * have to be kept in step with the panel. A failure is silent: the
     * panel falls back to its typed fields and the rest of the app does
     * not care.
     */
    private fun loadFacets() {
        if (app.facets.loaded) return
        scope.launch {
            try {
                // Four reads, not thirteen. Eleven round trips on
                // every cold load queued behind each other at the one
                // database the search was also using, which is what
                // "D1 is overloaded" looked like from here.
                val all = FacetQueries.everything.map { api.query(it).let { r -> r.cols to r.rows } }
                val d = api.query(FacetQueries.decks)
                app = app.copy(
                    facets = FacetQueries.decodeEverything(
                        all,
                        FacetQueries.decodeDecks(d.cols, d.rows),
                    ),
                )
            } catch (e: Exception) {
                // A panel with typed fields instead of checkbox lists is
                // still a usable panel.
            }
        }
    }

    // ------------------------------------------------------------ work

    /**
     * Claim the action, then do it.
     *
     * `work` only launches, and on this dispatcher the body does not
     * start until the event loop comes back round — so a flag set as
     * the first line of the suspend function is set a whole frame
     * after the press. Both halves of a double tap get through that
     * gap, and because every call carries its own idempotency key the
     * server treats them as two separate pieces of work: a deck made
     * twice, a list added twice.
     *
     * So the flag is written here, synchronously, between the press
     * and the launch. The gate in front of it is the same one the
     * button is disabled by, which means a press that gets through
     * anyway — a stale frame, a keyboard, a script — is refused on
     * the same terms.
     */
    private fun claim(allowed: Boolean, mark: () -> AppState, block: suspend () -> AppState) {
        if (!allowed) return
        app = mark()
        work(block)
    }

    /** Off to the network and back, with the failure surfaced as a toast. */
    private fun work(block: suspend () -> AppState) {
        scope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                app.say(e.message ?: "something went wrong", failed = true)
            } catch (e: Exception) {
                app.say(e.message ?: e.toString(), failed = true)
            }
        }
    }

    private fun token() = app.admin.token.orEmpty()

    /**
     * Fetch what a view needs, and say so while it is happening.
     *
     * Decks and Stats used to go straight to `work`, which leaves
     * `busy` false — so the page rendered its empty state, "No decks
     * yet", over a load that was still in flight, and kept rendering
     * it if the load failed because the failure went to a toast
     * instead of to the page. It read as the decks having vanished.
     */
    /**
     * Nothing scoped is fetched before there is a collection to scope
     * it to.
     *
     * The three pages below are somebody's collection. Until the
     * server has said who this session is, or a key in the address
     * has been resolved, there is no "somebody" — and asking anyway
     * read every collection at once, which is how Kayla's decks got
     * onto Matt's screen. `AppState.collectionKnown` is the gate, and
     * the queries refuse an empty owner as well, so a shell
     * forgetting this cannot leak a row.
     */
    private fun loadFor(s: AppState) {
        if (!s.collectionKnown && s.view in View.COLLECTION) return
        when (s.view) {
            // Only if the rows on screen do not already answer it.
            // Coming back from a card asked the database for the same
            // hundred cards again, and emptied the grid to do it.
            View.LIBRARY -> if (!s.library.fresh) searchSoon()

            View.DECKS -> {
                app = app.fetching(View.DECKS)
                intoPage(View.DECKS) {
                    if (s.route.rest.isEmpty()) loadDecks() else openDeck(s, s.route.rest)
                }
            }

            View.STATS -> {
                app = app.fetching(View.STATS)
                intoPage(View.STATS) { loadStats(s) }
            }

            View.ADMIN -> {
                app = app.fetching(View.ADMIN)
                intoPage(View.ADMIN) { loadPeople() }
            }

            // A card reached by its own address — a link somebody
            // sent, a reload, the back button — rather than by a tap
            // that already knew the card's real name.
            View.CARD -> s.cardRef?.let { ref ->
                if (app.card?.nameNorm != ref.nameNorm) {
                    app = app.copy(
                        card = CardDetail(name = ref.nameNorm, nameNorm = ref.nameNorm).loading(),
                    )
                }
                val label = app.card?.name ?: ref.nameNorm
                intoPage(View.CARD) { loadCard(ref.nameNorm, label) }
            } ?: Unit

            else -> Unit
        }
    }

    /**
     * Like `work`, but a failure lands on the screen that asked for it
     * rather than in a toast that has already gone by the time the
     * empty state is read.
     */
    private fun intoPage(view: View, block: suspend () -> AppState) {
        scope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                app.fetchFailed(e.message ?: "something went wrong", view)
            } catch (e: Exception) {
                app.fetchFailed(e.message ?: e.toString(), view)
            }
        }
    }

    /**
     * Put the search in the address bar without adding a history step.
     *
     * `location.hash =` would push one per keystroke; `replaceState`
     * keeps the link shareable and the back button useful.
     */
    /** Somewhere new. Back should come back here. */
    private fun pushHash(s: AppState) {
        try {
            window.history.pushState(window.history.state, "", s.hash())
        } catch (e: Throwable) {
            replaceHash(s)
        }
    }

    private fun replaceHash(s: AppState) {
        val hash = s.hash()
        try {
            window.history.replaceState(window.history.state, "", hash)
        } catch (e: Throwable) {
            // Not worth failing a search over.
        }
    }

    /** The only path to a search, so a burst of changes is one read. */
    private fun searchSoon() {
        searchJob?.cancel()
        app = app.copy(library = app.library.loading())
        searchJob = scope.launch {
            delay(searchDebounceMs)
            app = try {
                search(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer search replaced this one. Not a failure, and
                // writing one put "Search failed: Job was cancelled"
                // over the grid every time a filter changed mid-flight.
                throw e
            } catch (e: ApiFailure) {
                app.copy(library = app.library.failed(e.message ?: "something went wrong"))
            } catch (e: Exception) {
                app.copy(library = app.library.failed(e.message ?: e.toString()))
            }
        }
    }

    private suspend fun search(s: AppState): AppState {
        // Scoped to the collection on screen, which is your own
        // unless the address names somebody else's.
        val (page, count) = Load.library(s.scopedLibrary())
        val rows = api.query(page)
        val total = api.query(count)
        return app.copy(
            library = app.library.loaded(Rows.cards(rows.cols, rows.rows), Rows.count(total.rows)),
        )
    }

    /**
     * The deck list, re-read.
     *
     * It builds on whatever `app` holds when the read comes back,
     * not on a state captured before it — anything that landed while
     * it was in flight would otherwise be thrown away. It took no
     * argument for exactly that reason, and the two callers that
     * passed one had their work quietly discarded: a created deck
     * stayed on "Creating…" for as long as anybody was willing to
     * watch it.
     */
    private suspend fun loadDecks(): AppState {
        // One collection's decks, not every deck in the database.
        // The collection on screen, or every one of them for a reader
        // the server has said is nobody. Never an empty owner: that is
        // "nobody said yet", which `DeckQueries` refuses outright.
        val r = api.query(DeckQueries.all(app.viewing.ifEmpty { DeckQueries.EVERY }))
        return app.copy(decks = app.decks.loaded(DeckQueries.decode(r.cols, r.rows)))
    }

    private suspend fun openDeck(s: AppState, slug: String): AppState {
        // The list first, because the header needs the deck's own row.
        if (app.decks.decks.isEmpty()) app = loadDecks()
        val r = api.query(DeckQueries.cards(slug))
        // Read `app` again rather than the copy captured before the
        // query: anything that landed while it was in flight — the
        // facet lists, a toast — would otherwise be thrown away.
        val opened = app.copy(decks = app.decks.opened(slug, DeckQueries.decodeCards(r.cols, r.rows)))
            .navigate(Route(View.DECKS, slug))
        // The tokens come from Scryfall and land behind the list
        // rather than holding it up. A deck that shows its cards and
        // fills in its tokens a moment later is right; one that waits
        // on a second service to show anything is not.
        loadTokens(slug)
        return opened
    }

    private fun loadTokens(slug: String) {
        scope.launch {
            val found = scryfall.tokens(app.decks.scryfallIds)
            // Still the same deck? Opening another one while this was
            // in flight must not hang the first deck's tokens on it.
            if (app.decks.openSlug == slug) {
                app = app.copy(decks = app.decks.withTokens(found))
            }
        }
    }

    /**
     * Who is there, for Admin Settings.
     *
     * The server refuses this to anybody without the role, so the
     * failure path is as real as the happy one — an ordinary account
     * that reaches the address sees the refusal rather than an empty
     * list that looks like an empty database.
     */
    private suspend fun loadPeople(): AppState = try {
        app.copy(people = app.people.loaded(api.people(token())))
    } catch (ex: ApiFailure) {
        app.copy(people = app.people.failed(ex.message ?: "that did not work"))
    }

    private suspend fun loadStats(s: AppState): AppState {
        val scope = s.statsScope()
        val r = api.query(StatsQueries.totals(scope))
        return app.copy(stats = app.stats.scopedTo(scope.owner).loaded(StatsQueries.decode(r.cols, r.rows)))
    }

    // ------------------------------------------------------ card detail

    private fun openCard(row: CardRow) {
        openNamed(row.fullName, row.nameNorm, row.owner)
    }

    /**
     * A card opened from somewhere that knows its `name_norm`.
     *
     * `openFound` lowercases the display name to get one, which is
     * near enough for the palette and wrong for anything with an
     * accent or an em dash in it. A deck list has the real column.
     */
    private fun openNamed(name: String, nameNorm: String, owner: String) {
        // The owner is what the row was found under, not part of the
        // card: the page shows every owner's copies.
        app = app.openCard(CardRef(nameNorm), name)
        work { loadCard(nameNorm, name) }
    }

    /**
     * "Full details" on the carousel's sheet.
     *
     * The carousel's history entry is **forgotten** rather than
     * popped. `OverlayHistory` closes an overlay by calling
     * `history.back()`, which is asynchronous — so closing the
     * carousel and opening the card in the same gesture queued a pop
     * that landed after the card's own address had been pushed and
     * took it straight back off. The card page appeared and vanished
     * and the address bar still said the deck.
     *
     * The card's address supersedes the entry instead: `forget`
     * brings the depth down without asking the browser for anything,
     * and the push that follows is what the back gesture lands on.
     */
    /**
     * Who is signed in, asked of the server rather than remembered.
     *
     * The session is an HttpOnly cookie this page cannot read, which
     * is the point of it — so the only way to know is to ask, and the
     * answer stays true after the cookie expires.
     *
     * `credentials: 'include'` because the API is on another domain:
     * without it the browser sends no cookie at all and everybody is
     * a stranger. Written as a cast because Kotlin's binding for the
     * enum has no member for it.
     */
    private fun loadAccount() {
        scope.launch {
            val who = runCatching { fetchAccount() }.getOrNull()
            // Settled either way: "nobody" is an answer, and the
            // scoped pages are waiting on one before they fetch.
            app = app.copy(
                admin = if (who != null) app.admin.signIn(who) else app.admin.settle(),
            )
            // Arrived without naming a collection? Go to your own, and
            // put its key in the address — so the link in the bar is
            // one that can be copied and handed to somebody else.
            // Replaced rather than pushed: it is where you already
            // are, not somewhere you went, so Back must not come back
            // to the address you are being moved off.
            app.homeRoute()?.let { home ->
                window.history.replaceState(null, "", home.toHash())
                app = app.copy(route = home)
            }
            // A gated route was held rather than bounced while the
            // answer was out — a bookmark to `#/entry` must not land
            // on the Library every time — so land it again now that
            // there is an answer to land it against.
            app = app.navigate(app.route)
            resolveCollection()
            // Whatever was waiting on who this is can go now.
            if (app.route.collection.isEmpty()) loadFor(app)
        }
    }

    /**
     * The key in the address, turned into whose collection it is.
     *
     * `cards.owner` holds a slug and an address holds a key, and only
     * the server knows which is which — which is the point: an
     * address names a collection and says nothing at all about who
     * may edit it.
     */
    private fun resolveCollection() {
        val key = app.route.collection
        if (key.isEmpty()) {
            app = app.browsing("")
            // An account with no key in the address is still a
            // collection: their own.
            if (app.collectionKnown) loadFor(app)
            return
        }
        scope.launch {
            val res = runCatching {
                window.fetch("${MtgApi.DEFAULT_BASE}/c/$key").await()
            }.getOrNull()
            if (res == null || !res.ok) {
                app = app.say("no collection at that address", failed = true)
                return@launch
            }
            val body = res.json().await().asDynamic()
            val slug = body.slug as? String ?: return@launch
            app = app.browsing(slug)
            loadFor(app)
        }
    }

    private suspend fun fetchAccount(): Account? {
        val res = window.fetch(
            "${MtgApi.DEFAULT_BASE}/auth/me",
            RequestInit(credentials = "include".unsafeCast<RequestCredentials>()),
        ).await()
        if (!res.ok) return null
        val body = res.json().await().asDynamic()
        val slug = body.slug as? String ?: return null
        return Account(
            slug = slug,
            name = body.name as? String,
            avatar = body.avatar as? String,
            role = (body.role as? String) ?: "user",
        )
    }

    private fun signOut() {
        scope.launch {
            runCatching {
                window.fetch(
                    "${MtgApi.DEFAULT_BASE}/auth/logout",
                    RequestInit(method = "POST", credentials = "include".unsafeCast<RequestCredentials>()),
                ).await()
            }
            app = app.copy(admin = app.admin.signOut()).navigate(app.route)
        }
    }

    private fun openFromCarousel(card: PeekCard) {
        OverlayHistory.forget(1)
        app = app.closing(Overlay.CARD_PEEK)
        openNamed(card.title, card.nameNorm, app.decks.open?.owner.orEmpty())
    }

    private fun openFound(found: Found) {
        val norm = found.name.lowercase()
        app = app.closing(Overlay.PALETTE).openCard(CardRef(norm), found.name)
        work { loadCard(norm, found.name) }
    }

    private suspend fun loadCard(nameNorm: String, label: String): AppState {
        // Run here, decoded in `:core`, so the phone and the browser
        // build the same `CardDetail` out of the same answers.
        val answers = Load.card(nameNorm).map { api.query(it).let { r -> r.cols to r.rows } }
        return app.copy(card = Load.cardDetail(label, nameNorm, answers))
    }

    // ---------------------------------------------------- find and hint

    /** Debounced, and the one in flight is abandoned when a newer starts. */
    private fun find(term: String) {
        findJob?.cancel()
        findJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            try {
                val r = api.query(PaletteQueries.find(term))
                // `term` and not the current one: a slow answer to a word
                // that has since been typed over is dropped rather than
                // shown under what is now in the box.
                app = app.copy(
                    palette = app.palette.found(PaletteQueries.decode(r.cols, r.rows), term),
                )
            } catch (e: Exception) {
                // Typing fast. Not worth an error in a convenience.
            }
        }
    }

    /**
     * The card-name box changed.
     *
     * Against `app`, which is now, rather than against a state the
     * composition captured, which is whenever it last drew. Two edits
     * inside one frame — type a name, clear it — compared the new
     * name with itself, decided nothing had moved, and left the
     * narrowed results on screen under an empty box.
     *
     * Only a real change asks the database again. Everything else the
     * suggestion list reports is a list opening or closing, and a
     * search for each of those turned every touch on a phone into a
     * reload.
     */
    private fun nameTyped(c: Completion) {
        val was = app.complete.term
        app = app.typedCardName(c)
        if (c.term == was) return
        if (c.worthAsking) lookup(c.term)
        searchSoon()
    }

    /**
     * The commander box, which has its own suggestions.
     *
     * Separate from the Library's: typing a commander here must not
     * rewrite the search filter on a screen nobody is looking at.
     */
    private fun commanderTyped(c: Completion) {
        val was = app.newDeck.hint.term
        app = app.copy(newDeck = app.newDeck.hinting(c))
        if (c.term == was || !c.worthAsking) return
        commanderJob?.cancel()
        commanderJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            val names = scryfall.complete(c.term)
            app = app.copy(newDeck = app.newDeck.copy(hint = app.newDeck.hint.suggested(names)))
        }
    }

    /**
     * A file dropped on the wizard's card list.
     *
     * The same reading and the same limits as mass entry, landing in
     * the wizard instead of the entry box.
     */
    private fun readDeckFiles(files: List<File>) {
        scope.launch {
            val texts = mutableListOf<String>()
            files.forEach { f ->
                val bytes = f.size.toLong()
                if (bytes > Upload.MAX_BYTES) {
                    app = app.say("${f.name} is too big (${Upload.size(bytes)})", failed = true)
                    return@forEach
                }
                val text = readText(f)
                if (text == null) app = app.say("could not read ${f.name}", failed = true)
                else texts += text
            }
            if (texts.isEmpty()) return@launch
            val merged = Upload.merge(app.newDeck.list, texts.joinToString("\n"))
            app = app.copy(newDeck = app.newDeck.type(merged)).say(
                "Loaded ${DeckList.entries(merged).size} cards",
            )
        }
    }

    private fun lookup(term: String) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            val names = scryfall.complete(term)
            app = app.copy(complete = app.complete.suggested(names))
        }
    }

    // ---------------------------------------------------------- export

    private suspend fun exportList(s: AppState, where: ExportTo): AppState {
        val r = api.query(Export.query(s.library.filters))
        val text = Export.decklist(Rows.cards(r.cols, r.rows))
        return when (where) {
            ExportTo.FILE -> {
                download(Export.filename(today()), text)
                app.say("Exported ${r.rows.size} cards")
            }

            ExportTo.CLIPBOARD -> {
                if (copy(text)) app.say("${r.rows.size} cards copied")
                else app.say("could not reach the clipboard", failed = true)
            }
        }
    }

    /**
     * The open deck, handed over.
     *
     * The link is this page. The list is the deck itself, which is
     * what somebody wants when they are going to build it rather than
     * read about it — and it needs no read, because the cards are
     * already on screen.
     */
    private suspend fun shareDeck(s: AppState, what: ShareWhat, where: ExportTo): AppState {
        val deck = s.decks.open ?: return s.say("No deck open", failed = true)
        val text = when (what) {
            ShareWhat.LINK -> Share.link(s)
            ShareWhat.DECKLIST -> Export.deck(s.decks.cards)
        }
        val name = when (what) {
            ShareWhat.LINK -> "${deck.slug}-link.txt"
            ShareWhat.DECKLIST -> Export.deckFilename(deck.slug, today())
        }
        return when (where) {
            ExportTo.FILE -> {
                download(name, text)
                app.say(if (what == ShareWhat.LINK) "Link downloaded" else "${s.decks.totalCards} cards exported")
            }

            ExportTo.CLIPBOARD ->
                if (copy(text)) {
                    app.say(if (what == ShareWhat.LINK) "Link copied" else "${s.decks.totalCards} cards copied")
                } else {
                    app.say("could not reach the clipboard", failed = true)
                }
        }
    }

    /**
     * A link to what is on screen, on the clipboard.
     *
     * The share sheet where there is one — a phone browser — and the
     * clipboard everywhere else, which is what a desk actually wants.
     */
    private fun share() {
        val url = Share.link(app)
        val nav = window.navigator.asDynamic()
        if (nav.share != null) {
            try {
                nav.share(js("({})").unsafeCast<Any>().also {
                    it.asDynamic().title = Share.title(app)
                    it.asDynamic().url = url
                })
                return
            } catch (e: Throwable) {
                // No share sheet after all. The clipboard still works.
            }
        }
        scope.launch { app = if (copy(url)) app.say("Link copied") else app.say(url) }
    }

    /** True when it landed. `writeText` is a promise and can be refused. */
    private suspend fun copy(text: String): Boolean = try {
        val clip = window.navigator.asDynamic().clipboard
        if (clip == null) {
            false
        } else {
            (clip.writeText(text) as kotlin.js.Promise<Unit>).await()
            true
        }
    } catch (e: Throwable) {
        false
    }

    private fun download(name: String, text: String) {
        val blob = Blob(arrayOf(text), BlobPropertyBag(type = "text/plain"))
        val url = org.w3c.dom.url.URL.createObjectURL(blob)
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = url
        a.setAttribute("download", name)
        document.body?.appendChild(a)
        a.click()
        a.remove()
        org.w3c.dom.url.URL.revokeObjectURL(url)
    }

    // ------------------------------------------------------------ files

    private fun readFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val chunks = mutableListOf<String>()
            val names = mutableListOf<String>()
            for (f in files) {
                val bytes = (f.size as Number).toLong()
                if (Upload.tooBig(bytes)) {
                    app = app.say("${f.name} is too big (${Upload.size(bytes)})", failed = true)
                    continue
                }
                val text = readText(f)
                if (text == null) app = app.say("could not read ${f.name}", failed = true)
                else { chunks += text; names += f.name }
            }
            if (chunks.isEmpty()) return@launch
            val incoming = chunks.joinToString("\n")
            app = app.copy(entry = app.entry.type(Upload.merge(app.entry.list, incoming)))
                .say(Upload.describe(names, incoming))
                .shareUsed()
        }
    }

    private suspend fun readText(file: File): String? = suspendCancellableCoroutine { cont ->
        val reader = FileReader()
        reader.onload = { cont.resume(reader.result as? String) }
        reader.onerror = { cont.resume(null) }
        reader.readAsText(file)
    }

    // ------------------------------------------------------------ decks

    private fun editDeck(slug: String) {
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(deckEdit = DeckEditState.of(deck, app.decks.cards)).opening(Overlay.DECK_EDIT)
    }

    // ------------------------------------------------ one card at a time

    /**
     * Maintenance on the deck's own page.
     *
     * There is no per-card endpoint and there does not need to be:
     * `/decks/list` takes the whole list and works out the
     * difference, so a swap goes through the same checking, the same
     * sourcing and the same record as a full rewrite.
     */
    private fun startTweak(card: DeckCard?, kind: Tweak?) {
        val deck = app.decks.open ?: return
        val commander = app.decks.cards.filter { it.role == "commander" }
            .joinToString(" // ") { it.name }
            .ifEmpty { deck.commanderName.orEmpty() }
        val tweak = if (card == null) {
            DeckTweak.add(deck, commander)
        } else {
            DeckTweak.on(deck, commander, card, kind)
        }
        app = app.copy(deckTweak = tweak).opening(Overlay.DECK_TWEAK)
    }

    /** Debounced, and the one in flight is abandoned when a newer starts. */
    private fun findForTweak(term: String) {
        tweakJob?.cancel()
        tweakJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            try {
                val r = api.query(PaletteQueries.find(term))
                val owned = PaletteQueries.decode(r.cols, r.rows)
                app = app.copy(deckTweak = app.deckTweak?.searched(owned))
                // And anything else that is a real card. A deck can
                // want one nobody owns yet; the plan calls that "to
                // buy" and says so before it writes.
                val named = scryfall.complete(term)
                app = app.copy(deckTweak = app.deckTweak?.searched(owned, named))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A finder that cannot reach the server is not an
                // error worth a dialog; the box still takes typing.
            }
        }
    }

    private suspend fun planTweak(s: AppState): AppState {
        val t = s.deckTweak ?: return app
        app = app.copy(deckTweak = t.working())
        return try {
            val plan = api.setDeckList(token(), t.slug, t.commander, t.listAfter(s.decks.cards), dryRun = true)
            app.copy(deckTweak = app.deckTweak?.planned(plan))
        } catch (ex: ApiFailure) {
            app.copy(deckTweak = app.deckTweak?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun applyTweak(s: AppState): AppState {
        val t = s.deckTweak ?: return app
        app = app.copy(deckTweak = t.working())
        return try {
            val plan = api.setDeckList(token(), t.slug, t.commander, t.listAfter(s.decks.cards), dryRun = false)
            // Reopen the deck so the list on screen is the list that
            // is now stored, rather than the one that was.
            openDeck(app.copy(deckTweak = app.deckTweak?.finished()), t.slug)
                .closing(Overlay.DECK_TWEAK)
                .say(t.summary + if (plan.buying > 0) " — ${plan.buying} bought" else "")
        } catch (ex: ApiFailure) {
            app.copy(deckTweak = app.deckTweak?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun reviewDeck(s: AppState): AppState {
        val e = s.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.slug, e.commander, e.list, dryRun = true)
            app.copy(deckEdit = app.deckEdit?.planned(plan))
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun saveDeck(s: AppState): AppState {
        val e = s.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.slug, e.commander, e.list, dryRun = false)
            openDeck(app.copy(deckEdit = app.deckEdit?.finished(plan)), e.slug)
                .closing(Overlay.DECK_EDIT)
                .say("Saved — ${plan.cardCount} cards" + if (plan.buying > 0) ", ${plan.buying} bought" else "")
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private fun askRename(slug: String) {
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(rename = RenameState(slug = deck.slug, was = deck.name))
            .opening(Overlay.RENAME)
    }

    /**
     * Write the new name, then follow the deck to its new address.
     *
     * The slug moves with the name, so staying where we are would
     * leave the page pointing at a deck that is no longer there.
     */
    private suspend fun renameDeck(s: AppState): AppState {
        val r = s.rename ?: return app
        return try {
            val done = api.renameDeck(token(), r.slug, r.name.trim())
            app = app.copy(rename = app.rename?.finished())
            loadDecks()
                .closing(Overlay.RENAME)
                .navigate(Route(View.DECKS, done.slug))
                .say("Renamed to ${done.name}")
        } catch (ex: ApiFailure) {
            app.copy(rename = app.rename?.failed(ex.message ?: "that did not work"))
        }
    }

    private fun askDisassemble(slug: String) {
        // The overlay covers the button it was pressed from, but not
        // until the next frame — two presses in one frame were two dry
        // runs against the same deck.
        if (app.disassemble?.busy == true) return
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(
            disassemble = DisassembleState(slug, deck.name, deck.owner).working(),
        ).opening(Overlay.DISASSEMBLE)
        work {
            val plan = api.disassemble(token(), slug, dryRun = true)
            app.copy(disassemble = app.disassemble?.planned(plan))
        }
    }

    private suspend fun disassemble(s: AppState): AppState {
        val d = s.disassemble ?: return app
        app = app.copy(disassemble = d.working())
        return try {
            val r = api.disassemble(token(), d.slug, dryRun = false)
            app = app.copy(disassemble = app.disassemble?.finished())
            loadDecks()
                .closing(Overlay.DISASSEMBLE)
                .navigate(View.DECKS)
                .say("Disassembled ${d.deckName} — ${r.freed} back in bulk")
        } catch (ex: ApiFailure) {
            app.copy(disassemble = app.disassemble?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun checkNames(s: AppState): AppState {
        val n = s.newDeck
        app = app.copy(newDeck = n.working("Checking every name…"))
        return try {
            val v = api.validateRaw(n.commander + "\n" + n.list)
            app.copy(newDeck = app.newDeck.validated(v))
        } catch (ex: ApiFailure) {
            app.copy(newDeck = app.newDeck.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun createDeck(s: AppState): AppState {
        val n = s.newDeck
        app = app.copy(newDeck = n.working("Creating…"))
        return try {
            api.createDeck(
                token = token(),
                name = n.name,
                format = n.format!!.slug,
                owner = app.viewing,
                commander = n.commander.ifBlank { null },
                list = n.list,
                dryRun = false,
            )
            app = app.copy(newDeck = app.newDeck.finished())
            loadDecks().say("Created ${n.name}")
        } catch (ex: ApiFailure) {
            app.copy(newDeck = app.newDeck.failed(ex.message ?: "that did not work"))
        }
    }

    // ----------------------------------------------------------- odds

    private fun routeFromHash() = Route.parse(window.location.hash)

    private fun now(): String = js("new Date().toISOString()") as String

    private fun today(): String = now().substringBefore('T')
}

/**
 * `localStorage`, behind the core's own interface.
 *
 * Wrapped in a try because a private window with site data blocked
 * throws on the accessor itself, and the history panel not working is
 * not a reason for the app not to start.
 */
private class BrowserStore : Store {
    override fun get(key: String): String? = try {
        window.localStorage.getItem(key)
    } catch (e: Throwable) {
        null
    }

    override fun put(key: String, value: String) {
        try {
            window.localStorage.setItem(key, value)
        } catch (e: Throwable) {
            // Full, or blocked. Nothing here is worth failing over.
        }
    }

    override fun remove(key: String) {
        try {
            window.localStorage.removeItem(key)
        } catch (e: Throwable) {
        }
    }
}

/**
 * Keeping the history stack the same depth as the overlay stack.
 *
 * An overlay that opens pushes an entry, so back pops it and closes the
 * overlay rather than leaving the route. An overlay closed by its own X
 * takes the entry back off, so the history never fills with dead steps
 * — and the pop that causes must not then close a second overlay,
 * which is what the counter is for.
 */
private object OverlayHistory {
    private var depth = 0
    private var pending = 0

    /**
     * Give up an entry without popping it.
     *
     * For an overlay whose entry is about to be superseded by a real
     * navigation — see `openFromCarousel`. Popping it would undo the
     * navigation that replaced it.
     */
    fun forget(n: Int) {
        depth = (depth - n).coerceAtLeast(0)
    }

    fun sync(want: Int) {
        while (depth < want) {
            window.history.pushState(null, "")
            depth++
        }
        while (depth > want) {
            pending++
            depth--
            window.history.back()
        }
    }

    /** True when this pop was one we asked for. */
    fun expected(): Boolean {
        if (pending == 0) {
            if (depth > 0) depth--
            return false
        }
        pending--
        return true
    }
}

fun main() {
    window.asDynamic().mtgApp = MtgApp
    // Kept for the page that mounts only the wizard.
    window.asDynamic().mtgEntry = MtgApp
}
