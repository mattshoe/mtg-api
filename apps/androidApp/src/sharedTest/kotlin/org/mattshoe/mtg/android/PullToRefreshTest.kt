package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Role
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pulling any page down asks the app to refresh it.
 *
 * Matt: "Every page should be able to pull to refresh". Through the
 * real `AppShell`, page by page, because the gesture is the shell's
 * and a page that is not scrollable, or sits outside the box that
 * listens, would take the drag and say nothing.
 */
@RunWith(AndroidJUnit4::class)
class PullToRefreshTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private val me = Admin().signIn(Account("matt", role = Role.ADMIN), session = "t")

    private fun row(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 1, printings = 1, free = 1, price = 1.5, value = 1.5,
    )

    private val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UW", 3, null)

    private val decks = DecksState().loaded(listOf(deck))

    private val pages: Map<String, AppState> = mapOf(
        "Library" to AppState(
            admin = me,
            library = Library().loaded(listOf(row("Sol Ring"), row("Lightning Bolt")), 2),
        ),
        "Decks" to AppState(route = Route(View.DECKS), admin = me, decks = decks),
        "an open deck" to AppState(
            route = Route(View.DECKS, "alela"),
            admin = me,
            decks = decks.opened(
                "alela",
                listOf(
                    DeckCard(
                        "Sol Ring", qty = 1, role = null, owned = 1, nameNorm = "sol ring",
                        typeLine = "Artifact", scryfallId = null,
                    ),
                ),
            ),
        ),
        "Stats" to AppState(route = Route(View.STATS), admin = me),
        "Admin Settings" to AppState(route = Route(View.ADMIN), admin = me),
        "Server Logs" to AppState(route = Route(View.LOGS), admin = me),
        "a card" to AppState(
            route = Route(View.CARD, "sol+ring"),
            admin = me,
            card = CardDetail(name = "Sol Ring", nameNorm = "sol ring"),
        ),
        "Entry" to AppState(route = Route(View.ENTRY), admin = me),
    )

    private fun shell(start: AppState, onRefresh: () -> Unit = {}) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = start,
                        onState = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onRefresh = onRefresh,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * A thumb dragged from just below the middle of the screen to the
     * bottom edge.
     *
     * Below the middle on purpose. It started a third of the way down
     * once, which on Robolectric's 470dp screen is inside even a short
     * page — and on an 808dp phone is under the Server Log's two lines,
     * on blank space nothing scrolled, so the pull went nowhere. CI's
     * emulator found it; this start finds it on the JVM.
     */
    private fun pullDown() {
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.45f),
                end = Offset(centerX, height - 1f),
                durationMillis = 600,
            )
        }
        rule.waitForIdle()
    }

    private fun pullAsks(page: String) {
        var asked = 0
        shell(pages.getValue(page)) { asked++ }
        pullDown()
        assertEquals(1, asked, "pulling down on $page asked nothing to refresh")
    }

    @Test fun pullingDownOnTheLibraryRefreshesIt() = pullAsks("Library")
    @Test fun pullingDownOnTheDeckListRefreshesIt() = pullAsks("Decks")
    @Test fun pullingDownOnAnOpenDeckRefreshesIt() = pullAsks("an open deck")
    @Test fun pullingDownOnStatsRefreshesIt() = pullAsks("Stats")
    @Test fun pullingDownOnAdminSettingsRefreshesIt() = pullAsks("Admin Settings")
    @Test fun pullingDownOnTheServerLogRefreshesIt() = pullAsks("Server Logs")
    @Test fun pullingDownOnACardRefreshesIt() = pullAsks("a card")
    @Test fun pullingDownOnEntryRefreshesIt() = pullAsks("Entry")

    private val spinners = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Test
    fun aPulledPageSpinsWhileItsFetchIsOut() {
        // The same page, busy both times. Only one of them was pulled,
        // and only that one should have the pull's spinner on top.
        val stats = pages.getValue("Stats")
        val opened = stats.fetching()
        val pulled = stats.refreshed()
        assertTrue(pulled.refreshing, "the fixture is not refreshing, so this proves nothing")
        val held = androidx.compose.runtime.mutableStateOf(opened)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = held.value,
                        onState = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val plain = rule.onAllNodes(spinners).fetchSemanticsNodes().size
        held.value = pulled
        rule.waitForIdle()
        val withPull = rule.onAllNodes(spinners).fetchSemanticsNodes().size
        assertTrue(
            withPull > plain,
            "a pulled page that is still fetching shows $withPull spinners, the same as one merely loading ($plain)",
        )
    }
}
