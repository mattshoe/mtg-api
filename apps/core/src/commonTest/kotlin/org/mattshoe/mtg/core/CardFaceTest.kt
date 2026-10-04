package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card's own face (3.2).
 *
 * `CardDetail` had no fields for what is printed on a card, so both
 * platforms opened one and showed its sets, its decks and its
 * legality while never saying what it does. The columns have been in
 * `cards` since the first import and nothing selected them.
 */
class CardFaceTest {

    private val cols = listOf(
        "face_index", "name", "mana_cost", "type_line",
        "oracle_text", "flavor_text", "power", "toughness", "loyalty", "defense",
    )

    private fun row(vararg v: String?) = JsonArray(
        v.map { if (it == null) JsonNull else JsonPrimitive(it) },
    )

    @Test
    fun oneRowIsOneFace() {
        val faces = CardQueries.decodeFaces(
            cols,
            listOf(row("99", "Sol Ring", "{1}", "Artifact", "{T}: Add {C}{C}.", "", null, null, null, null)),
        )
        assertEquals(1, faces.size)
        assertEquals("{1}", faces[0].manaCost)
        assertEquals("Artifact", faces[0].typeLine)
        assertEquals("{T}: Add {C}{C}.", faces[0].oracleText)
        assertNull(faces[0].stats, "an artifact has no power and toughness")
    }

    @Test
    fun aDoubleFacedCardKeepsBothFacesInPrintedOrder() {
        val faces = CardQueries.decodeFaces(
            cols,
            listOf(
                row("0", "Adventurous Eater", "{2}{B}", "Creature — Human Warlock", "x", "", "3", "2", null, null),
                row("1", "Have a Bite", "{B}", "Sorcery", "y", "", null, null, null, null),
            ),
        )
        assertEquals(listOf("Adventurous Eater", "Have a Bite"), faces.map { it.name })
        assertEquals("3/2", faces[0].stats)
        assertNull(faces[1].stats, "a sorcery has no power and toughness")
    }

    @Test
    fun theStatBoxSaysWhicheverOfTheThreeTheCardHas() {
        assertEquals("3/4", Face(power = "3", toughness = "4").stats)
        // Tarmogoyf. `*` is why this is a string and why the phone's
        // power box had to stop raising a digits-only keyboard.
        assertEquals("*/1+*", Face(power = "*", toughness = "1+*").stats)
        assertEquals("4", Face(loyalty = "4").stats, "a planeswalker shows loyalty")
        assertEquals("6", Face(defense = "6").stats, "a battle shows defense")
        assertNull(Face().stats)
    }

    @Test
    fun aRowWithNothingPrintedOnItIsDropped() {
        // A card whose columns are all empty contributes no panel at
        // all, rather than an empty box with a border round it.
        val faces = CardQueries.decodeFaces(
            cols,
            listOf(row("99", "Nameless", "", "", "", "", null, null, null, null)),
        )
        assertTrue(faces.isEmpty(), "a blank face was rendered as a panel")
    }

    @Test
    fun theQueryNarrowsToOnePrintingBeforeReadingTheFace() {
        val sql = CardQueries.face("sol ring").sql
        // Without the CTE this returns one row per printing owned, so
        // Sol Ring came back twice with identical text. Caught by
        // running it against the real database rather than by reading
        // it.
        assertTrue(sql.contains("WITH pick"), sql)
        assertTrue(sql.contains("LIMIT 1"), "the printing is not narrowed to one")
        assertEquals(listOf("sol ring"), CardQueries.face("sol ring").params)
    }

    @Test
    fun theCardDetailExposesTheFrontFace() {
        val front = Face(name = "Adventurous Eater")
        val back = Face(name = "Have a Bite")
        assertEquals(front, CardDetail(faces = listOf(front, back)).face)
        assertNull(CardDetail().face, "a card with nothing loaded has no face")
    }
}
