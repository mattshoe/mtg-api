package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deck opened by its key, from somebody else's collection.
 *
 * Matt: "i shared a link to my Liliana deck with Kayla, and the deck
 * doesn't load for her". The shelf is the collection on screen, so a
 * shared deck is not on it, and `open` looked for the deck's row only
 * there. The shells drive this end to end; these pin the rule.
 */
class SharedDeckTest {

    private val kaylas = Deck("k4yd3ck0", "Valgavoth", "k4yy0003", "Valgavoth", "BR", 3, null, "Kayla M")
    private val matts = Deck("x5aqbxtw", "Faultless Victory", "e7de0cb1", "Liliana the Faultless", "B", 3, null, "Matt")
    private val ring = DeckCard(name = "Sol Ring", qty = 1, role = null, owned = 1, nameNorm = "sol ring", typeLine = "Artifact")

    @Test
    fun aDeckOffTheShelfOpensFromItsOwnRow() {
        val s = DecksState(decks = listOf(kaylas)).opened("x5aqbxtw", listOf(ring), matts)
        assertEquals(matts, s.open, "a deck that is not on the shelf did not open")
        assertEquals(listOf(kaylas), s.decks, "the shared deck was put on the shelf of the collection on screen")
    }

    @Test
    fun theShelfIsAskedForTheRowOnlyWhenItLacksIt() {
        assertTrue(DecksState(decks = listOf(kaylas)).needsRow("x5aqbxtw"))
        assertEquals(false, DecksState(decks = listOf(kaylas)).needsRow("k4yd3ck0"))
    }

    @Test
    fun closingForgetsTheSharedDeck() {
        val s = DecksState(decks = listOf(kaylas)).opened("x5aqbxtw", listOf(ring), matts).close()
        assertNull(s.open)
        assertNull(s.opened("x5aqbxtw", emptyList()).open, "a closed shared deck came back without being asked for")
    }

    @Test
    fun aDeckThatIsNotThereIsAnErrorNotASpinner() {
        val s = DecksState(decks = listOf(kaylas), busy = true).missing("n0d3ck00")
        assertNull(s.open)
        assertEquals(false, s.busy)
        assertEquals("there is no deck at that address", s.error)
    }

    @Test
    fun oneDeckIsAskedForByItsKeyAndNobodysCollection() {
        val q = DeckQueries.one("x5aqbxtw")
        assertEquals(listOf<Any?>("x5aqbxtw"), q.params, "the deck was not asked for by its key")
        assertTrue("d.key = ?" in q.sql, "the one-deck query does not scope to the key")
        assertTrue("owner_id = (SELECT" !in q.sql, "the one-deck query is scoped to a collection, so a shared deck is not found")
    }
}
