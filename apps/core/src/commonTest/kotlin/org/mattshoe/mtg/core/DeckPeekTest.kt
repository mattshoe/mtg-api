package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tapping a card in a deck.
 *
 * Matt: "When tapping my cards in a deck, i want them to show in a
 * 'carousel' [...] where i can swipe through them. It should be an
 * overlay so u can see the screen behind it [...] Btw this should be
 * what happens when you tap a card in the deck list. This is not a
 * special feature that launch."
 *
 * So the row no longer opens the card's page. It opens a position in
 * the deck — which card of the ninety-nine you are looking at — and
 * the page is one button away from there.
 *
 * Which card that is has to be a number into `pageOrder` rather than
 * a copy of the card, because the sheet under the carousel changes
 * the deck: setting a count, swapping a card, removing one. A held
 * copy would go stale on the first of those and the carousel would
 * be showing a card the deck no longer has.
 */
class DeckPeekTest {

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
    )

    private val deck = Deck("alela", "Alela", "matt", null, "UW", 3, null)

    private fun onADeck(vararg names: String) = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks.loaded(listOf(deck))
                    .opened("alela", names.map(::card)),
            )
        }

    /**
     * Three cards, named so that the order they are written in is the
     * order the page puts them in.
     *
     * The carousel indexes `pageOrder`, which groups by type and
     * sorts by name inside a group — not the order the rows came
     * back from the database. The first version of this test handed
     * them over in a different order and was reading a different
     * card from the one it named.
     */
    private fun threeCards() = onADeck("Counterspell", "Cultivate", "Sol Ring")

    @Test
    fun nothingIsPeekedToBeginWith() {
        val s = threeCards()
        assertFalse(s.peek.open)
        assertNull(s.peeked)
        assertFalse(Overlay.CARD_PEEK in s.overlays)
    }

    @Test
    fun theFixtureIsInThePageOrderTheCarouselIndexes() {
        assertEquals(
            listOf("Counterspell", "Cultivate", "Sol Ring"),
            threeCards().decks.pageOrder.map { it.name },
        )
    }

    @Test
    fun tappingACardOpensTheCarouselOnThatCard() {
        val s = threeCards().peekAt(1)
        assertTrue(Overlay.CARD_PEEK in s.overlays)
        assertEquals("Cultivate", assertNotNull(s.peeked).name)
    }

    @Test
    fun tappingIsByTheCardOnTheRowAndNotByCountingRowsTwice() {
        // The deck page draws its rows grouped by type, so the row's
        // position inside its group is not its position in the run.
        // The shell hands over the card and the core finds it.
        val s = threeCards()
        val sol = s.decks.pageOrder.last()
        assertEquals("Sol Ring", assertNotNull(s.peekCard(sol).peeked).name)
    }

    @Test
    fun tappingACardTheDeckDoesNotHaveDoesNothing() {
        val s = threeCards().peekCard(card("Black Lotus"))
        assertFalse(Overlay.CARD_PEEK in s.overlays)
    }

    @Test
    fun itStaysOnTheDeckRatherThanNavigatingToTheCard() {
        // The whole point of the change: the row used to be a link to
        // `#/card/...`. It is an overlay now, with the deck behind it.
        val s = threeCards().peekAt(0)
        assertEquals(View.DECKS, s.view)
        assertEquals("alela", s.route.rest)
    }

    @Test
    fun swipingMovesAlongThePageOrder() {
        val s = threeCards().peekAt(0).peekTo(2)
        assertEquals("Sol Ring", assertNotNull(s.peeked).name)
        assertEquals("3 of 3", s.peekPlace)
    }

    @Test
    fun aSwipeCannotFallOffEitherEnd() {
        val s = threeCards().peekAt(0)
        assertEquals("Counterspell", assertNotNull(s.peekTo(-1).peeked).name)
        assertEquals("Sol Ring", assertNotNull(s.peekTo(9).peeked).name)
    }

    @Test
    fun theCarouselSaysWhereYouAreInTheDeck() {
        assertEquals("2 of 3", threeCards().peekAt(1).peekPlace)
        assertNull(threeCards().peekPlace, "it counts a card nobody is looking at")
    }

    @Test
    fun tappingACardOfADeckThatIsNotOpenDoesNothing() {
        val s = AppState().peekAt(0)
        assertFalse(Overlay.CARD_PEEK in s.overlays, "it opened a carousel over nothing")
    }

    @Test
    fun removingTheCardYouAreLookingAtShowsWhatTookItsPlace() {
        // The sheet under the carousel can remove the card. The
        // position survives, the card under it does not, and a
        // carousel that went blank at that moment would be the
        // worst possible answer.
        val s = threeCards().peekAt(2)
        val shorter = s.copy(decks = s.decks.opened("alela", listOf(card("Sol Ring"))))
        assertEquals("Sol Ring", assertNotNull(shorter.peeked).name)
    }

    @Test
    fun emptyingTheDeckLeavesNothingToLookAt() {
        val s = threeCards().peekAt(1)
        assertNull(s.copy(decks = s.decks.opened("alela", emptyList())).peeked)
    }

    @Test
    fun fullDetailsOpensTheCardsOwnPageAndClosesTheCarousel() {
        // Two steps and in this order, which is what both shells do.
        //
        // It was one — an `openPeeked()` on `AppState` — and then the
        // same rule moved into `openCard` so no shell could forget
        // it. Both were wrong for the same reason: the website keeps
        // a history entry per open overlay, so closing the carousel
        // in the same update that changes the route made the router
        // pop the navigation back off again. The card page appeared
        // and vanished, and the address bar still said the deck.
        //
        // Closing first is its own update, the history entry comes
        // off, and the card page is pushed on top of nothing.
        val peeking = threeCards().peekAt(1)
        val card = peeking.peeked!!
        val s = peeking.closing(Overlay.CARD_PEEK)
            .openCard(CardRef(card.nameNorm), card.name)
        assertEquals(View.CARD, s.view)
        assertEquals("Cultivate", s.card?.name)
        assertFalse(Overlay.CARD_PEEK in s.overlays, "the carousel is still over the card page")
        // And the card knows it came from the deck, so Back returns
        // there rather than to the Library.
        assertEquals(Route(View.DECKS, "alela"), s.from)
    }

    @Test
    fun closingTheCarouselForgetsWhereItWas() {
        val s = threeCards().peekAt(1).closing(Overlay.CARD_PEEK)
        assertFalse(s.peek.open)
        assertNull(s.peeked)
    }

    @Test
    fun backTakesTheCarouselOffBeforeTheDeck() {
        val s = threeCards().peekAt(1)
        val back = assertNotNull(s.back())
        assertFalse(Overlay.CARD_PEEK in back.overlays)
        assertEquals("alela", back.decks.openSlug, "one press closed the deck as well")
    }
}

/**
 * What the sheet under the carousel is allowed to say.
 *
 * Matt: "The 'bottom sheet' thingy underneath it should only have
 * some very very basic information like name, value, set, etc."
 *
 * Name, price and type the deck's own query already carried. The set
 * it did not, so a sheet asking for one had nothing to print —
 * `deck_cards` holds a name and a quantity, and everything else on a
 * row is joined from `cards`.
 */
class DeckCardPrintingTest {

    private val cols = listOf(
        "name", "name_norm", "qty", "role", "owned", "type_line", "scryfall_id",
        "mana_cost", "cmc", "produced_mana", "oracle_text", "color_identity",
        "rarity", "price", "setcode", "set_name", "collector_number",
    )

    private fun row() = kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive("Sol Ring"))
        add(kotlinx.serialization.json.JsonPrimitive("sol ring"))
        add(kotlinx.serialization.json.JsonPrimitive(1))
        add(kotlinx.serialization.json.JsonNull)
        add(kotlinx.serialization.json.JsonPrimitive(3))
        add(kotlinx.serialization.json.JsonPrimitive("Artifact"))
        add(kotlinx.serialization.json.JsonPrimitive("abcdef12-3456"))
        add(kotlinx.serialization.json.JsonPrimitive("{1}"))
        add(kotlinx.serialization.json.JsonPrimitive(1.0))
        add(kotlinx.serialization.json.JsonNull)
        add(kotlinx.serialization.json.JsonPrimitive("Add {C}{C}."))
        add(kotlinx.serialization.json.JsonNull)
        add(kotlinx.serialization.json.JsonPrimitive("uncommon"))
        add(kotlinx.serialization.json.JsonPrimitive(1.75))
        add(kotlinx.serialization.json.JsonPrimitive("m3c"))
        add(kotlinx.serialization.json.JsonPrimitive("Modern Horizons 3 Commander"))
        add(kotlinx.serialization.json.JsonPrimitive("409"))
    }

    @Test
    fun aDeckCardKnowsWhichPrintingItIs() {
        val card = DeckQueries.decodeCards(cols, listOf(row())).single()
        assertEquals("m3c", card.setCode)
        assertEquals("Modern Horizons 3 Commander", card.setName)
        assertEquals("409", card.collectorNumber)
    }

    @Test
    fun theQueryActuallyAsksForThem() {
        // The decoder reading a column the SELECT never names is the
        // shape of bug this catches: green decoder, blank sheet.
        val sql = DeckQueries.cards("alela").sql
        listOf("setcode", "set_name", "collector_number").forEach {
            assertTrue(it in sql, "the deck query does not select $it")
        }
    }

    @Test
    fun aCardNobodyOwnsHasNoPrintingAndSaysSo() {
        val bare = DeckCard(name = "Sol Ring", qty = 1, role = null, owned = 0)
        assertEquals(null, bare.setCode)
        assertEquals(null, bare.printing)
    }

    @Test
    fun thePrintingReadsTheWayACollectorWritesIt() {
        val card = DeckQueries.decodeCards(cols, listOf(row())).single()
        assertEquals("M3C · 409", card.printing)
    }
}
