package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Share
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A card can be shared, including one opened from a deck.
 *
 * Matt: "there is no share button on cards from the deck details
 * page."
 *
 * It was missing from every card, not just a card reached through a
 * deck: `CardSheet` had no `onShare` parameter at all, while the
 * website's `CardPage` has carried one — "Copy a link to this card" —
 * the whole time. A card is its own destination with its own address,
 * so it is the one screen where a link is most obviously the point.
 *
 * The route the card is reached by does not change the link, which is
 * the second thing asserted here: `AppState.openCard` navigates to
 * the card alone rather than the card plus whatever deck was
 * underneath, so a card shared out of Alela and the same card shared
 * out of the Library hand over the same URL.
 */
@RunWith(AndroidJUnit4::class)
class CardShareParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private var shared: String? = null

    private fun printing() = Printing(
        id = 1, setCode = "m3c", setName = "Modern Horizons 3", collectorNumber = "409",
        finish = "nonfoil", qty = 1, scryfallId = "abcdef12-3456", owner = "matt",
        cardName = "Sol Ring",
    )

    private fun detail() = CardDetail(
        name = "Sol Ring",
        nameNorm = "sol ring",
        printings = listOf(printing()),
    )

    private fun deck() = Deck(
        "alela", "Faerie Swarm", "matt",
        "Alela, Artful Provocateur (ELD) 324", "UW", 3, null,
    )

    private fun deckCard(name: String) = DeckCard(
        name, qty = 1, role = null, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Artifact", scryfallId = "abcdef12-3456",
    )

    /** A card open, reached from a deck, the way the app reaches one. */
    private fun fromTheDeck(): AppState = AppState(
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState().loaded(listOf(deck())).opened("alela", listOf(deckCard("Sol Ring"))),
    ).openCard(CardRef("sol ring")).copy(card = detail())

    private fun fromTheLibrary(): AppState =
        AppState(admin = Admin().signIn(Account(key = "matt", role = "admin"), "t")).openCard(CardRef("sol ring")).copy(card = detail())

    private fun shell(start: AppState) {
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
                        onShareCard = { shared = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aCardOpenedFromADeckOffersAShare() {
        shell(fromTheDeck())
        rule.onNodeWithContentDescription("Share this card").assertExists()
    }

    @Test
    fun soDoesACardOpenedFromTheLibrary() {
        // It was missing from both. Naming the deck in the report did
        // not mean the deck was the cause.
        shell(fromTheLibrary())
        rule.onNodeWithContentDescription("Share this card").assertExists()
    }

    @Test
    fun theShareHandsOverTheCardsOwnLink() {
        shell(fromTheDeck())
        rule.onNodeWithContentDescription("Share this card").performClick()
        rule.waitForIdle()
        assertEquals(
            Share.link(fromTheDeck()),
            shared,
            "the share did not hand over this card's address",
        )
        assertTrue(
            shared!!.startsWith("https://mtg.mattshoe.org/#/card/"),
            "the link is not a card link: $shared",
        )
    }

    @Test
    fun theLinkIsTheSameWhicheverWayTheCardWasReached() {
        // `openCard` navigates to the card alone, so the deck
        // underneath is not in the address. A link that carried it
        // would open somebody else on a deck they were not sent.
        assertEquals(Share.link(fromTheLibrary()), Share.link(fromTheDeck()))
    }

    @Test
    fun theShareIsTheSameMarkTheDeckUses() {
        shell(fromTheDeck())
        // One share symbol in the app, not two. `ShareMark` is the
        // website's own drawing; a second one typed as a glyph is how
        // the deck page ended up with an arrow nobody recognised.
        val mark = rule.onNodeWithTag(SHARE_MARK_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val w = mark.right.value - mark.left.value
        assertTrue(w >= 19f, "the card's share mark is only ${w}dp")
    }

    @Test
    fun theShareTargetIsThumbSized() {
        shell(fromTheDeck())
        val target = rule.onNodeWithContentDescription("Share this card")
            .getUnclippedBoundsInRoot()
        val w = target.right.value - target.left.value
        val h = target.bottom.value - target.top.value
        assertTrue(w >= 47.5f && h >= 47.5f, "the card's share target is ${w}x${h}dp, under 48")
    }
}
