package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck page's COLOUR panel under its three rings, on an open deck
 * in a real `AppShell`.
 *
 * Matt: "Get rid of this text under the charts" and "I want the
 * 'exact' chart to be on its own line and bigger than the others".
 * The website's sibling is `DeckStatsLayoutTest`.
 */
@RunWith(AndroidJUnit4::class)
class ColourPanelParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(
        name: String,
        typeLine: String,
        manaCost: String? = null,
        cmc: Double? = 2.0,
        produces: String? = null,
        qty: Int = 1,
    ) = DeckCard(
        name,
        qty = qty,
        role = null,
        owned = qty,
        nameNorm = name.lowercase(),
        typeLine = typeLine,
        manaCost = manaCost,
        cmc = cmc,
        producedMana = produces,
        scryfallId = "abcdef12-3456",
    )

    /** Two colours wanted and made, and a dual, so every ring has slices. */
    private fun cards() = listOf(
        card("Plains", "Basic Land — Plains", cmc = 0.0, produces = "W", qty = 5),
        card("Island", "Basic Land — Island", cmc = 0.0, produces = "U", qty = 3),
        card("Hallowed Fountain", "Land", cmc = 0.0, produces = "WU", qty = 2),
        card("Counterspell", "Instant", manaCost = "{U}{U}"),
        card("Swords to Plowshares", "Instant", manaCost = "{W}", cmc = 1.0),
    )

    private fun shell() {
        val start = AppState(
            route = Route(View.DECKS, "alela"),
            admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
            decks = DecksState().loaded(listOf(deck())).opened("alela", cards()),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The drawn ring itself, not its caption or key. */
    private fun ring(caption: String) = rule
        .onNode(hasContentDescription("$caption · ", substring = true), useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    private fun says(fragment: String) = rule
        .onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theColourPanelHasNoCaptionUnderTheRings() {
        shell()
        assertTrue(says("Makes · "), "the colour panel never drew, so this proves nothing")
        assertTrue(!says("Pips the deck asks for"), "the caption about pips is still under the rings")
        assertTrue(!says("Hybrid pips count for both halves"), "the hybrid sentence is still under the rings")
    }

    @Test
    fun aSplashWithNoSourceIsStillSaid() {
        // The caption is gone; the warning it used to end with is not.
        val start = listOf(
            card("Plains", "Basic Land — Plains", cmc = 0.0, produces = "W"),
            card("Counterspell", "Instant", manaCost = "{U}{U}"),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = AppState(
                            route = Route(View.DECKS, "alela"),
                            admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
                            decks = DecksState().loaded(listOf(deck())).opened("alela", start),
                        ),
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
        assertTrue(says("No source for Blue."), "a splash with no sources went unsaid")
    }

    @Test
    fun needsAndMakesShareARowAndExactlyHasALineOfItsOwnBelowThem() {
        shell()
        val needs = ring("Needs")
        val makes = ring("Makes")
        val exactly = ring("Exactly")
        assertEquals(needs.top, makes.top, "needs and makes are not in one row")
        assertTrue(
            exactly.top >= needs.bottom,
            "the Exactly ring starts at ${exactly.top}, beside needs and makes rather than below them (they end at ${needs.bottom})",
        )
    }

    @Test
    fun theExactlyRingIsBiggerThanTheOtherTwo() {
        shell()
        val needs = ring("Needs")
        val exactly = ring("Exactly")
        val wide = exactly.right - exactly.left
        val narrow = needs.right - needs.left
        assertTrue(
            wide > narrow * 1.25f,
            "the Exactly ring is $wide across and needs is $narrow, so it is not bigger",
        )
    }
}
