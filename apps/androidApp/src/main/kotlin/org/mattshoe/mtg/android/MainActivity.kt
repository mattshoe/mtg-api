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
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckQueries
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Export
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PaletteQueries
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.StatsQueries
import org.mattshoe.mtg.core.Store
import org.mattshoe.mtg.core.Table
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

    private val api = MtgApi()
    private val scryfall = Scryfall()
    private val prefs by lazy { getSharedPreferences("mtg", Context.MODE_PRIVATE) }
    private val store: Store by lazy { PrefsStore(prefs) }

    private var app by mutableStateOf(AppState())

    private var lookupJob: Job? = null
    private var findJob: Job? = null

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
            admin = Admin(prefs.getString("token", "").orEmpty().ifBlank { null }),
            history = EntryHistory.load(store),
        )
        loadFor(app)

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
                            work {
                                val t = api.unlock(password)
                                prefs.edit().putString("token", t).apply()
                                app.copy(admin = app.admin.unlock(t)).say("Admin mode on")
                            }
                        },
                        onSearch = { work { search() } },
                        onOpenDeck = { slug -> work { openDeck(slug) } },
                        onRunSql = { work { runSql() } },
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
                        onReviewDeck = { work { reviewDeck() } },
                        onSaveDeck = { work { saveDeck() } },
                        onAskDisassemble = { slug -> askDisassemble(slug) },
                        onDisassemble = { work { disassemble() } },
                        onCheckNames = { work { checkNames() } },
                        onCreateDeck = { work { createDeck() } },
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ work

    private fun token() = app.admin.token.orEmpty()

    /** Off to the network and back, with the failure surfaced as a toast. */
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
                app = app.copy(palette = app.palette.found(PaletteQueries.decode(r.cols, r.rows)))
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
