package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardDetailTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }

    @Test
    fun artIsDerivedFromTheIdAlreadyOnTheRow() {
        assertEquals(
            "https://cards.scryfall.io/normal/front/a/b/abcdef.jpg",
            CardQueries.art("abcdef"),
        )
        assertEquals(
            "https://cards.scryfall.io/small/front/a/b/abcdef.jpg",
            CardQueries.art("abcdef", "small"),
        )
    }

    @Test
    fun aMissingOrShortIdGivesNoUrlRatherThanABrokenOne() {
        assertNull(CardQueries.art(null))
        assertNull(CardQueries.art(""))
        assertNull(CardQueries.art("a"))
    }

    @Test
    fun printingsDecodeAndSumToWhatIsOwned() {
        val cols = listOf("id", "setcode", "set_name", "collector_number", "finish", "qty", "scryfall_id")
        val p = CardQueries.decodePrintings(
            cols,
            rows("""[1,"m3c","MH3","409","nonfoil",2,"abc"]""", """[2,"2x2","2X2","117","foil",1,"def"]"""),
        )
        val card = CardDetail(name = "Sol Ring", owner = "matt", printings = p)
        assertEquals(2, card.printings.size)
        assertEquals(3, card.owned)
    }

    /** A proxy in a deck does not consume a real card. */
    @Test
    fun proxiesDoNotCountAgainstWhatIsFree() {
        val cols = listOf("slug", "name", "owner", "is_proxy", "qty", "role")
        val uses = CardQueries.decodeUses(
            cols,
            rows("""["a","Alela","matt",0,1,null]""", """["b","Bello","matt",1,1,null]"""),
        )
        val card = CardDetail(
            printings = listOf(Printing(1, "m3c", "MH3", "409", "nonfoil", 2, "abc")),
            usedIn = uses,
        )
        assertEquals(2, card.owned)
        assertEquals(1, card.committed, "the proxy must not be counted")
        assertEquals(1, card.free)
        assertFalse(card.overCommitted)
    }

    @Test
    fun moreDecksThanCopiesIsFlaggedAndFreeNeverGoesNegative() {
        val card = CardDetail(
            printings = listOf(Printing(1, "m3c", null, null, "nonfoil", 1, null)),
            usedIn = listOf(
                DeckUse("a", "A", "matt", 1, null, false),
                DeckUse("b", "B", "matt", 1, null, false),
            ),
        )
        assertTrue(card.overCommitted)
        assertEquals(0, card.free)
    }

    @Test
    fun theQueriesBindNameAndOwner() {
        assertEquals(listOf<Any?>("sol ring", "matt"), CardQueries.printings("sol ring", "matt").params)
        assertEquals(listOf<Any?>("sol ring", "matt"), CardQueries.usedIn("sol ring", "matt").params)
    }
}

class ConsoleTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }

    @Test
    fun aTableKeepsColumnOrderAndNulls() {
        val t = Table.of(listOf("name", "qty"), rows("""["Sol Ring",3]""", """["Opt",null]"""))
        assertEquals(listOf("name", "qty"), t.cols)
        assertEquals(listOf("Sol Ring", "3"), t.rows[0])
        assertNull(t.rows[1][1])
    }

    @Test
    fun nothingToRunIsNotRunnable() {
        assertFalse(ConsoleState().canRun)
        assertFalse(ConsoleState(sql = "   ").canRun)
        assertTrue(ConsoleState(sql = "SELECT 1").canRun)
        assertFalse(ConsoleState(sql = "SELECT 1").running().canRun, "not while one is in flight")
    }

    @Test
    fun aFailureClearsTheStaleResultRatherThanLeavingItOnScreen() {
        val s = ConsoleState(sql = "SELECT 1").ran(Table(listOf("x"), listOf(listOf("1"))), 4)
        assertNotNull(s.result)
        val failed = s.failed("near \"SELEC\": syntax error")
        assertNull(failed.result, "an old table under a new error reads as if it worked")
        assertEquals("near \"SELEC\": syntax error", failed.error)
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}

class LogsTest {

    private fun line(status: Int, level: String = "info", ms: Int = 10) =
        LogLine("2026-09-28T00:00:00Z", level, "query", "POST", "/query", status, ms, null)

    @Test
    fun failuresAreStatusOrLevel() {
        assertTrue(line(500).failed)
        assertTrue(line(401).failed)
        assertTrue(line(200, level = "error").failed)
        assertFalse(line(200).failed)
    }

    @Test
    fun slowIsOverASecond() {
        assertTrue(line(200, ms = 1500).slow)
        assertFalse(line(200, ms = 999).slow)
    }

    @Test
    fun theErrorsToggleNarrowsWithoutLosingTheRest() {
        val s = LogsState().loaded(listOf(line(200), line(500), line(200)))
        assertEquals(3, s.shown.size)
        assertEquals(1, s.errorCount)
        val only = s.toggleErrors()
        assertEquals(1, only.shown.size)
        assertEquals(3, only.lines.size, "narrowing the view must not drop the data")
        assertEquals(3, only.toggleErrors().shown.size)
    }

    @Test
    fun logLinesDecodeFromNamedColumns() {
        val l = LogQueries.decode(
            listOf(mapOf("ts" to "t", "level" to "warn", "status" to "404", "ms" to "12", "path" to "/x")),
        ).single()
        assertEquals("warn", l.level)
        assertEquals(404, l.status)
        assertEquals(12, l.ms)
        assertTrue(l.failed)
    }
}
