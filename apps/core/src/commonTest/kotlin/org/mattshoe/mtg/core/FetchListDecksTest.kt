package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "in the fetch list page, if there are no entries in the bulk for
 * a card, then show which deck it belongs to if it appears in a deck".
 *
 * Bulk is what no deck has spoken for — `card_usage.free`, owned minus
 * the copies a real deck holds. A card with none of that left is not in
 * a binder, it is in a deck, and the list says which.
 */
class FetchListDecksTest {

    private fun owned(name: String, qty: Int, inDecks: Int, decks: List<String>, rarity: String = "common") =
        OwnedPrinting(
            nameNorm = name.lowercase(), name = name, face = null, types = "Artifact",
            colors = null, rarity = rarity, qty = qty, identity = null,
            inDecks = inDecks, decks = decks,
        )

    private fun card(list: String, vararg rows: OwnedPrinting) =
        FetchList.build(DeckList.entries(list), rows.toList()).sections.single().cards.single()

    @Test
    fun aCardWithNoCopyInBulkSaysWhichDecksHoldIt() {
        val c = card("1 Sol Ring", owned("Sol Ring", qty = 2, inDecks = 2, decks = listOf("Chaos Incarnate", "Elven Council")))
        assertEquals(listOf("Chaos Incarnate", "Elven Council"), c.decks)
        assertEquals("In decks: Chaos Incarnate, Elven Council", c.decksLine)
    }

    @Test
    fun oneDeckIsSaidAsOne() {
        val c = card("1 Sol Ring", owned("Sol Ring", qty = 1, inDecks = 1, decks = listOf("Chaos Incarnate")))
        assertEquals("In deck: Chaos Incarnate", c.decksLine)
    }

    @Test
    fun aCardWithACopyStillInBulkNamesNoDeck() {
        val c = card("1 Sol Ring", owned("Sol Ring", qty = 3, inDecks = 2, decks = listOf("Chaos Incarnate", "Elven Council")))
        assertEquals(emptyList(), c.decks, "a spare copy is in the binder, so no deck should be named")
    }

    @Test
    fun copiesAtEveryRarityCountTowardsBulk() {
        // The query answers one row per rarity, each carrying the same
        // deck count, so bulk is every copy owned less that count once.
        val c = card(
            "1 Sol Ring",
            owned("Sol Ring", qty = 1, inDecks = 2, decks = listOf("A", "B")),
            owned("Sol Ring", qty = 1, inDecks = 2, decks = listOf("A", "B"), rarity = "uncommon"),
        )
        assertEquals(listOf("A", "B"), c.decks)
        val spare = card(
            "1 Sol Ring",
            owned("Sol Ring", qty = 2, inDecks = 2, decks = listOf("A", "B")),
            owned("Sol Ring", qty = 1, inDecks = 2, decks = listOf("A", "B"), rarity = "uncommon"),
        )
        assertEquals(emptyList(), spare.decks, "three owned and two in decks leaves one in bulk")
    }

    @Test
    fun theQueryAsksHowManyCopiesRealDecksHoldAndWhichDecks() {
        val q = FetchList.query(listOf("sol ring"), "m4tt0001")!!
        assertTrue("in_decks" in q.sql, q.sql)
        assertTrue("decks" in q.sql, q.sql)
        // The same rule as card_usage: a proxy or PROPOSED deck holds no real card.
        assertTrue("is_proxy = 0" in q.sql, "a proxy deck would be named as holding a real card")
        assertTrue("PROPOSED" in q.sql, "a proposed deck would be named as holding a real card")
        assertEquals(2, q.params.size)
    }

    @Test
    fun theDecksComeBackFromTheRows() {
        val cols = listOf("name_norm", "name", "face1", "types", "colors", "color_identity", "rarity", "qty", "in_decks", "decks")
        val rows = listOf(
            JsonArray(
                listOf(
                    JsonPrimitive("sol ring"), JsonPrimitive("Sol Ring"), JsonPrimitive("Sol Ring"),
                    JsonPrimitive("Artifact"), JsonPrimitive(""), JsonPrimitive(""), JsonPrimitive("uncommon"),
                    JsonPrimitive(2), JsonPrimitive(2), JsonPrimitive("[\"Chaos Incarnate\",\"Elven Council\"]"),
                ),
            ),
        )
        val p = FetchList.decode(cols, rows).single()
        assertEquals(2, p.inDecks)
        assertEquals(listOf("Chaos Incarnate", "Elven Council"), p.decks)
    }

    @Test
    fun aRowWithoutDeckColumnsNamesNoDeck() {
        val cols = listOf("name_norm", "name", "qty")
        val rows = listOf(JsonArray(listOf(JsonPrimitive("sol ring"), JsonPrimitive("Sol Ring"), JsonPrimitive(1))))
        val p = FetchList.decode(cols, rows).single()
        assertEquals(0, p.inDecks)
        assertEquals(emptyList(), p.decks)
        assertNull(FetchList.build(DeckList.entries("1 Sol Ring"), listOf(p)).sections.single().cards.single().decksLine)
    }
}
