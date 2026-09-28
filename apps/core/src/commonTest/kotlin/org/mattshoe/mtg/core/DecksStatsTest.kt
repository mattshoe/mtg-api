package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecksTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }
    private val cols = listOf("slug", "name", "owner", "commander", "colors", "bracket", "art_id")

    @Test
    fun aDeckDecodes() {
        val d = DeckQueries.decode(
            cols,
            rows("""["alela","Alela","matt","Alela, Artful Provocateur (ELD) 324","UW",3,"abc"]"""),
        ).single()
        assertEquals("alela", d.slug)
        assertEquals("matt", d.owner)
        assertEquals(3, d.bracket)
        assertEquals(listOf("U", "W"), d.colorPips)
    }

    /** Commanders are stored with the set annotation often enough that
     *  matching the whole string finds nothing. */
    @Test
    fun theCommanderNameDropsItsSetAnnotation() {
        val d = DeckQueries.decode(
            cols,
            rows("""["a","A","matt","Alela, Artful Provocateur (ELD) 324",null,null,null]"""),
        ).single()
        assertEquals("Alela, Artful Provocateur", d.commanderName)
    }

    @Test
    fun aDeckWithNoCommanderSaysSoRatherThanGuessing() {
        val d = DeckQueries.decode(cols, rows("""["a","A","matt",null,null,null,null]""")).single()
        assertNull(d.commanderName)
        assertTrue(d.colorPips.isEmpty())
    }

    /**
     * A commander with nine printings must not multiply its deck row by
     * nine. The subquery collapses to one row per name first.
     */
    @Test
    fun theArtJoinCollapsesPrintingsFirst() {
        val sql = DeckQueries.all().sql
        assertTrue(sql.contains("GROUP BY name_norm"), sql)
        assertTrue(sql.contains("MIN(id)"))
    }

    @Test
    fun oneDecksCardsAreBoundBySlug() {
        val q = DeckQueries.cards("alela")
        assertEquals(listOf<Any?>("alela"), q.params)
        assertTrue(q.sql.contains("d.slug = ?"))
    }

    @Test
    fun gapsAreTheCardsTheOwnerIsShortOf() {
        val s = DecksState().opened(
            "alela",
            listOf(
                DeckCard("Sol Ring", qty = 1, role = null, owned = 1),
                DeckCard("Mana Crypt", qty = 1, role = null, owned = 0),
                DeckCard("Island", qty = 10, role = null, owned = 4),
            ),
        )
        assertEquals(listOf("Mana Crypt", "Island"), s.gaps.map { it.name })
        assertEquals(12, s.totalCards)
    }

    @Test
    fun decksGroupByOwnerInAStableOrder() {
        val s = DecksState().loaded(
            listOf(
                Deck("b", "B", "matt", null, null, null, null),
                Deck("a", "A", "kayla", null, null, null, null),
                Deck("c", "C", "matt", null, null, null, null),
            ),
        )
        assertEquals(listOf("kayla", "matt"), s.byOwner.map { it.first })
        assertEquals(2, s.byOwner.last().second.size)
    }

    @Test
    fun closingADeckForgetsItsCards() {
        val s = DecksState().opened("alela", listOf(DeckCard("Sol Ring", 1, null, 1))).close()
        assertNull(s.openSlug)
        assertTrue(s.cards.isEmpty())
    }
}

class StatsTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }

    @Test
    fun theDefaultScopeIsEveryone() {
        assertEquals("Both", StatsScope().label)
        assertTrue(StatsQueries.totals(StatsScope()).params.isEmpty())
    }

    /**
     * The scope appears in eight subqueries, so it has to be bound eight
     * times. One short and SQLite silently shifts every later parameter.
     */
    @Test
    fun aScopedQueryBindsTheOwnerOncePerSubquery() {
        val q = StatsQueries.totals(StatsScope(Owner.MATT))
        assertEquals(8, q.params.size)
        assertTrue(q.params.all { it == "matt" })
        assertEquals(8, Regex("owner = \\?").findAll(q.sql).count())
    }

    @Test
    fun anUnscopedQueryStillSlotsIntoAWhere() {
        assertTrue(StatsQueries.totals(StatsScope()).sql.contains("WHERE 1=1"))
    }

    @Test
    fun theSideBySideIsAlwaysBoth() {
        assertTrue(StatsQueries.perOwner().params.isEmpty())
        assertTrue(StatsQueries.perOwner().sql.contains("GROUP BY c.owner"))
    }

    @Test
    fun totalsDecode() {
        val cols = listOf(
            "printings", "uniques", "physical", "decks", "free", "sets", "foils", "value", "priced_at",
        )
        val t = StatsQueries.decode(cols, rows("""[6032,3481,3743,24,4144,190,311,5046,"2026-09-27"]"""))
        assertEquals(6032, t.printings)
        assertEquals(3743, t.physical)
        assertEquals(24, t.decks)
        assertEquals(5046.0, t.value)
        assertEquals("2026-09-27", t.pricedAt)
    }

    @Test
    fun missingNumbersAreZeroRatherThanACrash() {
        val t = StatsQueries.decode(listOf("printings"), rows("[null]"))
        assertEquals(0, t.printings)
        assertNull(t.value)
    }

    @Test
    fun anEmptyResultIsZeroesNotAnException() {
        assertEquals(Totals(), StatsQueries.decode(listOf("printings"), emptyList()))
    }
}
