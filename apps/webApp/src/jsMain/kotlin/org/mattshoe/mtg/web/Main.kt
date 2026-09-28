package org.mattshoe.mtg.web

import androidx.compose.runtime.Composition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.DeckQueries
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.StatsQueries
import org.mattshoe.mtg.core.Table
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.query
import org.w3c.dom.HTMLElement

/**
 * The web app, on the shared core.
 *
 * Owns `AppState`, runs the queries the core hands it, and renders the
 * shell. Every rule it obeys — which tabs are visible, what a screen
 * needs, whether Apply may be offered — comes from `:core`, shared with
 * Android.
 */
@JsExport
object MtgApp {

    private var composition: Composition? = null
    private val api = MtgApi()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun mount(root: HTMLElement, sharedList: String?, token: String) {
        unmount()
        composition = renderComposable(root = root) {
            var state by remember {
                mutableStateOf(
                    AppState(admin = Admin(token.ifBlank { null }))
                        .let { if (sharedList.isNullOrBlank()) it else it.withShare(sharedList) }
                        .let { if (sharedList.isNullOrBlank()) it.navigate(routeFromHash()) else it },
                )
            }

            fun set(next: AppState) {
                state = next
            }

            /** Off the main thread and back, with the failure surfaced. */
            fun work(block: suspend () -> AppState) {
                scope.launch {
                    state = try {
                        block()
                    } catch (e: ApiFailure) {
                        state.say(e.message ?: "something went wrong")
                    } catch (e: Exception) {
                        state.say(e.message ?: e.toString())
                    }
                }
            }

            AppShell(
                state = state,
                onState = { next ->
                    set(next)
                    // The route decides what to fetch, so a tab change
                    // reloads without every caller remembering to.
                    if (next.view != state.view || next.route.rest != state.route.rest) {
                        loadFor(next, ::work)
                    }
                },
                onUnlock = { password ->
                    work {
                        val t = api.unlock(password)
                        window.localStorage.setItem("mtg.admin", """{"token":"$t","expires_at":null}""")
                        state.copy(admin = state.admin.unlock(t)).say("Admin mode on")
                    }
                },
                onSearch = { work { search(state) } },
                onOpenDeck = { slug -> work { openDeck(state, slug) } },
                onRunSql = { work { runSql(state) } },
                onPreviewEntry = {
                    work {
                        val s = state.entry
                        state.copy(
                            entry = s.previewed(
                                api.cards(token(state), s.direction!!, s.owner!!, s.list, dryRun = true),
                            ),
                        )
                    }
                },
                onApplyEntry = {
                    work {
                        val s = state.entry
                        state.copy(
                            entry = s.finished(
                                api.cards(token(state), s.direction!!, s.owner!!, s.list, dryRun = false),
                            ),
                        ).shareUsed()
                    }
                },
            )
        }
    }

    fun unmount() {
        composition?.dispose()
        composition = null
    }

    // ------------------------------------------------------------ loads

    private fun token(s: AppState) = s.admin.token.orEmpty()

    private fun loadFor(s: AppState, work: (suspend () -> AppState) -> Unit) {
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
        return s.copy(
            library = s.library.loaded(Rows.cards(rows.cols, rows.rows), Rows.count(total.rows)),
        )
    }

    private suspend fun loadDecks(s: AppState): AppState {
        val r = api.query(DeckQueries.all())
        return s.copy(decks = s.decks.loaded(DeckQueries.decode(r.cols, r.rows)))
    }

    private suspend fun openDeck(s: AppState, slug: String): AppState {
        val all = if (s.decks.decks.isEmpty()) loadDecks(s) else s
        val r = api.query(DeckQueries.cards(slug))
        return all.copy(decks = all.decks.opened(slug, DeckQueries.decodeCards(r.cols, r.rows)))
            .navigate(Route(View.DECKS, slug))
    }

    private suspend fun loadStats(s: AppState): AppState {
        val scope = Load.scopeFrom(s.route.rest)
        val r = api.query(StatsQueries.totals(scope))
        return s.copy(stats = s.stats.scopedTo(scope.owner).loaded(StatsQueries.decode(r.cols, r.rows)))
    }

    private suspend fun runSql(s: AppState): AppState {
        val r = api.queryRaw(s.console.sql, emptyList())
        return s.copy(console = s.console.ran(Table.of(r.cols, r.rows), r.n))
    }

    private fun routeFromHash() = Route.parse(window.location.hash)
}

fun main() {
    window.asDynamic().mtgApp = MtgApp
    // Kept for the page that mounts only the wizard.
    window.asDynamic().mtgEntry = MtgApp
}
