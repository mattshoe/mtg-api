package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The query box, which is the part of the app people type into fastest
 * and notice least when it is subtly wrong.
 */
class QueryBoxTest {

    private fun sql(q: String) = parseQueryBox(q).sql
    private fun params(q: String) = parseQueryBox(q).params

    @Test
    fun anEmptyBoxConstrainsNothing() {
        assertEquals("", sql(""))
        assertEquals("", sql("   "))
    }

    @Test
    fun abareWordSearchesBothFaces() {
        assertTrue(sql("bolt").contains("c.face2"))
        assertEquals(listOf<Any?>("%bolt%", "%bolt%", "%bolt%"), params("bolt"))
    }

    @Test
    fun quotedRunsStayTogether() {
        assertEquals(listOf<Any?>("%draw a card%"), params("""o:"draw a card""""))
    }

    @Test
    fun aLeadingMinusNegates() {
        assertTrue(sql("-t:creature").startsWith("NOT ("))
        assertFalse(sql("t:creature").startsWith("NOT ("))
    }

    @Test
    fun everyAliasForTheSameThingMeansTheSameThing() {
        assertEquals(sql("mv<=3"), sql("cmc<=3"))
        assertEquals(sql("o:flying"), sql("oracle:flying"))
        assertEquals(sql("s:m3c"), sql("edition:m3c"))
        assertEquals(sql("ci:wu"), sql("identity:wu"))
    }

    @Test
    fun comparisonOperatorsCarryThrough() {
        assertTrue(sql("mv<=3").contains("c.cmc <= ?"))
        assertTrue(sql("mv>3").contains("c.cmc > ?"))
        assertTrue(sql("mv=3").contains("c.cmc = ?"))
        assertTrue(sql("mv!=3").contains("c.cmc != ?"))
        // A bare colon means "at least" for a number.
        assertTrue(sql("mv:3").contains("c.cmc >= ?"))
    }

    /** `c:wu` reads as Scryfall reads it, not as an exact match. */
    @Test
    fun aBareColourColonIsAtLeast() {
        val s = sql("c:wu")
        assertEquals(2, Regex("LIKE \\?").findAll(s).count())
        assertEquals(listOf<Any?>("%W%", "%U%"), params("c:wu"))
    }

    @Test
    fun colourWithLessThanOrEqualIsAtMost() {
        assertEquals(3, Regex("NOT LIKE").findAll(sql("ci<=wu")).count())
    }

    @Test
    fun colourWithEqualsIsExactAndAlphabetical() {
        assertEquals(listOf<Any?>("UW"), params("ci=wu"))
        assertEquals(listOf<Any?>("UW"), params("ci=uw"), "the order typed must not matter")
    }

    @Test
    fun colourAndIdentityUseDifferentColumns() {
        assertTrue(sql("c:w").contains("c.colors"))
        assertTrue(sql("ci:w").contains("c.color_identity"))
    }

    /** A smaller EDHREC rank is a better one, so a bare colon means at most. */
    @Test
    fun aBareEdhrecColonMeansRankedAtLeastThatWell() {
        assertTrue(sql("edhrec:100").contains("c.edhrec_rank <= ?"))
        assertTrue(sql("edhrec>=100").contains("c.edhrec_rank >= ?"))
    }

    @Test
    fun aBareYearColonIsEquality() {
        assertTrue(sql("year:2024").contains("= ?"))
        assertEquals(listOf<Any?>("2024"), params("year:2024"))
    }

    @Test
    fun isShapesExpandToTheirClause() {
        assertTrue(sql("is:commander").contains("Legendary"))
        assertTrue(sql("is:vanilla").contains("oracle_text IS NULL"))
        assertTrue(sql("is:unpriced").contains("pr.usd IS NULL"))
    }

    /** `not:` is `is:` inverted, and `-` inverts it again. */
    @Test
    fun notIsIsInvertedAndAMinusInvertsItBack() {
        assertTrue(sql("not:reprint").startsWith("NOT ("))
        assertFalse(sql("-not:reprint").startsWith("NOT ("))
        assertTrue(sql("-is:reprint").startsWith("NOT ("))
    }

    @Test
    fun severalTermsAllApply() {
        val s = sql("t:creature mv<=3 -is:reprint")
        assertEquals(3, s.split("\n  AND ").size)
    }

    /** A typo that quietly matches everything is worse than one that says so. */
    @Test
    fun anUnknownKeyIsRefusedRatherThanIgnored() {
        val e = assertFailsWith<QuerySyntax> { parseQueryBox("bogus:3") }
        assertTrue(e.message!!.contains("bogus"))
        assertTrue(e.message!!.contains("cheatsheet"))
    }

    @Test
    fun anUnknownIsShapeIsRefusedToo() {
        val e = assertFailsWith<QuerySyntax> { parseQueryBox("is:nonsense") }
        assertTrue(e.message!!.contains("is:nonsense"))
    }

    @Test
    fun everyUnknownIsReportedOnceRatherThanRepeatedly() {
        val e = assertFailsWith<QuerySyntax> { parseQueryBox("bogus:1 bogus:2 alsobad:3") }
        assertEquals(1, Regex("bogus").findAll(e.message!!).count())
        assertTrue(e.message!!.contains("alsobad"))
    }

    @Test
    fun valuesAreBoundNeverInterpolated() {
        val q = """a:"'; DROP TABLE cards; --" set:'; DELETE FROM cards; --"""
        val parsed = runCatching { parseQueryBox(q) }.getOrNull() ?: return
        assertFalse(parsed.sql.contains("DROP"), parsed.sql)
        assertFalse(parsed.sql.contains("DELETE"), parsed.sql)
    }

    @Test
    fun nonNumericComparisonsDoNotProduceGarbageSql() {
        assertTrue(sql("mv<=banana").contains("c.cmc <= ?"))
        assertEquals(listOf<Any?>(0.0), params("mv<=banana"))
    }
}

/** The box and the panel, combined. */
class AdvancedWithPanelTest {

    @Test
    fun theBoxIsAndedOnTopOfThePanel() {
        val f = Filters(owner = "matt", adv = "t:creature")
        val s = conditions(f)
        assertTrue(s.sql.contains("c.owner = ?"))
        assertTrue(s.sql.contains("c.type_line"))
        assertEquals(listOf<Any?>("matt", "%creature%"), s.params)
    }

    @Test
    fun theBoxComesAfterThePanelSoItReadsInOrder() {
        val s = conditions(Filters(owner = "matt", adv = "t:creature")).sql
        assertTrue(s.indexOf("c.owner") < s.indexOf("c.type_line"))
    }

    @Test
    fun anEmptyBoxAddsNothing() {
        assertEquals(conditions(Filters(owner = "matt")).sql, conditions(Filters(owner = "matt", adv = "  ")).sql)
    }

    @Test
    fun aBadBoxThrowsForTheCallerToShow() {
        assertFailsWith<QuerySyntax> { conditions(Filters(adv = "bogus:1")) }
    }
}
