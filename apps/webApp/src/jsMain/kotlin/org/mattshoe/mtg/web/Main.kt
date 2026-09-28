package org.mattshoe.mtg.web

import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckQueries
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Export
import org.mattshoe.mtg.core.FacetQueries
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PaletteQueries
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.StatsQueries
import org.mattshoe.mtg.core.Store
import org.mattshoe.mtg.core.Table
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
    private val api = MtgApi()
    private val scryfall = Scryfall()
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * The state lives out here rather than inside the composition
     * because the window listeners — keydown, popstate — have to reach
     * it, and a listener registered per recomposition is a listener
     * registered a hundred times.
     */
    private var app by mutableStateOf(AppState())

    private var listening = false
    private var lookupJob: Job? = null
    private var findJob: Job? = null

    private val store: Store = BrowserStore()

    fun mount(root: HTMLElement, sharedList: String?, token: String) {
        unmount()
        app = AppState(admin = Admin(token.ifBlank { null }), history = EntryHistory.load(store))
            .let { if (sharedList.isNullOrBlank()) it.navigate(routeFromHash()) else it.withShare(sharedList) }
        listen()
        loadFor(app)
        loadFacets()

        composition = renderComposable(root = root) {
            val state = app

            // Every overlay that opens pushes a history entry, so the
            // back gesture dismisses what is on top instead of
            // navigating out from under it.
            LaunchedEffect(state.overlays.stack.size) {
                OverlayHistory.sync(state.overlays.stack.size)
            }

            AppShell(
                state = state,
                onState = { next ->
                    val was = app
                    app = next
                    if (next.view != was.view || next.route.rest != was.route.rest) {
                        window.location.hash = next.route.toHash().removePrefix("#")
                        loadFor(next)
                    }
                },
                onUnlock = { password ->
                    work {
                        val t = api.unlock(password)
                        store.put("mtg.admin", """{"token":"$t","expires_at":null}""")
                        app.copy(admin = app.admin.unlock(t)).say("Admin mode on")
                    }
                },
                onSearch = { work { search(app) } },
                onOpenDeck = { slug -> work { openDeck(app, slug) } },
                onRunSql = { work { runSql(app) } },
                onPreviewEntry = {
                    work {
                        val s = app.entry
                        app.copy(
                            entry = s.previewed(
                                api.cards(token(), s.direction!!, s.owner!!, s.list, dryRun = true),
                            ),
                        )
                    }
                },
                onApplyEntry = {
                    work {
                        val s = app.entry
                        val done = app.copy(
                            entry = s.finished(
                                api.cards(token(), s.direction!!, s.owner!!, s.list, dryRun = false),
                            ),
                        ).shareUsed().recordEntry(now())
                        EntryHistory.save(store, done.history)
                        done
                    }
                },
                onExport = { work { exportList(app) } },
                onOpenCard = { row -> openCard(row) },
                onOpenFound = { found -> openFound(found) },
                onFind = { term -> find(term) },
                onLookup = { term -> lookup(term) },
                onFiles = { files -> readFiles(files) },
                onReuse = { e -> app = app.copy(entry = app.history.reuse(e)).navigate(View.ENTRY) },
                onClearHistory = {
                    app = app.copy(history = app.history.cleared())
                    EntryHistory.save(store, app.history)
                },
                onEditDeck = { slug -> editDeck(slug) },
                onReviewDeck = { work { reviewDeck(app) } },
                onSaveDeck = { work { saveDeck(app) } },
                onAskDisassemble = { slug -> askDisassemble(slug) },
                onDisassemble = { work { disassemble(app) } },
                onCheckNames = { work { checkNames(app) } },
                onCreateDeck = { work { createDeck(app) } },
            )
        }
    }

    fun unmount() {
        composition?.dispose()
        composition = null
    }

    // ------------------------------------------------------- listeners

    private fun listen() {
        if (listening) return
        listening = true

        window.addEventListener("keydown", { raw ->
            val e = raw as KeyboardEvent
            val next = app.onBrowserKey(e)
            if (next != null) {
                e.preventDefault()
                val was = app
                app = next
                if (next.view != was.view) {
                    window.location.hash = next.route.toHash().removePrefix("#")
                    loadFor(next)
                }
            }
        })

        window.addEventListener("popstate", {
            // An entry this code popped on purpose, closing an overlay
            // by its own X. The overlay is already gone.
            if (OverlayHistory.expected()) return@addEventListener
            app.dismissTop()?.let { app = it }
        })

        window.addEventListener("hashchange", {
            val route = routeFromHash()
            if (route.view != app.view || route.rest != app.route.rest) {
                app = app.navigate(route)
                loadFor(app)
            }
        })
    }

    /**
     * The lists the filter panel offers, read once.
     *
     * Thirteen small reads rather than a bespoke endpoint that would
     * have to be kept in step with the panel. A failure is silent: the
     * panel falls back to its typed fields and the rest of the app does
     * not care.
     */
    private fun loadFacets() {
        if (app.facets.loaded) return
        scope.launch {
            try {
                val columns = FacetQueries.all.dropLast(1).map { Rows.column(api.query(it).rows) }
                val d = api.query(FacetQueries.decks)
                app = app.copy(
                    facets = FacetQueries.assemble(columns, FacetQueries.decodeDecks(d.cols, d.rows)),
                )
            } catch (e: Exception) {
                // A panel with typed fields instead of checkbox lists is
                // still a usable panel.
            }
        }
    }

    // ------------------------------------------------------------ work

    /** Off to the network and back, with the failure surfaced as a toast. */
    private fun work(block: suspend () -> AppState) {
        scope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                app.say(e.message ?: "something went wrong")
            } catch (e: Exception) {
                app.say(e.message ?: e.toString())
            }
        }
    }

    private fun token() = app.admin.token.orEmpty()

    private fun loadFor(s: AppState) {
        when (s.view) {
            View.LIBRARY -> work { search(s) }
            View.DECKS -> work { if (s.route.rest.isEmpty()) loadDecks(s) else openDeck(s, s.route.rest) }
            View.STATS -> work { loadStats(s) }
            else -> Unit
        }
    }

    private suspend fun search(s: AppState): AppState {
        val (page, count) = Load.library(s.library)
        val rows = api.query(page)
        val total = api.query(count)
        return app.copy(
            library = app.library.loaded(Rows.cards(rows.cols, rows.rows), Rows.count(total.rows)),
        )
    }

    private suspend fun loadDecks(s: AppState): AppState {
        val r = api.query(DeckQueries.all())
        return app.copy(decks = app.decks.loaded(DeckQueries.decode(r.cols, r.rows)))
    }

    private suspend fun openDeck(s: AppState, slug: String): AppState {
        val all = if (app.decks.decks.isEmpty()) loadDecks(s) else app
        val r = api.query(DeckQueries.cards(slug))
        return all.copy(decks = all.decks.opened(slug, DeckQueries.decodeCards(r.cols, r.rows)))
            .navigate(Route(View.DECKS, slug))
    }

    private suspend fun loadStats(s: AppState): AppState {
        val scope = Load.scopeFrom(s.route.rest)
        val r = api.query(StatsQueries.totals(scope))
        return app.copy(stats = app.stats.scopedTo(scope.owner).loaded(StatsQueries.decode(r.cols, r.rows)))
    }

    private suspend fun runSql(s: AppState): AppState {
        val r = api.queryRaw(s.console.sql, emptyList())
        return app.copy(console = app.console.ran(Table.of(r.cols, r.rows), r.n))
    }

    // ------------------------------------------------------ card detail

    private fun openCard(row: CardRow) {
        app = app.copy(card = CardDetail(name = row.fullName, owner = row.owner).loading())
            .opening(Overlay.CARD)
        work { loadCard(row.nameNorm, row.owner, row.fullName) }
    }

    private fun openFound(found: Found) {
        app = app.closing(Overlay.PALETTE)
            .copy(card = CardDetail(name = found.name, owner = found.owner).loading())
            .opening(Overlay.CARD)
        work { loadCard(found.name.lowercase(), found.owner, found.name) }
    }

    private suspend fun loadCard(nameNorm: String, owner: String, label: String): AppState {
        val (printings, uses) = Load.card(nameNorm, owner)
        val p = api.query(printings)
        val u = api.query(uses)
        return app.copy(
            card = CardDetail(
                name = label,
                owner = owner,
                printings = CardQueries.decodePrintings(p.cols, p.rows),
                usedIn = CardQueries.decodeUses(u.cols, u.rows),
            ),
        )
    }

    // ---------------------------------------------------- find and hint

    /** Debounced, and the one in flight is abandoned when a newer starts. */
    private fun find(term: String) {
        findJob?.cancel()
        findJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            try {
                val r = api.query(PaletteQueries.find(term))
                app = app.copy(palette = app.palette.found(PaletteQueries.decode(r.cols, r.rows)))
            } catch (e: Exception) {
                // Typing fast. Not worth an error in a convenience.
            }
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

    private suspend fun exportList(s: AppState): AppState {
        val r = api.query(Export.query(s.library.filters))
        val text = Export.decklist(Rows.cards(r.cols, r.rows))
        download(Export.filename(today()), text)
        return app.say("Exported ${r.rows.size} rows")
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
                    app = app.say("${f.name} is too big (${Upload.size(bytes)})")
                    continue
                }
                val text = readText(f)
                if (text == null) app = app.say("could not read ${f.name}")
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

    private fun askDisassemble(slug: String) {
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
            loadDecks(app.copy(disassemble = app.disassemble?.finished()))
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
                owner = n.owner!!,
                commander = n.commander.ifBlank { null },
                list = n.list,
                dryRun = false,
            )
            loadDecks(app.copy(newDeck = app.newDeck.finished())).say("Created ${n.name}")
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
