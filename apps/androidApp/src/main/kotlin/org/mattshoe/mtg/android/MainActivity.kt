package org.mattshoe.mtg.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
import org.mattshoe.mtg.core.Deeplink
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
import org.mattshoe.mtg.core.GitHubReleases
import org.mattshoe.mtg.core.Share
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.SharedFile
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

    // `Wiring.apiBase` is null everywhere but an end-to-end test,
    // where the fake worker is already listening on a loopback port
    // by the time the system builds this activity. See [Wiring].
    private var api = MtgApi(Wiring.apiBase ?: MtgApi.DEFAULT_BASE)
    private var scryfall = Scryfall()
    private var github = GitHubReleases()
    private val prefs by lazy { getSharedPreferences("mtg", Context.MODE_PRIVATE) }
    private val store: Store by lazy { PrefsStore(prefs) }
    private var downloads: Downloads = MediaStoreDownloads(this)

    /**
     * The state, and the work in flight, both outliving this activity.
     *
     * Android recreates the activity on every configuration change,
     * so anything held in a field here is gone on a rotation. See
     * [MtgViewModel].
     */
    private val model: MtgViewModel by viewModels()

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
        get() = model.app
        set(value) {
            AdminToken.sync(store, model.app.admin, value.admin)
            model.app = value
        }

    // All on the ViewModel, so a rotation mid-request neither cancels
    // the work nor loses the handle to it.
    private var lookupJob: Job?
        get() = model.lookupJob
        set(v) { model.lookupJob = v }
    private var findJob: Job?
        get() = model.findJob
        set(v) { model.findJob = v }
    private var tweakJob: Job?
        get() = model.tweakJob
        set(v) { model.tweakJob = v }
    private var commanderJob: Job?
        get() = model.commanderJob
        set(v) { model.commanderJob = v }

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

    /** The same seam for Scryfall, which the deck's tokens come from. */
    internal fun useScryfallForTesting(scryfall: Scryfall) {
        this.scryfall = scryfall
    }

    /** And for GitHub, which the release notes come from. */
    internal fun useGitHubForTesting(github: GitHubReleases) {
        this.github = github
    }

    /**
     * The seam a test reaches through for a download, the same way
     * [useForTesting] swaps the network. See [Downloads].
     */
    internal fun useDownloadsForTesting(downloads: Downloads) {
        this.downloads = downloads
    }

    /**
     * Where the app's coroutines run: the ViewModel's scope, not the
     * activity's. `lifecycleScope` is cancelled when a configuration
     * change destroys the activity, so a rotation part-way through a
     * search abandoned the search.
     */
    private val scope get() = model.viewModelScope

    /**
     * A second link, arriving at an app that is already open.
     *
     * `MainActivity` is `singleTask`, so the system hands the link to
     * the running instance rather than starting another. An app that
     * only reads `intent` in `onCreate` follows the first link of a
     * session and silently ignores every one after it.
     */
    override fun onNewIntent(incoming: Intent) {
        super.onNewIntent(incoming)
        // So `intent` is the new one for anything that reads it later.
        setIntent(incoming)
        if (followLink(incoming)) {
            // The link may name somebody's collection, and which
            // collection decides what every query asks for.
            resolveCollection()
            loadFor(app)
        } else if (takeShare(incoming)) {
            loadFor(app)
        }
    }

    /**
     * A file or text shared from another app, into the entry box.
     *
     * Returns whether it moved. The reading is `SharedFile`; what a
     * share does to the app is `AppState.withShare`, the same one the
     * website's share target uses.
     */
    private fun takeShare(from: Intent?): Boolean {
        if (from?.action != Intent.ACTION_SEND && from?.action != Intent.ACTION_SEND_MULTIPLE) return false
        val share = SharedFile.read(this, from)
        app = if (share.list.isBlank()) app.say(share.problem, failed = true) else app.withShare(share.list)
        return true
    }

    /**
     * Follow an `mtg.mattshoe.org` link, if that is what this is.
     *
     * Returns whether it moved. Where the link goes is
     * `Deeplink.landing` in `:core`, shared with the website's own
     * address parsing, so a link cannot open one place in the browser
     * and another on the phone. Anything else — a plain launch, a
     * share from another app, somebody else's host — is left alone.
     */
    private fun followLink(from: Intent?): Boolean {
        if (from?.action != Intent.ACTION_VIEW) return false
        val next = Deeplink.landing(app, from.dataString) 
        if (next == app) return false
        app = next
        return true
    }

    /** What `onCreate` landed, for a test that cannot see a private field. */
    internal fun stateForTesting(): AppState = app

    /** Lets a test put the activity into a state it did not reach by pressing anything. */
    internal fun setStateForTesting(state: AppState) {
        app = state
    }

    /** `shareDeck`, for a test that cannot see a private method. */
    internal fun shareDeckForTesting(what: ShareWhat, where: ExportTo): AppState {
        app = shareDeck(what, where)
        return app
    }

    /** What the picker hands back, for a test that cannot open a picker. */
    internal fun readFilesForTesting(uris: List<Uri>) = readFiles(uris)

    /** `exportList`, for a test that cannot see a private method. */
    internal suspend fun exportListForTesting(where: ExportTo): AppState {
        app = exportList(where)
        return app
    }

    /**
     * Sign in with Google, natively.
     *
     * Credential Manager puts the sheet up, Google hands back an ID
     * token, and the Worker turns that into a session — the same
     * session the website gets, verified the same way. See
     * `GoogleSignIn`.
     */
    private fun signInWithGoogle() {
        scope.launch {
            val idToken = try {
                GoogleSignIn.idToken(this@MainActivity)
            } catch (e: GetCredentialCancellationException) {
                // Somebody changed their mind. That is not an error and
                // deserves no toast.
                return@launch
            } catch (e: Exception) {
                app = app.say(e.message ?: "could not reach Google", failed = true)
                return@launch
            }
            app = try {
                val session = api.signInWithGoogle(idToken)
                val who = session.account
                if (who == null) {
                    app.say("that sign-in came back without an account", failed = true)
                } else {
                    app.copy(admin = app.admin.signIn(who, session.token))
                        .say("Signed in as ${who.shownName}")
                }
            } catch (e: Exception) {
                app.say(e.message ?: "could not sign in", failed = true)
            }
        }
    }

    /** End the session, on the server as well as here. */
    private fun signOutOfGoogle() {
        val held = app.admin.token
        app = app.copy(admin = app.admin.signOut()).navigate(app.route)
        if (!held.isNullOrBlank()) scope.launch { runCatching { api.signOut(held) } }
    }

    /**
     * What a held token turns out to be.
     *
     * It is a session if the server recognises it, the old password
     * token if it does not, and nothing at all if it has lapsed —
     * and only the server can tell those apart.
     */
    /**
     * The key in a link, turned into whose collection it is.
     *
     * A shared address carries a key and `cards.owner` holds a slug,
     * and only the server knows which is which — which is the point:
     * an address names a collection and says nothing about who may
     * edit it. Without this, a link somebody sent opened the reader's
     * own collection, which is the thing a link is for not doing.
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
            val found = runCatching { api.collection(key) }.getOrNull()
            if (found == null) {
                app = app.say("no collection at that address", failed = true)
                return@launch
            }
            app = app.browsing(found.key)
            loadFor(app)
        }
    }

    private fun restoreAccount() {
        val held = app.admin.token
        if (held.isNullOrBlank()) {
            // Nothing to ask about, so it is already answered.
            app = app.copy(admin = app.admin.settle())
            return
        }
        scope.launch {
            val who = runCatching { api.me(held) }.getOrNull()
            // Settled either way: a stale session is "nobody", which
            // the scoped pages are waiting on before they fetch.
            app = app.copy(
                admin = if (who != null) app.admin.signIn(who, held) else app.admin.settle(),
            )
            // A gated route was held rather than bounced while the
            // answer was out — a share or a deep link to the wizard
            // must not land on the Library every time — so land it
            // again now that there is an answer to land it against.
            app = app.navigate(app.route)
            // The screen that was waiting for an answer can go now.
            // Without this the screen stayed empty until something
            // else navigated.
            loadFor(app)
        }
    }

    /** A second load, the way a config change or a re-entry would ask for one. */
    internal fun loadFacetsForTesting() = loadFacets()

    /** The opening fetch for whatever the route names. */
    internal fun loadForTesting() = loadFor(app)

    /** The token load, so a test can wait for it instead of sleeping. */
    internal val tokensJob: Job? get() = model.tokensJob
    internal val releasesJob: Job? get() = model.releasesJob
    internal val tasksJob: Job? get() = model.tasksJob

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

        // Only on a genuinely new start. `onCreate` runs again after
        // every configuration change, and re-running this reset `app`
        // to a fresh `AppState` — which emptied the app just as
        // thoroughly as holding the state in a field did.
        if (!model.started) {
            model.started = true
            app = AppState(
                admin = Admin(AdminToken.restore(store)),
                history = EntryHistory.load(store),
            )
            // Whatever was held last time might be a session, might be
            // the old password, might be stale. Asking the server
            // which is the only way to find out, and it happens
            // alongside the first fetch rather than in front of it —
            // the app is useful to somebody who is not signed in.
            restoreAccount()
            resolveCollection()
            // A link that started the app decides where it opens,
            // before the first fetch, so nothing is read for the
            // Library and then thrown away for the deck.
            if (!followLink(intent)) takeShare(intent)
            loadFor(app)
            loadFacets()
        }

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
                        onSetRole = { person, role ->
                            // One row at a time: the press greys out
                            // the row it was made on and leaves the
                            // rest live.
                            if (app.people.changing == null) {
                                app = app.copy(people = app.people.changing(person.key))
                                scope.launch {
                                    app = try {
                                        api.setRole(token(), person.key, role)
                                        app.copy(
                                            people = app.people.changed(person.key, role),
                                        ).say("${person.shownName} is now $role")
                                    } catch (ex: ApiFailure) {
                                        val why = ex.message ?: "that did not work"
                                        app.copy(people = app.people.refused(why))
                                            .say(why, failed = true)
                                    }
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
                        onOpenDeck = { key ->
                            val next = app.navigate(Route(View.DECKS, key))
                            app = next
                            loadFor(next)
                        },
                        onPreviewEntry = {
                            claim(
                                app.entry.canPreview,
                                { app.copy(entry = app.entry.working("Checking…")) },
                            ) {
                                val s = app.entry
                                app.copy(
                                    entry = s.previewed(
                                        api.cards(token(), s.direction!!, app.viewing, s.list, dryRun = true),
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
                                        api.cards(token(), s.direction!!, app.viewing, s.list, dryRun = false),
                                    ),
                                ).shareUsed().recordEntry(now())
                                EntryHistory.save(store, done.history)
                                done
                            }
                        },
                        onExport = { where -> work { exportList(where) } },
                        onOpenCard = { row -> openCard(row) },
                        onOpenFound = { found -> openFound(found) },
                        onOpenNamed = { name, norm, owner -> openNamed(name, norm, owner) },
                        // "Full details" on the carousel's sheet.
                        // Nothing to account for here — the phone has
                        // no history entry per overlay — so it is the
                        // close and the open, in that order.
                        onSignIn = { signInWithGoogle() },
                        onSignOut = { signOutOfGoogle() },
                        onOpenPeeked = { card ->
                            app = app.closing(Overlay.CARD_PEEK)
                            openNamed(card.title, card.nameNorm, app.decks.open?.owner.orEmpty())
                        },
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
                        onEditDeck = { key -> editDeck(key) },
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
                        onAskDisassemble = { key -> askDisassemble(key) },
                        onAskRename = { key -> askRename(key) },
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
                        onShareCard = { link -> app = shareCard(link) },
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
        scope.launch {
            app = try {
                block()
            } catch (e: ApiFailure) {
                // `failed = true`, the same as the website's own
                // `work`. Without it a refused password, a dead
                // network and a successful export all came up in the
                // same toast with the same colour, and the one
                // person using this is colourblind, so the wording
                // was the only thing telling them apart.
                app.say(e.message ?: "something went wrong", failed = true)
            } catch (e: Exception) {
                app.say(e.message ?: e.toString(), failed = true)
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

            View.ADMIN -> {
                app = app.fetching(View.ADMIN)
                intoPage(View.ADMIN) { loadPeople() }
                loadReleases()
                if (s.route.rest.isEmpty()) loadTasks()
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
    /**
     * The in-flight facet load, so a test can wait for it.
     *
     * Not for the app's benefit — nothing here joins it. It exists
     * because the alternative is a test that depends on a dispatcher
     * running the coroutine inline, and that test passed alone and
     * failed in company depending on what else had touched
     * `Dispatchers.Main` first. A job you can join is deterministic
     * whoever ran before you.
     */
    internal val facetsJob: Job? get() = model.facetsJob

    /**
     * Why the facet load gave up, if it did.
     *
     * Production ignores a facet failure on purpose — a filter panel
     * with typed fields is still usable. That makes the failure
     * invisible, which cost three attempts at a flaky test that could
     * only ever report "facets never loaded" and never why.
     */
    internal val facetsError: Exception? get() = model.facetsError

    /**
     * Whether the facet load actually wrote its result.
     *
     * Set immediately after the assignment, so a test can tell "the
     * load never produced anything" from "the load produced it and
     * something else overwrote it" — which are the two remaining
     * stories behind an unloaded `facets`, and which the state alone
     * cannot distinguish.
     */
    internal val facetsApplied: Boolean get() = model.facetsApplied

    private fun loadFacets() {
        if (app.facets.loaded) return
        model.facetsError = null
        model.facetsApplied = false
        model.facetsJob = scope.launch {
            try {
                val all = FacetQueries.everything.map { api.query(it).let { r -> r.cols to r.rows } }
                val d = api.query(FacetQueries.decks)
                app = app.copy(
                    facets = FacetQueries.decodeEverything(
                        all,
                        FacetQueries.decodeDecks(d.cols, d.rows),
                    ),
                )
                model.facetsApplied = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallowed. `catch (e: Exception)` below would
                // have caught this too, which is how a cancelled load
                // used to be indistinguishable from a failed one — and
                // `MainActivityFacetsTest` could then only report the
                // symptom, "facets never loaded", with no cause.
                throw e
            } catch (e: Exception) {
                // A panel with typed fields instead of checkbox lists is
                // still a usable panel. Kept, but no longer silent: the
                // throwable is held so a test can say what actually
                // went wrong instead of only that nothing arrived.
                model.facetsError = e
            }
        }
    }

    /** Like `work`, but the failure lands on the screen that asked. */
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

    private suspend fun search(): AppState {
        // Scoped to the collection on screen, which is your own
        // unless the address names somebody else's. Unscoped, a
        // search returned every collection's cards at once.
        val (page, count) = Load.library(app.scopedLibrary())
        val rows = api.query(page)
        val total = api.query(count)
        return app.copy(
            library = app.library.loaded(Rows.cards(rows.cols, rows.rows), Rows.count(total.rows)),
        )
    }

    private suspend fun loadDecks(): AppState {
        // One collection's decks, not every deck in the database.
        // The collection on screen, or every one of them for a reader
        // the server has said is nobody. Never an empty owner: that is
        // "nobody said yet", which `DeckQueries` refuses outright.
        val r = api.query(DeckQueries.all(app.viewing.ifEmpty { DeckQueries.EVERY }))
        return app.copy(decks = app.decks.loaded(DeckQueries.decode(r.cols, r.rows)))
    }

    private suspend fun openDeck(key: String): AppState {
        val all = if (app.decks.decks.isEmpty()) loadDecks() else app
        val r = api.query(DeckQueries.cards(key))
        val opened = all.copy(decks = all.decks.opened(key, DeckQueries.decodeCards(r.cols, r.rows)))
        // Started here and not awaited, the same as the website: a
        // deck that shows its cards and fills in its tokens a moment
        // later is right; one that waits on a second service to show
        // anything is not.
        loadTokens(key, opened.decks.scryfallIds)
        return opened
    }

    /**
     * What the deck's cards make, from Scryfall's `all_parts`.
     *
     * Android has never asked. `TokenList` has been at the bottom of
     * the deck page since the port and the list it draws has been
     * empty every time, because `withTokens` was called in exactly
     * one place in the repository and that place was the website.
     * An empty list draws nothing, so the section read as one that
     * had been taken away. Matt: "what the fuck happened to the
     * tokens section at the bottom??"
     *
     * The ids are passed in rather than read off `app`, because this
     * is started from inside `openDeck` — before the state it just
     * built has been assigned — and reading `app` there would ask
     * about the deck you were on a moment ago.
     */
    private fun loadTokens(key: String, ids: List<String>) {
        model.tokensJob = scope.launch {
            // Let the deck land first.
            //
            // This is launched from inside `openDeck`, before the
            // state it just built has been assigned, and
            // `viewModelScope` dispatches on `Main.immediate` — so a
            // launched coroutine runs *synchronously* up to its first
            // real suspension. Against a fake network that never
            // suspends, the whole token load finished before
            // `app = opened` executed, and the assignment then
            // overwrote the tokens it had just written. CI found it;
            // the same test had been passing locally on the ordering
            // going the other way.
            //
            // `yield` puts this behind the assignment whatever the
            // engine does.
            yield()
            // Swallowed on purpose, the way the website swallows it.
            // Scryfall being down is not a reason for a deck to show
            // an error; it is a reason for the deck to have no token
            // list, which is also what a deck with no tokens looks
            // like.
            val found = runCatching { scryfall.tokens(ids) }.getOrDefault(emptyList())
            // Still the same deck? Opening another one while this was
            // in flight must not hang the first deck's tokens on it.
            // Asked of the route rather than the loaded deck, because
            // the route names the deck from the moment you navigate
            // and the loaded one only once its cards are back.
            if (app.route.rest == key && app.view == View.DECKS) {
                app = app.copy(decks = app.decks.withTokens(found))
            }
        }
    }

    /**
     * The builds that shipped, for the release notes on Admin Settings.
     *
     * Beside the people rather than after them: one is GitHub and the
     * other is the Worker, and neither should wait on the other. Once
     * per visit to the list; a person's page reuses what is there.
     * Sibling of the website's `loadReleases`.
     */
    private fun loadReleases() {
        if (app.releases.busy || app.releases.rows.isNotEmpty()) return
        app = app.copy(releases = app.releases.loading())
        model.releasesJob = scope.launch {
            app = try {
                // Asked first, then copied. See `loadPeople`.
                val found = github.releases()
                app.copy(releases = app.releases.loaded(found))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.copy(releases = app.releases.failed(e.message ?: "that did not work"))
            }
        }
    }

    /**
     * The intake requests and where each one is, for Admin Settings.
     *
     * Every visit to the list, unlike the release notes: a task's
     * status changes minute to minute. Opening one person is the same
     * view and does not ask again. Sibling of the website's `loadTasks`.
     */
    private fun loadTasks() {
        if (app.tasks.busy) return
        app = app.copy(tasks = app.tasks.loading())
        model.tasksJob = scope.launch {
            app = try {
                val found = github.tasks()
                app.copy(tasks = app.tasks.loaded(found))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.copy(tasks = app.tasks.failed(e.message ?: "that did not work"))
            }
        }
    }

    /**
     * Who is there, for Admin Settings.
     *
     * The server refuses this to anybody without the role, so an
     * ordinary account that reaches the address sees the refusal
     * rather than an empty list that looks like an empty database.
     */
    private suspend fun loadPeople(): AppState = try {
        // Asked first, then copied. `app.copy(people = ...(api.people()))`
        // reads `app` before the call suspends and writes that snapshot
        // back when it returns, over whatever landed meanwhile — which
        // was the release notes, loading beside this, left on "Loading…"
        // for good.
        val found = api.people(token())
        app.copy(people = app.people.loaded(found))
    } catch (ex: ApiFailure) {
        app.copy(people = app.people.failed(ex.message ?: "that did not work"))
    }

    private suspend fun loadStats(): AppState {
        val scope = app.statsScope()
        val r = api.query(StatsQueries.totals(scope))
        return app.copy(stats = app.stats.scopedTo(scope.owner).loaded(StatsQueries.decode(r.cols, r.rows)))
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
        val answers = Load.card(nameNorm).map { api.query(it).let { a -> a.cols to a.rows } }
        return app.copy(card = Load.cardDetail(nameNorm, label, answers))
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

    private fun lookup(term: String) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
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
        commanderJob = scope.launch {
            delay(Completion.DEBOUNCE_MS.toLong())
            app = app.copy(newDeck = app.newDeck.copy(hint = app.newDeck.hint.suggested(scryfall.complete(c.term))))
        }
    }

    // ------------------------------------------------------------ deck

    private fun askRename(key: String) {
        val deck = app.decks.decks.firstOrNull { it.key == key } ?: return
        app = app.copy(rename = RenameState(key = deck.key, was = deck.name))
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
            val done = api.renameDeck(token(), r.key, r.name.trim())
            app = app.copy(rename = app.rename?.finished())
            loadDecks()
                .closing(Overlay.RENAME)
                .navigate(Route(View.DECKS, done.key))
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
                // Typing fast. Not worth an error in a convenience.
            }
        }
    }

    private suspend fun planTweak(): AppState {
        val t = app.deckTweak ?: return app
        return try {
            val plan = api.setDeckList(
                token(), t.key, t.commander, t.listAfter(app.decks.cards), dryRun = true,
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
                token(), t.key, t.commander, t.listAfter(app.decks.cards), dryRun = false,
            )
            app = app.copy(deckTweak = app.deckTweak?.finished())
            // Reopen the deck so the list on screen is the list that is
            // now stored, rather than the one that was.
            openDeck(t.key)
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
     * `where` decides where it lands — see [Downloads] for why Download
     * still falls back to the clipboard on some phones, and why the
     * toast always says which one actually happened.
     */
    /**
     * A link to the open card, on the clipboard.
     *
     * The clipboard rather than the share sheet, the same as every
     * other share on this app, and the toast says "copied" rather
     * than "shared" so the wording never claims something that did
     * not happen.
     */
    internal fun shareCard(link: String): AppState {
        copyToClipboard("${app.card?.name.orEmpty().ifBlank { "card" }}-link.txt", link)
        return app.say("Link copied")
    }

    private fun shareDeck(what: ShareWhat, where: ExportTo): AppState {
        val deck = app.decks.open ?: return app.say("No deck open")
        val text = when (what) {
            ShareWhat.LINK -> Share.link(app)
            ShareWhat.DECKLIST -> Export.deck(app.decks.cards)
        }
        val name = when (what) {
            ShareWhat.LINK -> Export.deckFilename(deck.name, "link")
            ShareWhat.DECKLIST -> Export.deckFilename(deck.name, today())
        }
        val copiedLabel = if (what == ShareWhat.LINK) "Link copied" else "${app.decks.totalCards} cards copied"
        return when (where) {
            ExportTo.CLIPBOARD -> {
                copyToClipboard(name, text)
                app.say(copiedLabel)
            }

            ExportTo.FILE -> when (downloads.save(name, text)) {
                DownloadResult.SAVED -> app.say(
                    if (what == ShareWhat.LINK) "Link downloaded" else "${app.decks.totalCards} cards exported",
                )

                DownloadResult.UNSUPPORTED_OS -> {
                    copyToClipboard(name, text)
                    app.say("$copiedLabel — downloads need Android 10 or newer")
                }

                DownloadResult.FAILED -> {
                    copyToClipboard(name, text)
                    app.say("could not save the file — copied instead")
                }
            }
        }
    }

    // ----------------------------------------------------------- export

    /**
     * The whole filtered set as a decklist.
     *
     * The web downloads a file because a browser always can; a phone
     * can too, from Android 10 — see [Downloads]. Below that, or if
     * the write itself fails, this falls back to the clipboard and the
     * toast says so rather than claiming a download that did not
     * happen. The text is identical either way — `Export.decklist`,
     * shared with the web.
     */
    private suspend fun exportList(where: ExportTo): AppState {
        val r = api.query(Export.query(app.library.filters))
        val text = Export.decklist(Rows.cards(r.cols, r.rows))
        val name = Export.filename(today())
        val copiedLabel = "Copied ${r.rows.size} cards as a decklist"
        return when (where) {
            ExportTo.CLIPBOARD -> {
                copyToClipboard(name, text)
                app.say(copiedLabel)
            }

            ExportTo.FILE -> when (downloads.save(name, text)) {
                DownloadResult.SAVED -> app.say("Exported ${r.rows.size} cards")

                DownloadResult.UNSUPPORTED_OS -> {
                    copyToClipboard(name, text)
                    app.say("$copiedLabel — downloads need Android 10 or newer")
                }

                DownloadResult.FAILED -> {
                    copyToClipboard(name, text)
                    app.say("could not save the file — copied instead")
                }
            }
        }
    }

    private fun copyToClipboard(name: String, text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(name, text))
    }

    // ------------------------------------------------------------ files

    private fun readFiles(uris: List<Uri>) {
        scope.launch {
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
            // Into whichever box is on screen — the deck wizard's, when
            // it is open. This always wrote the entry box behind it.
            app = app.uploaded(names, chunks.joinToString("\n"))
        }
    }

    // ------------------------------------------------------------ decks

    private fun editDeck(key: String) {
        val deck = app.decks.decks.firstOrNull { it.key == key } ?: return
        app = app.copy(deckEdit = DeckEditState.of(deck, app.decks.cards)).opening(Overlay.DECK_EDIT)
    }

    private suspend fun reviewDeck(): AppState {
        val e = app.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.key, e.commander, e.list, dryRun = true)
            app.copy(deckEdit = app.deckEdit?.planned(plan))
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private suspend fun saveDeck(): AppState {
        val e = app.deckEdit ?: return app
        app = app.copy(deckEdit = e.working())
        return try {
            val plan = api.setDeckList(token(), e.key, e.commander, e.list, dryRun = false)
            app = app.copy(deckEdit = app.deckEdit?.finished(plan))
            openDeck(e.key).closing(Overlay.DECK_EDIT)
                .say("Saved — ${plan.cardCount} cards" + if (plan.buying > 0) ", ${plan.buying} bought" else "")
        } catch (ex: ApiFailure) {
            app.copy(deckEdit = app.deckEdit?.failed(ex.message ?: "that did not work"))
        }
    }

    private fun askDisassemble(key: String) {
        if (app.disassemble?.busy == true) return
        val deck = app.decks.decks.firstOrNull { it.key == key } ?: return
        app = app.copy(disassemble = DisassembleState(key, deck.name, deck.ownerName.ifEmpty { deck.owner }).working())
            .opening(Overlay.DISASSEMBLE)
        work {
            val plan = api.disassemble(token(), key, dryRun = true)
            app.copy(disassemble = app.disassemble?.planned(plan))
        }
    }

    private suspend fun disassemble(): AppState {
        val d = app.disassemble ?: return app
        app = app.copy(disassemble = d.working())
        return try {
            val r = api.disassemble(token(), d.key, dryRun = false)
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
            val made = api.createDeck(
                token = token(),
                name = n.name,
                format = n.format!!.slug,
                collection = app.viewing,
                commander = n.commander.ifBlank { null },
                list = n.list,
                dryRun = false,
            )
            app = app.copy(newDeck = app.newDeck.finished(made.key.orEmpty()))
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
