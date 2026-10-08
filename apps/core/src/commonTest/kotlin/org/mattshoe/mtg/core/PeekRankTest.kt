package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Matt: "I want the carousel to show edhrec rank on it".
 *
 * Both runs the carousel is over. The Library's rows already carried
 * the rank and nothing read it; a deck's rows never selected it.
 */
class PeekRankTest {

    private fun row(name: String, rank: Long?, layout: String? = "normal") = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = layout, scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = null, rarity = "uncommon", setCode = "m3c",
        setName = null, collectorNumber = "409", edhrecRank = rank, releasedAt = null,
        finish = "nonfoil", power = null, toughness = null, artist = null, qty = 1,
        printings = 1, free = 1, price = 1.0, value = 1.0,
    )

    private fun library(vararg rows: CardRow) = AppState().navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows.toList(), rows.size)) }

    @Test
    fun aRankedLibraryCardSaysItsRank() {
        val r = row("Sol Ring", 1)
        val s = library(r).peekRow(r)
        val tags = assertNotNull(s.peeked).tags.map { it.text }
        assertTrue("EDHREC #1" in tags, "carousel tags over the Library: $tags")
    }

    @Test
    fun anUnrankedCardSaysNothingAboutIt() {
        val r = row("Homebrew", null)
        val tags = assertNotNull(library(r).peekRow(r).peeked).tags.map { it.text }
        assertTrue(tags.none { "EDHREC" in it }, "an unranked card claimed a rank: $tags")
    }

    @Test
    fun theLibraryCarouselKnowsTheLayout() {
        val r = row("Hagra Mauling // Hagra Broodpit", 2093, "modal_dfc")
        assertEquals("modal_dfc", assertNotNull(library(r).peekRow(r).peeked).layout)
    }

    private val cols = listOf("name", "name_norm", "qty", "role", "owned", "scryfall_id", "edhrec_rank", "layout")

    private fun deckRow() = JsonArray(
        listOf(
            JsonPrimitive("Aetherblade Agent // Gitaxian Mindstinger"),
            JsonPrimitive("aetherblade agent // gitaxian mindstinger"),
            JsonPrimitive(1), JsonNull, JsonPrimitive(1),
            JsonPrimitive("dad34ae5-56b4-4394-be02-e043dc1cc23d"),
            JsonPrimitive(20135), JsonPrimitive("transform"),
        ),
    )

    @Test
    fun theDeckQueryAsksForTheRankAndTheLayout() {
        val sql = DeckQueries.cards("alela").sql
        assertTrue(Regex("""AS\s+edhrec_rank""").containsMatchIn(sql), "the deck query never selects edhrec_rank")
        assertTrue(Regex("""AS\s+layout""").containsMatchIn(sql), "the deck query never selects layout")
    }

    @Test
    fun aDeckCardReadsThem() {
        val c = DeckQueries.decodeCards(cols, listOf(deckRow())).single()
        assertEquals(20135L, c.edhrecRank, "rank dropped by the decoder")
        assertEquals("transform", c.layout, "layout dropped by the decoder")
    }

    @Test
    fun theDeckCarouselSaysTheRankToo() {
        val card = DeckQueries.decodeCards(cols, listOf(deckRow())).single()
        val s = AppState().navigate(Route(View.DECKS, "alela")).let {
            it.copy(decks = it.decks.loaded(listOf(Deck("alela", "Alela", "matt", null, "UB", 3, null))).opened("alela", listOf(card)))
        }.peekCard(card)
        val peeked = assertNotNull(s.peeked)
        assertTrue("EDHREC #20,135" in peeked.tags.map { it.text }, "deck carousel tags: ${peeked.tags}")
        assertEquals("transform", peeked.layout)
    }
}
