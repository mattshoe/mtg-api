package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun row(name: String, id: Long = 1) = Found(id, name, null, "Instant", 2, "matt")

private fun jsonRow(vararg cells: String?) = JsonArray(
    cells.map { if (it == null) JsonNull else JsonPrimitive(it) },
)

/**
 * The quick-find state machine: what was typed, what came back, which
 * row is highlighted, and when any of it is worth a round trip.
 */
class QuickFindStateTest {

    private val three = PaletteState().opened().typed("bo")
        .found(listOf(row("Bolt"), row("Bolt Bend"), row("Boltwave")))

    @Test
    fun oneCharacterIsNotWorthARoundTrip() {
        assertFalse(PaletteState().worthAsking, "an untouched palette asked anyway")
        assertFalse(PaletteState().typed("b").worthAsking, "one character asked about most of the table")
        assertTrue(PaletteState().typed("bo").worthAsking)
        assertTrue(PaletteState().typed("bolt").worthAsking)
    }

    @Test
    fun whitespaceDoesNotCountTowardsTheMinimum() {
        assertFalse(PaletteState().typed("   ").worthAsking)
        assertFalse(PaletteState().typed("\t\n").worthAsking)
        assertFalse(PaletteState().typed(" b ").worthAsking, "a padded single character asked")
        assertTrue(PaletteState().typed("  bo  ").worthAsking, "a padded real term refused to ask")
    }

    @Test
    fun deletingBackUnderTheMinimumClearsWhatWasShowing() {
        val back = three.typed("b")
        assertTrue(back.items.isEmpty(), "the old answer sat under a term nobody asked about")
        assertEquals("b", back.term)
        assertEquals(0, back.active)
        assertFalse(back.busy)
        assertNull(back.chosen)
    }

    @Test
    fun typingSomethingRealIsBusyUntilTheAnswerLands() {
        val asked = PaletteState().opened().typed("bolt")
        assertTrue(asked.busy, "nothing marked it in flight, so the empty state flashed")
        assertFalse(asked.found(listOf(row("Bolt"))).busy)
        assertFalse(PaletteState().typed("b").busy, "it went busy over a term it will never ask")
    }

    @Test
    fun theFirstRowIsHighlightedWhenResultsLand() {
        assertEquals(0, three.active)
        assertEquals("Bolt", three.chosen?.name)
    }

    @Test
    fun theHighlightStopsAtBothEndsRatherThanWrapping() {
        assertEquals("Bolt Bend", three.down().chosen?.name)
        assertEquals("Boltwave", three.down().down().chosen?.name)
        assertEquals("Boltwave", three.down().down().down().down().chosen?.name)
        assertEquals(2, three.down().down().down().active)
        assertEquals("Bolt", three.up().chosen?.name)
        assertEquals(0, three.up().up().active)
        assertEquals("Bolt Bend", three.down().down().up().chosen?.name)
    }

    @Test
    fun arrowsOnAnEmptyListDoNothingAndNothingIsChosen() {
        val none = PaletteState().opened().typed("zzzz").found(emptyList())
        assertNull(none.chosen, "an empty list offered something to open")
        assertSame(none, none.down())
        assertSame(none, none.up())
        assertEquals(0, none.active)
    }

    @Test
    fun highlightingARowThatIsNotThereChangesNothing() {
        assertEquals(2, three.highlight(2).active)
        assertEquals(0, three.highlight(-1).active)
        assertEquals(0, three.highlight(3).active)
        assertEquals(0, three.highlight(99).active)
        assertSame(three, three.highlight(3))
    }

    @Test
    fun closingEmptiesItAndReopeningShowsNothingStale() {
        val shut = three.down().closed()
        assertFalse(shut.open)
        assertEquals("", shut.term)
        assertTrue(shut.items.isEmpty())
        assertEquals(0, shut.active)
        assertFalse(shut.busy)

        val again = shut.opened()
        assertTrue(again.open)
        assertEquals("", again.term)
        assertTrue(again.items.isEmpty(), "reopening showed the last search again")
        assertNull(again.chosen)
    }

    @Test
    fun anAnswerToATermThatHasBeenTypedOverIsDropped() {
        val asked = PaletteState().opened().typed("bo").typed("bolt")
        val late = asked.found(listOf(row("Bo Levar")), forTerm = "bo")
        assertTrue(late.items.isEmpty(), "a stale answer landed under a newer term")
        assertTrue(late.busy, "the stale answer also said the newer request had finished")

        val current = asked.found(listOf(row("Lightning Bolt")), forTerm = "bolt")
        assertEquals(listOf("Lightning Bolt"), current.items.map { it.name })
        assertFalse(current.busy)
    }

    @Test
    fun paddingDoesNotMakeAnAnswerLookStale() {
        val asked = PaletteState().opened().typed("  bolt ")
        assertEquals(1, asked.found(listOf(row("Bolt")), forTerm = "bolt").items.size)
    }

    @Test
    fun itNeverShowsMoreThanTheQueryAsksFor() {
        val many = (1..40).map { row("Card $it", it.toLong()) }
        assertEquals(PaletteState.LIMIT, PaletteState().typed("card").found(many).items.size)
    }

    @Test
    fun theStateAsksTheSameQuestionTheQueriesDo() {
        assertEquals(PaletteQueries.find("bolt").sql, PaletteState().typed("bolt").query().sql)
        assertEquals(PaletteQueries.find("bolt").params, PaletteState().typed("bolt").query().params)
    }
}

/** The LIKE the palette sends, and what it binds. */
class QuickFindQueryTest {

    @Test
    fun theTermIsTrimmedAndLowercasedIntoASubstringMatch() {
        assertEquals("%bolt%", PaletteQueries.find("  Bolt  ").params.first())
        assertEquals("%lightning bolt%", PaletteQueries.find("Lightning Bolt").params.first())
    }

    @Test
    fun everyPlaceholderGetsExactlyOneBoundValue() {
        val q = PaletteQueries.find("bolt")
        assertEquals(q.sql.count { it == '?' }, q.params.size, "placeholders and parameters disagree")
        assertEquals(1, q.params.distinct().size, "one term should mean one pattern")
    }

    @Test
    fun theTermIsBoundRatherThanPasted() {
        val q = PaletteQueries.find("O'Brien'; DROP TABLE cards --")
        assertFalse(q.sql.contains("DROP"), q.sql)
        assertFalse(q.sql.contains("O'Brien"), q.sql)
        assertEquals("%o'brien'; drop table cards --%", q.params.first())
    }

    @Test
    fun likeWildcardsSomebodyTypedAreOrdinaryCharacters() {
        // "50%" used to match every card in the table.
        assertEquals("%50\\%%", PaletteQueries.find("50%").params.first())
        assertEquals("%chandra\\_%", PaletteQueries.find("Chandra_").params.first())
    }

    @Test
    fun theEscapeCharacterIsItselfEscapedFirst() {
        assertEquals("%a\\\\b%", PaletteQueries.find("a\\b").params.first())
        // A backslash in front of a percent must not end up escaping
        // the percent: both are literal characters that were typed.
        assertEquals("%\\\\\\%%", PaletteQueries.find("\\%").params.first())
    }

    @Test
    fun everyLikeDeclaresTheEscapeCharacter() {
        val sql = PaletteQueries.find("bolt").sql
        assertEquals(3, sql.split("LIKE ?").size - 1, sql)
        assertEquals(3, sql.split("ESCAPE '\\'").size - 1, "a LIKE without ESCAPE keeps the wildcards")
    }

    @Test
    fun itLooksAtBothFacesAndGroupsByOwner() {
        val sql = PaletteQueries.find("bolt").sql
        assertTrue(sql.contains("name_norm LIKE ?"), sql)
        assertTrue(sql.contains("lower(face1) LIKE ?"), sql)
        assertTrue(sql.contains("lower(face2) LIKE ?"), sql)
        assertTrue(sql.contains("GROUP BY owner_id, name_norm"), sql)
        assertTrue(sql.contains("ORDER BY length(name), name"), sql)
    }

    @Test
    fun itAsksForNoMoreRowsThanItWillShow() {
        assertTrue(PaletteQueries.find("bolt").sql.contains("LIMIT ${PaletteState.LIMIT}"))
        assertEquals(12, PaletteState.LIMIT)
    }

    @Test
    fun anEmptyTermStillBuildsSomethingHarmless() {
        val q = PaletteQueries.find("   ")
        assertEquals(3, q.params.size)
        assertEquals("%%", q.params.first())
    }
}

/** Turning rows off the wire into list entries. */
class QuickFindDecodeTest {

    private val cols = listOf("id", "name", "scryfall_id", "type_line", "qty", "owner")

    @Test
    fun aFullRowDecodes() {
        val got = PaletteQueries.decode(
            cols,
            listOf(jsonRow("7", "Lightning Bolt", "abc-123", "Instant", "4", "matt")),
        ).single()
        assertEquals(Found(7, "Lightning Bolt", "abc-123", "Instant", 4, "matt"), got)
    }

    @Test
    fun nullsComeBackAsNullsOrSensibleDefaults() {
        val got = PaletteQueries.decode(
            cols,
            listOf(jsonRow(null, null, null, null, null, null)),
        ).single()
        assertEquals(0, got.id.toInt())
        assertEquals("", got.name)
        assertNull(got.scryfallId)
        assertNull(got.typeLine)
        assertEquals(0, got.qty)
        assertEquals("", got.owner)
    }

    @Test
    fun aMissingColumnIsNotAnError() {
        val got = PaletteQueries.decode(
            listOf("name", "owner"),
            listOf(jsonRow("Sol Ring", "matt")),
        ).single()
        assertEquals("Sol Ring", got.name)
        assertEquals("matt", got.owner)
        assertNull(got.scryfallId)
        assertNull(got.typeLine)
        assertEquals(0, got.qty)
    }

    @Test
    fun aRowShorterThanItsHeaderIsNotAnError() {
        val got = PaletteQueries.decode(cols, listOf(jsonRow("7", "Bolt"))).single()
        assertEquals(7, got.id.toInt())
        assertEquals("Bolt", got.name)
        assertEquals(0, got.qty)
        assertEquals("", got.owner)
    }

    @Test
    fun somethingThatIsNotANumberWhereANumberWasExpected() {
        val got = PaletteQueries.decode(
            cols,
            listOf(jsonRow("not-an-id", "Bolt", null, null, "lots", "matt")),
        ).single()
        assertEquals(0, got.id.toInt())
        assertEquals(0, got.qty)
        assertEquals("Bolt", got.name)
    }

    @Test
    fun anEmptyResultIsAnEmptyList() {
        assertEquals(emptyList(), PaletteQueries.decode(cols, emptyList()))
        assertEquals(emptyList(), PaletteQueries.decode(emptyList(), emptyList()))
    }

    @Test
    fun decodedRowsGoStraightIntoTheList() {
        val state = PaletteState().opened().typed("bolt").found(
            PaletteQueries.decode(cols, listOf(jsonRow("7", "Lightning Bolt", null, "Instant", "4", "matt"))),
        )
        assertEquals("Lightning Bolt", state.chosen?.name)
        assertEquals(4, state.chosen?.qty)
        assertFalse(state.busy)
    }
}
