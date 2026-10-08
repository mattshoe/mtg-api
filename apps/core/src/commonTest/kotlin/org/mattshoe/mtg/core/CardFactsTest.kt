package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything the database knows about a card, on the card's page.
 *
 * Matt: "the card details page still has a ton of missing fields from
 * the database". `cards` has carried the rank, the rarity, the artist,
 * the frame, the flags and the rest since the first import, and the
 * page selected none of them. #35 said it had; its diff did not.
 */
class CardFactsTest {

    private val cols = listOf(
        "edhrec_rank", "cmc", "colors", "color_identity", "produced_mana", "rarity",
        "setcode", "set_name", "set_type", "released_at", "collector_number", "artist",
        "layout", "frame", "border_color", "watermark", "security_stamp",
        "reserved", "game_changer", "full_art", "textless", "promo", "reprint",
        "variation", "oversized", "story_spotlight", "booster",
        "keywords", "finishes", "games", "promo_types", "frame_effects", "tags",
    )

    private fun row(vararg v: Any?) = JsonArray(
        v.map {
            when (it) {
                null -> JsonNull
                is Number -> JsonPrimitive(it)
                else -> JsonPrimitive(it.toString())
            }
        },
    )

    /** Arcane Signet, as `test/fixtures/seed.sql` has it. */
    private fun signet() = CardQueries.decodeFacts(cols, signetRows())

    private fun said(label: String) = signet().lines.firstOrNull { it.label == label }?.value

    @Test
    fun theRankIsOnThePage() = assertEquals("#3", said("EDHREC rank"))

    @Test
    fun theManaValueIsAWholeNumberWhenItIsOne() = assertEquals("2", said("Mana value"))

    @Test
    fun coloursAreNamedInWubrgOrderAndNoColourIsColourless() {
        assertEquals("Colorless", said("Colors"))
        assertEquals("Colorless", said("Color identity"))
        assertEquals("White, Blue, Black, Red, Green", said("Produces"))
    }

    @Test
    fun thePrintingIsNamed() {
        assertEquals("Common", said("Rarity"))
        assertEquals("Bloomburrow Commander (BLC)", said("Set"))
        assertEquals("Commander", said("Set type"))
        assertEquals("2024-08-02", said("Released"))
        assertEquals("127", said("Collector number"))
        assertEquals("Ioannis Fiore", said("Artist"))
        assertEquals("Normal", said("Layout"))
        assertEquals("2015", said("Frame"))
        assertEquals("Black", said("Border"))
    }

    @Test
    fun theFlagsThatAreSetAreSaidAndTheRestAreNot() =
        assertEquals("Game changer, Reprint", said("Flags"))

    @Test
    fun theListsHungOffTheCardAreSaid() {
        assertEquals("nonfoil, foil", said("Finishes"))
        assertEquals("paper", said("Games"))
        assertEquals("Mana rock, Ramp", said("Tags"))
    }

    @Test
    fun aFieldWithNothingInItIsLeftOffRatherThanDrawnBlank() {
        val labels = signet().lines.map { it.label }
        listOf("Watermark", "Security stamp", "Keywords", "Promo types", "Frame effects").forEach {
            assertTrue(it !in labels, "\"$it\" is drawn with nothing in it")
        }
    }

    @Test
    fun noRowMeansNoFacts() =
        assertEquals(emptyList(), CardQueries.decodeFacts(cols, emptyList()).lines)

    @Test
    fun theQueryAsksForEveryOneOfThem() {
        val q = CardQueries.facts("arcane signet")
        cols.forEach { assertTrue("AS $it" in q.sql || "pick.$it" in q.sql, "the query never selects $it") }
        assertEquals(listOf("arcane signet"), q.params)
    }

    @Test
    fun theCardPageLoadsThem() =
        assertTrue(Load.card("sol ring").any { it == CardQueries.facts("sol ring") }, "Load.card never asks")

    @Test
    fun theAnswersBecomeThePageWithTheFactsOnIt() {
        // Both shells hand `Load.card`'s answers back in its order and
        // get the page; neither picks them apart itself any more.
        val queries = Load.card("arcane signet")
        val empty = emptyList<String>() to emptyList<JsonArray>()
        val answers = queries.map { q ->
            if (q == CardQueries.facts("arcane signet")) cols to signetRows() else empty
        }
        val card = Load.cardDetail("arcane signet", "Arcane Signet", answers)
        assertEquals("#3", card.facts.lines.firstOrNull { it.label == "EDHREC rank" }?.value, "the facts never reached the page")
        assertEquals("Arcane Signet", card.name)
    }

    private fun signetRows() = listOf(
        row(
            3, 2.0, null, null, "BGRUW", "common",
            "blc", "Bloomburrow Commander", "commander", "2024-08-02", "127", "Ioannis Fiore",
            "normal", "2015", "black", null, null,
            0, 1, 0, 0, 0, 1,
            0, 0, 0, 0,
            null, "nonfoil, foil", "paper", null, null, "Mana rock, Ramp",
        ),
    )
}
