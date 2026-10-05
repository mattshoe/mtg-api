package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View

/**
 * Matt's bug, reported on the web and never fixed on Android: "If i
 * tap a card to navigate to the details, when i go back the whole
 * page refreshes and i lose my place in the scroll."
 *
 * `AppShell` composes exactly one of `LibraryScreen` and `DecksScreen`
 * at a time behind a `when (state.view)`, so opening a card stops
 * composing whichever screen you were reading and coming back builds
 * a fresh one — a new `LazyGridState` or `ScrollState` at offset zero.
 * A test that mounts `LibraryScreen` or `DecksScreen` on its own can
 * never see this: it never stops composing the screen in the first
 * place. These go through the real `AppShell`, the way a tap on a
 * device does — scroll down, open a card, come back, and check the
 * place in the list is still the place you left it.
 */
@RunWith(AndroidJUnit4::class)
class ScrollPositionSurvivesACardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun cardRow(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 1, printings = 1, free = 1, price = 1.5, value = 1.5,
    )

    private fun deckCard(name: String) = DeckCard(
        name, qty = 1, role = null, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    /**
     * Mount a real shell over mutable state, the way the activity
     * does — including the two card-opening paths (`onOpenCard` from
     * the Library grid, `onOpenNamed` from a deck's card list), wired
     * the way `MainActivity` wires them, so the round trip through
     * `View.CARD` is the real one.
     */
    private fun shell(start: AppState): () -> AppState {
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    val live = held.value
                    state = live
                    AppShell(
                        state = live,
                        onState = { held.value = it; state = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onOpenCard = { row ->
                            held.value = held.value.openCard(CardRef(row.nameNorm), row.fullName)
                            state = held.value
                        },
                        onOpenNamed = { name, norm, _ ->
                            held.value = held.value.openCard(CardRef(norm), name)
                            state = held.value
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    @Test
    fun theLibraryGridKeepsItsScrollPositionAfterOpeningAndClosingACard() {
        val rows = (0 until 60).map { cardRow("Card $it") }
        val start = AppState(
            route = Route(View.LIBRARY),
            library = Library().loaded(rows, rows.size),
        )
        shell(start)

        rule.onNodeWithTag("library").performScrollToKey("matt:card 39")
        rule.onNodeWithText("Card 39").assertIsDisplayed()
        // Far enough from the top that a reset back to zero is unmissable.
        rule.onNodeWithText("Card 0").assertDoesNotExist()

        rule.onNodeWithText("Card 39").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("← Back").assertIsDisplayed()

        rule.onNodeWithText("← Back").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Card 39").assertIsDisplayed()
    }

    @Test
    fun theOpenDeckKeepsItsScrollPositionAfterOpeningAndClosingACard() {
        val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)
        val cards = (0 until 60).map { deckCard("Card $it") }
        val start = AppState(
            route = Route(View.DECKS, "alela"),
            decks = DecksState().loaded(listOf(deck)).opened("alela", cards),
        )
        shell(start)

        rule.onNodeWithText("Card 39").performScrollTo()
        rule.onNodeWithText("Card 39").assertIsDisplayed()
        // The column is not lazy, so "Card 0" is always in the tree —
        // scrolled off, not absent.
        rule.onNodeWithText("Card 0").assertIsNotDisplayed()

        // Through the carousel, which is what a row opens now. The
        // trip is longer than it was and the claim is the same: the
        // deck comes back where you left it.
        rule.onNodeWithText("Card 39").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("card-carousel").assertExists()
        rule.onNodeWithText("Full details").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("← Back").assertIsDisplayed()

        rule.onNodeWithText("← Back").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Card 39").assertIsDisplayed()
    }

    @Test
    fun theOpenDeckKeepsItsScrollPositionBehindTheCarouselItself() {
        // The overlay is the point: the deck is still there under the
        // scrim, still where it was, so closing the carousel is not a
        // navigation and has nothing to restore.
        val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)
        val cards = (0 until 60).map { deckCard("Card $it") }
        shell(
            AppState(
                route = Route(View.DECKS, "alela"),
                decks = DecksState().loaded(listOf(deck)).opened("alela", cards),
            ),
        )

        rule.onNodeWithText("Card 39").performScrollTo()
        rule.onNodeWithText("Card 39").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("card-carousel").assertExists()
        rule.onNodeWithTag("deck-detail").assertExists()

        rule.onNodeWithTag("card-carousel").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Card 39").onFirst().assertIsDisplayed()
    }
}
