package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rulings on a card, before anything draws them.
 *
 * The list arrives from a join on `oracle_id`, which means a card
 * with two faces gets the same ruling twice, and from an import that
 * has put three different things in `published_at` over the years —
 * an ISO day, a full timestamp, and nothing. None of that is a
 * screen's problem, so all of it is settled here and both the web
 * page and the phone read the same answer.
 */
class RulingsTest {

    private fun rows(vararg json: String): List<JsonArray> =
        json.map { Json.parseToJsonElement(it) as JsonArray }

    private fun card(vararg rulings: Ruling) = CardDetail(name = "Sol Ring", rulings = rulings.toList())

    // ------------------------------------------------------ the date

    @Test
    fun anIsoDayIsShownExactlyAsItIsStored() {
        assertEquals("2018-12-07", Ruling("2018-12-07", "It checks the battlefield.").day)
    }

    @Test
    fun aTimestampIsCutBackToTheDay() {
        // Scryfall has handed back both forms. A wall of
        // "T00:00:00.000Z" in front of every ruling is noise.
        assertEquals("2004-10-04", Ruling("2004-10-04T00:00:00.000Z", "Old.").day)
        assertEquals("2004-10-04", Ruling("2004-10-04 00:00:00", "Old.").day)
    }

    @Test
    fun aDateNobodyCanReadIsNoDateRatherThanRubbishOnTheLine() {
        // A wrong date in front of a ruling reads as part of it.
        assertEquals("", Ruling("", "No date at all.").day)
        assertEquals("", Ruling("soon", "Words, not a date.").day)
        assertEquals("", Ruling("2018-13-07", "Thirteenth month.").day)
        assertEquals("", Ruling("2018-12-45", "Forty-fifth day.").day)
        assertEquals("", Ruling("07/12/2018", "The other way round.").day)
        assertEquals("", Ruling("2018-12", "Half a date.").day)
    }

    // ------------------------------------------------------ the text

    @Test
    fun theRulingItselfIsTrimmedAndOtherwiseUntouched() {
        val r = Ruling("2018-12-07", "  Angle brackets <i> and an ampersand & stay as they are.  ")
        assertEquals("Angle brackets <i> and an ampersand & stay as they are.", r.body)
        assertTrue(r.sayable)
    }

    @Test
    fun aRulingWithNoWordsInItIsNotARuling() {
        assertFalse(Ruling("2018-12-07", "   ").sayable)
        assertFalse(Ruling("2018-12-07", "").sayable)
        assertEquals(emptyList(), card(Ruling("2018-12-07", "  ")).rulingsShown)
    }

    // ----------------------------------------------------- the order

    @Test
    fun rulingsReadOldestFirstWhateverOrderTheyArrivedIn() {
        val shown = card(
            Ruling("2019-05-03", "Third."),
            Ruling("2004-10-04", "First."),
            Ruling("2018-12-07", "Second."),
        ).rulingsShown
        assertEquals(listOf("First.", "Second.", "Third."), shown.map { it.body })
    }

    @Test
    fun twoRulingsOnTheSameDayKeepTheOrderTheyCameIn() {
        val shown = card(
            Ruling("2018-12-07", "One."),
            Ruling("2018-12-07", "Two."),
        ).rulingsShown
        assertEquals(listOf("One.", "Two."), shown.map { it.body })
    }

    @Test
    fun anUndatedRulingGoesLastRatherThanToTheTopOfTheList() {
        // "" sorts before every real date, so the one ruling nobody
        // can date would otherwise lead the section.
        val shown = card(
            Ruling("", "Undated."),
            Ruling("2018-12-07", "Dated."),
        ).rulingsShown
        assertEquals(listOf("Dated.", "Undated."), shown.map { it.body })
    }

    // ------------------------------------------------- the duplicates

    @Test
    fun theSameRulingTwiceIsShownOnce() {
        // A two-faced card joins through `oracle_id` and gets every
        // ruling back once per face.
        val shown = card(
            Ruling("2018-12-07", "It checks the battlefield."),
            Ruling("2018-12-07", "It checks the battlefield."),
        ).rulingsShown
        assertEquals(1, shown.size, "the same ruling was printed twice")
    }

    @Test
    fun aRepeatInADifferentDateFormatIsStillARepeat() {
        val shown = card(
            Ruling("2018-12-07", "Same words."),
            Ruling("2018-12-07T00:00:00Z", " Same words. "),
        ).rulingsShown
        assertEquals(1, shown.size, "the same ruling twice over, once with a timestamp")
    }

    @Test
    fun twoDifferentRulingsOnOneDayBothSurvive() {
        val shown = card(
            Ruling("2018-12-07", "One thing."),
            Ruling("2018-12-07", "A different thing."),
        ).rulingsShown
        assertEquals(2, shown.size, "two real rulings were collapsed into one")
    }

    // --------------------------------------------------- nothing, and
    //                                                      everything

    @Test
    fun aCardWithNoRulingsHasNothingToShowAndDoesNotPretendOtherwise() {
        assertEquals(emptyList(), CardDetail(name = "Sol Ring").rulingsShown)
    }

    @Test
    fun aCardWithOneRulingShowsThatOne() {
        val shown = card(Ruling("2018-12-07", "Only.")).rulingsShown
        assertEquals(1, shown.size)
        assertEquals("2018-12-07", shown.single().day)
        assertEquals("Only.", shown.single().body)
    }

    @Test
    fun manyRulingsAllSurviveInOrder() {
        val many = (1..12).map { Ruling("2018-12-" + it.toString().padStart(2, '0'), "Ruling $it.") }
        val shown = CardDetail(rulings = many.reversed()).rulingsShown
        assertEquals(12, shown.size)
        assertEquals(many.map { it.body }, shown.map { it.body })
    }

    // --------------------------------------------------- what arrives

    @Test
    fun aMissingDateFromTheDatabaseDecodesToAnUndatedRuling() {
        val decoded = CardQueries.decodeRulings(
            listOf("published_at", "comment"),
            rows("""[null,"No date on this row."]"""),
        )
        assertEquals(listOf(Ruling("", "No date on this row.")), decoded)
        assertEquals("", decoded.single().day)
        assertTrue(decoded.single().sayable)
    }

    @Test
    fun theQueryAsksForThemOldestFirstAndBindsOnlyTheName() {
        val q = CardQueries.rulings("sol ring")
        assertTrue(q.sql.contains("ORDER BY r.published_at"), q.sql)
        assertEquals(listOf("sol ring"), q.params)
        assertEquals(1, q.sql.count { it == '?' }, "one placeholder, one bound value")
    }
}
