package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Turning a two-faced card over in the carousel.
 *
 * Matt: "the carousel should have an icon overlaid on double sided
 * cards that allows you to flip the card so you can see the opposite
 * side back and forth".
 *
 * Which cards have a back is the `layout` column's to say, not the
 * name's: "Brazen Borrower // Petty Theft" is an adventure, one face
 * printed on one side, and Scryfall has no back to show for it. The
 * layouts with a second printed side are the ones the search box's
 * `is:dfc` already names.
 *
 * Which side is up is `Peek.flipped`, in the shared state, so both
 * shells draw the same side and a swipe lands on the next card's
 * front on both.
 */
class PeekFlipTest {

    private fun card(name: String, layout: String?) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Creature",
        scryfallId = "ab" + name.length + "-id",
        layout = layout,
    )

    private val delver = card("Delver of Secrets", "transform")
    private val sol = card("Sol Ring", "normal")
    private val borrower = card("Brazen Borrower // Petty Theft", "adventure")

    private fun onADeck(vararg cards: DeckCard) = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks.loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", cards.toList()),
            )
        }

    private fun at(s: AppState, name: String) =
        s.peekAt(s.decks.pageOrder.indexOfFirst { it.name == name })

    @Test
    fun aTwoFacedCardCanBeTurnedOverAndAOneFacedCardCannot() {
        val s = onADeck(delver, sol, borrower)
        assertTrue(assertNotNull(at(s, "Delver of Secrets").peeked).twoSided, "Delver has a back and no way to see it")
        assertFalse(assertNotNull(at(s, "Sol Ring").peeked).twoSided, "Sol Ring offers a back it does not have")
        assertFalse(
            assertNotNull(at(s, "Brazen Borrower // Petty Theft").peeked).twoSided,
            "an adventure is one side of cardboard, and offered a back",
        )
    }

    @Test
    fun everyLayoutWithAPrintedBackCounts() {
        listOf("transform", "modal_dfc", "reversible_card", "double_faced_token").forEach {
            assertTrue(CardQueries.twoSided(it), "$it has a back and is not offered one")
        }
        listOf(null, "normal", "split", "adventure", "flip", "saga", "meld").forEach {
            assertFalse(CardQueries.twoSided(it), "$it has no back to show and is offered one")
        }
    }

    @Test
    fun turningItOverShowsTheBackAndTurningItAgainShowsTheFront() {
        val s = at(onADeck(delver, sol), "Delver of Secrets")
        val id = assertNotNull(s.peeked).scryfallId!!
        assertEquals("https://cards.scryfall.io/normal/front/a/b/$id.jpg", s.peekArt, "it does not open on the front")
        val over = s.peekFlip()
        assertTrue(over.peek.flipped, "flipping left it the right way up")
        assertEquals("https://cards.scryfall.io/normal/back/a/b/$id.jpg", over.peekArt, "flipped, it still shows the front")
        assertEquals(s.peekArt, over.peekFlip().peekArt, "flipping twice did not come back to the front")
    }

    @Test
    fun aOneFacedCardIgnoresAFlip() {
        val s = at(onADeck(delver, sol), "Sol Ring")
        assertEquals(s, s.peekFlip(), "flipping Sol Ring changed something")
    }

    @Test
    fun theNextCardOpensFaceUp() {
        val s = onADeck(delver, card("Huntmaster of the Fells", "transform"))
        val flipped = at(s, "Delver of Secrets").peekFlip()
        val next = flipped.peekTo(flipped.peek.at + 1)
        assertFalse(next.peek.flipped, "swiping on landed on the next card's back")
        assertTrue(assertNotNull(next.peekArt).contains("/front/"), "the next card is drawn from its back: ${next.peekArt}")
    }

    @Test
    fun aDecksCardsCarryTheirLayout() {
        assertTrue("layout" in DeckQueries.cards("q8ytka9m").sql, "the deck query never asks which layout a card is")
        val c = DeckQueries.decodeCards(
            listOf("name", "name_norm", "qty", "owned", "scryfall_id", "layout"),
            listOf(JsonArray(listOf("Delver of Secrets", "delver of secrets", "1", "1", "ab-1", "transform").map(::JsonPrimitive))),
        ).single()
        assertEquals("transform", c.layout)
    }

    @Test
    fun theLibrarysCardsCanBeTurnedOver() {
        val row = CardRow(
            id = 1, owner = "matt", name = "Delver of Secrets // Insectile Aberration", nameNorm = "delver of secrets",
            face2 = "Insectile Aberration", layout = "transform", scryfallId = "ab-1", manaCost = "{U}", cmc = 1.0,
            typeLine = "Creature", colorIdentity = "U", rarity = "common", setCode = "isd", setName = "Innistrad",
            collectorNumber = "51", edhrecRank = null, releasedAt = null, finish = "nonfoil", power = "1",
            toughness = "1", artist = null, qty = 1, printings = 1, free = 1, price = 0.5, value = 0.5,
        )
        val s = AppState().navigate(View.LIBRARY)
            .let { it.copy(library = it.library.loaded(listOf(row), 1)) }
            .peekRow(row)
        assertTrue(assertNotNull(s.peeked).twoSided, "a transforming card in the Library has no way to turn over")
    }

    @Test
    fun tergridOnTheGameChangersListCanBeTurnedOver() {
        // The one two-faced card on either built-in list. Erayo on the
        // banned list is a flip card, both halves on one side.
        val page = AppState().navigate(Route.parse("#/guide/" + Guide.GAME_CHANGERS))
        val tergrid = page.gameChangerOrder.first { it.name.startsWith("Tergrid") }
        assertTrue(assertNotNull(page.peekGameChanger(tergrid).peeked).twoSided, "Tergrid has a back and no way to see it")
        val erayo = Banned.lists.flatMap { it.cards }.first { it.name.startsWith("Erayo") }
        assertFalse(CardQueries.twoSided(erayo.layout), "Erayo is a flip card and is offered a back")
    }

    @Test
    fun aFetchListsCardsCanBeTurnedOver() {
        assertTrue("layout" in FetchList.query(listOf("delver of secrets"), "e7de0cb1")!!.sql, "the fetch query never asks for a layout")
        val owned = FetchList.decode(
            listOf("name_norm", "name", "face1", "types", "rarity", "qty", "scryfall_id", "layout"),
            listOf(
                JsonArray(
                    listOf("delver of secrets", "Delver of Secrets // Insectile Aberration", "Delver of Secrets", "Creature", "common", "1", "ab-1", "transform")
                        .map(::JsonPrimitive),
                ),
            ),
        )
        val list = "1 Delver of Secrets"
        val f = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned))
        val s = AppState(route = Route(View.FETCH, FetchListState.PAGE), fetch = f)
        val open = s.peekFetch(s.fetch.result!!.sections.flatMap { it.cards }.single())
        assertTrue(assertNotNull(open.peeked).twoSided, "a transforming card on a fetch list has no way to turn over")
        // A row with no layout at all is a one-sided card, not a crash.
        val bare = FetchList.decode(listOf("name_norm", "layout"), listOf(JsonArray(listOf(JsonPrimitive("x"), JsonNull))))
        assertEquals(null, bare.single().layout)
    }
}
