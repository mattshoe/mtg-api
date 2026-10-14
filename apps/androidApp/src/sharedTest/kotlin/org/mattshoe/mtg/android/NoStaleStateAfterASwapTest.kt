package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertTrue

/**
 * A handler that outlives the frame it was made in must not write
 * that frame back.
 *
 * Matt: "the more cards i swap, the glitchier the UI becomes [...]
 * sometimes none of the popup fields are interactable at all and i
 * have to go back and forth to reload the page".
 *
 * Every write here is a whole `AppState`. A lambda handed to
 * `pointerInput(Unit)` or `LaunchedEffect(pager)` is kept from the
 * first frame, so it holds the state as it was then — and the
 * carousel's swipe and the tweak sheet's scrim both wrote that old
 * state back over everything that had happened since. After a swap,
 * that is the deck as it was before the swap: the list on screen
 * went back, and the next swap was planned against the old list and
 * wrote it to the server.
 *
 * Through `AppShell`, over a deck, with the state moved underneath
 * the way a landed swap moves it.
 */
@RunWith(AndroidJUnit4::class)
class NoStaleStateAfterASwapTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: MutableState<AppState>

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Sorcery",
        scryfallId = "abcdef12-3456",
    )

    private val before = listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring"))

    /** What the server sends back once Cultivate has been swapped out. */
    private val after = listOf(card("Counterspell"), card("Rampant Growth"), card("Sol Ring"))

    private fun deck() = Deck("alela", "Alela", "matt", null, "UW", 3, null)

    private fun onADeck(): AppState {
        val base = AppState(admin = Admin().signIn(Account("matt", role = "admin"), "t"))
        return base.navigate(Route(View.DECKS, "alela")).let {
            it.copy(decks = it.decks.loaded(listOf(deck())).opened("alela", before))
        }
    }

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = remember { mutableStateOf(start) }
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

    /** The swap landing: `applyTweak` reopens the deck with what is stored now. */
    private fun theSwapLands() {
        rule.runOnIdle {
            held.value = held.value.copy(decks = held.value.decks.opened("alela", after))
        }
        rule.waitForIdle()
    }

    private fun names() = held.value.decks.cards.map { it.name }

    @Test
    fun swipingTheCarouselAfterASwapKeepsTheSwap() {
        shell(onADeck())
        rule.onNodeWithText("Cultivate").let { runCatching { it.performScrollTo() }; it.performClick() }
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the row did not open a carousel")

        theSwapLands()
        rule.onNodeWithTag("carousel-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertTrue(
            "Rampant Growth" in names(),
            "a swipe put the deck back the way it was when the carousel opened: ${names()}",
        )
    }

    @Test
    fun aTapOutsideTheSheetAfterTheDeckMovedKeepsTheDeck() {
        shell(
            onADeck()
                .copy(deckTweak = DeckTweak.on(deck(), "", card("Cultivate"), Tweak.SWAP))
                .opening(Overlay.DECK_TWEAK),
        )

        theSwapLands()
        rule.onNodeWithTag("tweak-scrim").performTouchInput { click(Offset(width / 2f, 4f)) }
        rule.waitForIdle()

        assertTrue(Overlay.DECK_TWEAK !in held.value.overlays, "a tap outside the sheet left it open")
        assertTrue(
            "Rampant Growth" in names(),
            "closing the sheet put the deck back the way it was when the sheet opened: ${names()}",
        )
    }
}
