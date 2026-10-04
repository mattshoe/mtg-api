package org.mattshoe.mtg.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckQueries
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Export
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.FacetQueries
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PaletteQueries
import org.mattshoe.mtg.core.RenameState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.Share
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.StatsQueries
import org.mattshoe.mtg.core.Store
import org.mattshoe.mtg.core.Table
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.Upload
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.query
import java.time.Instant

/**
 * The whole app, on Android.
 *
 * The sibling of `MtgApp.mount` on the web, and deliberately the same
 * shape: it owns an `AppState`, runs whatever queries the shared core
 * hands it, and renders `AppShell`. Nothing here decides what a screen
 * needs or whether a write is allowed — `:core` does, and the web
 * obeys the same object.
 */
class MainActivity : ComponentActivity() {

    private var api = MtgApi()
    private val scryfall = Scryfall()
    private val prefs by lazy { getSharedPreferences("mtg", Context.MODE_PRIVATE) }
    private val store: Store by lazy { PrefsStore(prefs) }

    private var held by mutableStateOf(AppState())

    /**
     * The state. Every write goes through here rather than through
     * `onCreate`'s one `onState` callback, because `onUnlock` and the
     * rest of `work`/`claim` write `app` directly and never touch it.
     *
     * `AdminToken.sync` diffs the token on every single write, so the
     * nav row's Lock button, `Shortcuts`' own toggle, and whatever
     * calls `app = ` next all clear the persisted copy the same way —
     * there is no second step for any of them to skip.
     */
    private var app: AppState
        get() = held
        set(value) {
            AdminToken.sync(store, held.admin, value.admin)
            held = value
        }

    private var lookupJob: Job? = null
    private var findJob: Job? = null
    private var tweakJob: Job? = null
    private var commanderJob: Job? = null

    /**
     * The seam a test reaches through, the same way the web's
     * `useForTesting` does: nothing here changes what the app does,
     * only what it talks to. Called before `onCreate` — real
     * `MtgApi()` has already been constructed, but nothing has used
     * it yet.
     */
    internal fun useForTesting(api: MtgApi) {
        this.api = api
    }

    /** What `onCreate` landed, for a test that cannot see a private field. */
    internal fun stateForTesting(): AppState = app

    /** A second load, the way a config change or a re-entry would ask for one. */
    internal fun loadFacetsForTesting() = loadFacets()

    /**
     * Everything, because a narrow list greys out the file you actually
     * want in Android's picker — the same guesswork about MIME types
     * that broke the share sheet.
     */
    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) readFiles(uris) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        app = AppState(
            admin = Admin(AdminToken.restore(store)),
            history = EntryHistory.load(store),
        )
        loadFor(app)
        loadFacets()

        setContent {
            MtgTheme {
                Surface {
                    AppShell(
                        state = app,
                        onState = { next ->
                            val was = app
                            app = next
                            if (next.view != was.view || next.route.rest != was.route.rest) loadFor(next)
                        },
                        onUnlock = { password ->
                            claim(app.admin.canTry, { app.copy(admin = app.admin.tries()) }) {
                                try {
                                    val t = api.unlock(password)
                                    // Persisted by the `app` setter, which
                                    // diffs the token on every write.
                                    app.copy(admin = app.admin.unlock(t)).say("Admin mode on")
                                } catch (e: Exception) {
                                    app = app.copy(admin = app.admin.gaveUp())
                                    throw e
                                }
                            }
                        },
                        onSearch = { work { search() } },
                        // Through the address, the way every other
                        // screen is reached. Loading the deck without
                        // naming it in the route left the route saying
                        // "the deck list", so a card opened from the
                        // deck remembered the list as where it came
                        // from and back landed there instead of in the
                        // deck. `loadFor` sees the slug and fetches.
                        onOpenDeck = { slug ->
                            val next = app.navigate(Route(View.DECKS, slug))
                            app = next
                            loadFor(next)
                        },
                        onRunSql = {
                            claim(
                                app.console.canRun,
                                { app.copy(console = app.console.running()) },
                            ) { runSql() }
                        },
                        onPreviewEntry = {
                            claim(
                                app.entry.canPreview,
                                { app.copy(entry = app.entry.working("Checking…")) },
                            ) {
                                val s = app.entry
                                app.copy(
                                    entry = s.previewed(
                                        api.cards(token(), s.direction!!, s.owner!!, s.list, dryRun = true),
                                    ),
                                )
                            }
                        },
                        onApplyEntry = {
                            claim(
                                app.entry.canApply,
                                { app.copy(entry = app.entry.working("Applying…")) },
                            ) {
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
                        onExport = { work { exportList() } },
                        onOpenCard = { row -> openCard(row) },
                        onOpenFound = { found -> openFound(found) },
                        onOpenNamed = { name, norm, owner -> openNamed(name, norm, owner) },
                        onFind = { term -> find(term) },
                        onLookup = { term -> lookup(term) },
                        onPickFile = { pickFiles.launch(arrayOf("*/*")) },
                        onReuse = { e ->
                            app = app.copy(entry = app.history.reuse(e)).navigate(View.ENTRY)
                        },
                        onClearHistory = {
                            app = app.copy(history = app.history.cleared())
                            EntryHistory.save(store, app.history)
                        },
                        onEditDeck = { slug -> editDeck(slug) },
                        onReviewDeck = {
                            claim(
                                app.deckEdit?.canReview == true,
                                { app.copy(deckEdit = app.deckEdit?.working()) },
                            ) { reviewDeck() }
                        },
                        onSaveDeck = {
                            claim(
                                app.deckEdit?.canSave == true,
                                { app.copy(deckEdit = app.deckEdit?.working()) },
                            ) { saveDeck() }
                        },
                        onAskDisassemble = { slug -> askDisassemble(slug) },
                        onAskRename = { slug -> askRename(slug) },
                        onSaveRename = {
                            claim(
                                app.rename?.canSave == true,
                                { app.copy(rename = app.rename?.working()) },
                            ) { renameDeck() }
                        },
                        onAddCard = { startTweak(null, Tweak.ADD) },
                        onTweak = { card, kind -> startTweak(card, kind) },
                        onTweakFind = { term -> findForTweak(term) },
                        onTweakPreview = {
                            claim(
                                app.deckTweak?.ready == true,
                                { app.copy(deckTweak = app.deckTweak?.working()) },
                            ) { planTweak() }
                        },
                        onTweakApply = {
                            claim(
                                app.deckTweak?.canApply == true,
                                { app.copy(deckTweak = app.deckTweak?.working()) },
                            ) { applyTweak() }
                        },
                        onShare = { what, where -> work { shareDeck(what, where) } },
                        onDisassemble = {
                            claim(
                                app.disassemble?.canGo == true,
                                { app.copy(disassemble = app.disassemble?.working()) },
                            ) { disassemble() }
                        },
                        onCheckNames = {
                            claim(
                                app.newDeck.busy == null,
                                { app.copy(newDeck = app.newDeck.working("Checking every name…")) },
                            ) { checkNames() }
                        },
                        onCreateDeck = {
                            claim(
                                app.newDeck.canCreate,
                                { app.copy(newDeck = app.newDeck.working("Creating…")) },
                            ) { createDeck() }
                        },
                        onCommanderTyped = { c -> commanderTyped(c) },
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ work

    private fun token() = app.admin.token.orEmpty()

    /** Off to the network and back, with the failure surfaced as a toast. */
    /**
     * Claim the action, then do it. Sibling of `claim` on the web.
     *
     * `work` only launches: the body starts on the next pass of the
     * loop, so a flag set as the first line of the suspend function is
     * set after the press rather than with it, and both halves of a
     * double tap get through. Every call carries its own idempotency
     * key, so the server makes both pieces of work.
     */
    private fun claim(allowed: Boolean, mark: () -> AppState, block: suspend () -> AppState) {
        if (!allowed) return
        app = mark()
        work(block)
    }

    private fun work(block: suspend () -> AppState) {
        lifecycleScope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                app.say(e.message ?: "something went wrong")
            } catch (e: Exception) {
                app.say(e.message ?: e.toString())
            }
        }
    }

    /**
     * Fetch what a view needs, and say so while it is happening.
     *
     * `fetching`/`fetchFailed` are the core's, shared with the
     * website: going straight to `work` leaves `busy` false, so the
     * screen renders its empty state — "No decks yet" — over a load
     * still in flight, and keeps rendering it when the load fails
     * because the failure goes to a toast that has already gone.
     */
    private fun loadFor(s: AppState) {
        when (s.view) {
            View.LIBRARY -> {
                app = app.fetching(View.LIBRARY)
                intoPage(View.LIBRARY) { search() }
            }

            View.DECKS -> {
                app = app.fetching(View.DECKS)
                intoPage(View.DECKS) {
                    if (s.route.rest.isEmpty()) loadDecks() else openDeck(s.route.rest)
                }
            }

            View.STATS -> {
                app = app.fetching(View.STATS)
                intoPage(View.STATS) { loadStats() }
            }

            else -> Unit
        }
    }

    /**
     * The lists the filter panel offers, read once.
     *
     * The sibling of the web's `loadFacets` — same guard, same silent
     * failure, same shape — so the phone's type checklist and deck
     * picker fill from the collection instead of sitting empty
     * forever. Launched once from `onCreate`, not from `loadFor`,
     * because these lists do not change per screen and must not be
     * refetched on every navigation.
     */
    private fun loadFacets() {
        if (app.facets.loaded) return
        lifecycleScope.launch {
            try {
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

    /** Like `work`, but the failure lands on the screen that asked. */
    private fun intoPage(view: View, block: suspend () -> AppState) {
        lifecycleScope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                app.fetchFailed(e.message ?: "something went wrong", view)
            } catch (e: Exception) {
                app.fetchFailed(e.message ?: e.toString(), view)
            }
        }
    }

    private suspend fun search(): AppState {
        val (page, count) = Load.library(app.library)
        val rows = api.query(page)
        val total = api.query(count)
        return app.copy(
            library = app.library.loaded(Rows.cards(rows.cols, rows.rows), Rows.count(total.rows)),
        )
    }

    private suspend fun loadDecks(): AppState {
        val r = api.query(DeckQueries.all())
        return app.copy(decks = app.decks.loaded(DeckQueries.decode(r.cols, r.rows)))
    }

    private suspend fun openDeck(slug: String): AppState {
        val all = if (app.decks.decks.isEmpty()) loadDecks() else app
        val r = api.query(DeckQueries.cards(slug))
        return all.copy(decks = all.decks.opened(slug, DeckQueries.decodeCards(r.cols, r.rows)))
    }

    private suspend fun loadStats(): AppState {
        val scope = Load.scopeFrom(app.route.rest)
        val r = api.query(StatsQueries.totals(scope))
        return app.copy(stats = app.stats.scopedTo(scope.owner).loaded(StatsQueries.decode(r.cols, r.rows)))
    }

    private suspend fun runSql(): AppState {
        val r = api.queryRaw(app.console.sql, emptyList())
        return app.copy(console = app.console.ran(Table.of(r.cols, r.rows), r.n))
    }

    // ------------------------------------------------------ card detail

    private fun openCard(row: CardRow) {
        app = app.openCard(CardRef(row.nameNorm), row.fullName)
        work { loadCard(row.nameNorm, row.fullName) }
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

    private fun openFound(found: Found) {
        val norm = found.name.lowercase()
        app = app.closing(Overlay.PALETTE).openCard(CardRef(norm), found.name)
        work { loadCard(norm, found.name) }
    }

    private suspend fun loadCard(nameNorm: String, label: String): AppState {
        val (printings, uses, legal, rules) = Load.card(nameNorm)
        val p = api.query(printings)
        val u = api.query(uses)
        val l = api.query(legal)
        val r = api.query(rules)
        return app.copy(
            card = CardDetail(
                name = label,
                nameNorm = nameNorm,
                printings = CardQueries.decodePrintings(p.cols, p.rows),
                usedIn = CardQueries.decodeUses(u.cols, u.rows),
                legalities = CardQueries.decodeLegalities(l.cols, l.rows),
                rulings = CardQueries.decodeRulings(r.cols, r.rows),
            ),
        )
    }

    // ---------------------------------------------------- find and hint

    /** Debounced, and the one in flight is abandoned when a newer starts. */
    private fun find(term: String) {
        findJob?.cancel()
        findJob = lifecycleScope.launch {
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

    private fun lookup(term: String) {
        lookupJob?.cancel()
        lookupJob = lifecycleScope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            app = app.copy(complete = app.complete.suggested(scryfall.complete(term)))
        }
    }

    /**
     * The new deck wizard's commander box, which has its own
     * suggestions — separate from the Library's, so typing a
     * commander here must not touch the search filter.
     */
    private fun commanderTyped(c: Completion) {
        if (!c.worthAsking) return
        commanderJob?.cancel()
        commanderJob = lifecycleScope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            app = app.copy(newDeck = app.newDeck.copy(hint = app.newDeck.hint.suggested(scryfall.complete(c.term))))
        }
    }

    // ------------------------------------------------------------ deck

    private fun askRename(slug: String) {
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(rename = RenameState(slug = deck.slug, was = deck.name))
            .opening(Overlay.RENAME)
    }

    /**
     * Write the new name, then follow the deck to its new address.
     *
     * The slug moves with the name, so staying where we are would
     * leave the screen pointing at a deck that is no longer there.
     */
    private suspend fun renameDeck(): AppState {
        val r = app.rename ?: return app
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

    /**
     * Open the sheet on one card, or on none for an addition.
     *
     * The commander comes off the list on screen rather than the
     * deck row, because a deck with two of them has both and the row
     * only names one.
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
        tweakJob = lifecycleScope.launch {
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
                // Typing fast. Not worth an error in a convenience.
            }
        }
    }

    private suspend fun planTweak(): AppState {
        val t = app.deckTweak ?: return app
        return try {
            val plan = api.setDeckList(
                token(), t.slug, t.commander, t.listAfter(app.decks.cards), dryRun = true,
            )
            app.copy(deckTweak = app.deckTweak?.planned(plan))
        } catch (ex: ApiFailure) {
            app.copy(deckTweak = app.deckTweak?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun applyTweak(): AppState {
        val t = app.deckTweak ?: return app
        return try {
            val plan = api.setDeckList(
                token(), t.slug, t.commander, t.listAfter(app.decks.cards), dryRun = false,
            )
            app = app.copy(deckTweak = app.deckTweak?.finished())
            // Reopen the deck so the list on screen is the list that is
            // now stored, rather than the one that was.
            openDeck(t.slug)
                .closing(Overlay.DECK_TWEAK)
                .say(t.summary + if (plan.buying > 0) " — ${plan.buying} bought" else "")
        } catch (ex: ApiFailure) {
            app.copy(deckTweak = app.deckTweak?.failed(ex.message ?: "that did not work"))
        }
    }

    /**
     * The open deck, handed over.
     *
     * The link is this screen's address on the website, so what is
     * pasted into a chat opens the same deck for whoever gets it. The
     * list is the deck itself, which is what somebody wants when they
     * are going to build it rather than read about it.
     *
     * Both halves of `ExportTo` end up on the clipboard here, because
     * a phone has nowhere useful to put a loose text file and pasting
     * is what the next app is going to ask for either way. The wording
     * still distinguishes them, so a tap on Download does not look
     * like it did nothing.
     */
    private fun shareDeck(what: ShareWhat, where: ExportTo): AppState {
        val deck = app.decks.open ?: return app.say("No deck open")
        val text = when (what) {
            ShareWhat.LINK -> Share.link(app)
            ShareWhat.DECKLIST -> Export.deck(app.decks.cards)
        }
        val name = when (what) {
            ShareWhat.LINK -> "${deck.slug}-link.txt"
            ShareWhat.DECKLIST -> Export.deckFilename(deck.slug, today())
        }
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(name, text))
        return app.say(
            when (what) {
                ShareWhat.LINK -> "Link copied"
                ShareWhat.DECKLIST -> "${app.decks.totalCards} cards copied"
            },
        )
    }

    // ----------------------------------------------------------- export

    /**
     * The whole filtered set as a decklist, on the clipboard.
     *
     * The web downloads a file because a browser can; a phone pastes it
     * into whatever asked for it, which is what an export is for here.
     * The text is identical — `Export.decklist`, shared.
     */
    private suspend fun exportList(): AppState {
        val r = api.query(Export.query(app.library.filters))
        val text = Export.decklist(Rows.cards(r.cols, r.rows))
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(Export.filename(today()), text))
        return app.say("Copied ${r.rows.size} cards as a decklist")
    }

    // ------------------------------------------------------------ files

    private fun readFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            val chunks = mutableListOf<String>()
            val names = mutableListOf<String>()
            uris.forEach { uri ->
                val bytes = runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                if (bytes > 0 && Upload.tooBig(bytes)) {
                    app = app.say("that file is too big (${Upload.size(bytes)})")
                    return@forEach
                }
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                if (text == null) app = app.say("could not read that file")
                else {
                    chunks += text
                    names += uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                }
            }
            if (chunks.isEmpty()) return@launch
            val incoming = chunks.joinToString("\n")
            app = app.copy(entry = app.entry.type(Upload.merge(app.entry.list, incoming)))
                .say(Upload.describe(names, incoming))
                .shareUsed()
        }
    }

    // ------------------------------------------------------------ decks

    private fun editDeck(slug: String) {
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(deckEdit = DeckEditState.of(deck, app.decks.cards)).opening(Overlay.DECK_EDIT)
    }

    private suspend fun reviewDeck(): AppState {
        val e = app.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.slug, e.commander, e.list, dryRun = true)
            app.copy(deckEdit = app.deckEdit?.planned(plan))
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun saveDeck(): AppState {
        val e = app.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.slug, e.commander, e.list, dryRun = false)
            app = app.copy(deckEdit = app.deckEdit?.finished(plan))
            openDeck(e.slug).closing(Overlay.DECK_EDIT)
                .say("Saved — ${plan.cardCount} cards" + if (plan.buying > 0) ", ${plan.buying} bought" else "")
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private fun askDisassemble(slug: String) {
        if (app.disassemble?.busy == true) return
        val deck = app.decks.decks.firstOrNull { it.slug == slug } ?: return
        app = app.copy(disassemble = DisassembleState(slug, deck.name, deck.owner).working())
            .opening(Overlay.DISASSEMBLE)
        work {
            val plan = api.disassemble(token(), slug, dryRun = true)
            app.copy(disassemble = app.disassemble?.planned(plan))
        }
    }

    private suspend fun disassemble(): AppState {
        val d = app.disassemble ?: return app
        app = app.copy(disassemble = d.working())
        return try {
            val r = api.disassemble(token(), d.slug, dryRun = false)
            app = app.copy(disassemble = app.disassemble?.finished())
            loadDecks().closing(Overlay.DISASSEMBLE)
                .copy(decks = app.decks.close())
                .say("Disassembled ${d.deckName} — ${r.freed} back in bulk")
        } catch (ex: ApiFailure) {
            app.copy(disassemble = app.disassemble?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun checkNames(): AppState {
        val n = app.newDeck
        app = app.copy(newDeck = n.working("Checking every name…"))
        return try {
            app.copy(newDeck = app.newDeck.validated(api.validateRaw(n.commander + "\n" + n.list)))
        } catch (ex: ApiFailure) {
            app.copy(newDeck = app.newDeck.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun createDeck(): AppState {
        val n = app.newDeck
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
            app = app.copy(newDeck = app.newDeck.finished())
            loadDecks().say("Created ${n.name}")
        } catch (ex: ApiFailure) {
            app.copy(newDeck = app.newDeck.failed(ex.message ?: "that did not work"))
        }
    }

    private fun now(): String = Instant.now().toString()

    private fun today(): String = now().substringBefore('T')
}

/** `SharedPreferences`, behind the core's own interface. */
class PrefsStore(private val prefs: android.content.SharedPreferences) : Store {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}
