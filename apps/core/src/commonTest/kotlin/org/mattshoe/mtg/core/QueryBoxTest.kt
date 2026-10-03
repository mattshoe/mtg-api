package org.mattshoe.mtg.core

import kotlin.test.Ignore
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

    // -------------------------------------------- keys the cheatsheet
    // examples never happen to exercise: loy, price/usd, restricted.

    @Test
    fun loyaltyComparesRealNumbersOnlyLikePowerAndToughnessDo() {
        val s = sql("loy>=3")
        assertTrue(s.contains("c.loyalty GLOB '[0-9]*'"), s)
        assertTrue(s.contains("CAST(c.loyalty AS INTEGER) >= ?"), s)
        assertEquals(listOf<Any?>(3.0), params("loy>=3"))
    }

    @Test
    fun loyAndLoyaltyAreTheSameKey() {
        assertEquals(sql("loy<=2"), sql("loyalty<=2"))
    }

    @Test
    fun aNonNumericLoyaltyFallsBackRatherThanThrowing() {
        assertEquals(listOf<Any?>(0.0), params("loy>=X"))
    }

    @Test
    fun priceComparesAgainstTheSamePriceExpressionEveryOtherSortUses() {
        // PRICE_EXPR picks the foil or etched price when that is the
        // finish owned — a plain `c.price` column would answer for the
        // wrong printing.
        val s = sql("price>=5")
        assertTrue(s.contains("CASE c.finish"), s)
        assertTrue(s.contains(") >= ?"), s)
        assertEquals(listOf<Any?>(5.0), params("price>=5"))
    }

    @Test
    fun usdAndPriceAreTheSameKey() {
        assertEquals(sql("usd<=1.5"), sql("price<=1.5"))
    }

    @Test
    fun restrictedChecksTheLegalitiesTableForThatStatusSpecifically() {
        // Not the same question as `banned:` — a card can be legal in a
        // format's card pool while being restricted to one copy.
        val s = sql("restricted:vintage")
        assertTrue(s.contains("l.status = 'restricted'"), s)
        assertFalse(s.contains("l.status = 'banned'"), s)
        assertEquals(listOf<Any?>("vintage"), params("restricted:vintage"))
    }

    // -------------------------------------------- quoting shapes

    @Test
    fun singleQuotesHoldAPhraseTogetherJustLikeDoubleQuotesDo() {
        assertEquals(listOf<Any?>("%draw a card%"), params("o:'draw a card'"))
    }

    @Test
    fun aBareQuotedPhraseWithNoKeySearchesBothFacesLikeAnyOtherBareWord() {
        // `"Lightning Bolt"` typed with no key in front is still a name
        // search, just one term instead of two words ANDed separately.
        val q = "\"Lightning Bolt\""
        assertEquals(
            listOf<Any?>("%lightning bolt%", "%lightning bolt%", "%lightning bolt%"),
            params(q),
        )
        assertFalse(sql(q).startsWith("NOT ("))
    }

    @Test
    fun aMinusBeforeABareQuotedPhraseNegatesTheWholePhrase() {
        val q = "-\"Lightning Bolt\""
        assertTrue(sql(q).startsWith("NOT ("), sql(q))
    }

    // -------------------------------------------- hostile values

    @Test
    fun anApostropheInAValueSurvivesIntoTheBoundParameterUnescaped() {
        // It is bound, never interpolated, so there is nothing to escape
        // for SQL's sake — it only has to not be mangled on the way.
        assertEquals(listOf<Any?>("%o'brien%", "%o'brien%", "%o'brien%"), params("n:O'Brien"))
    }

    @Test
    fun aBackslashInAValueSurvivesIntoTheBoundParameterUnescaped() {
        val raw = "back\\slash"
        val expected = "%" + raw.lowercase() + "%"
        assertEquals(listOf<Any?>(expected, expected, expected), params("n:$raw"))
    }

    @Test
    fun aCommaInAQuotedValueStaysPartOfTheOneValue() {
        assertEquals(listOf<Any?>("%kardur, doomscourge%"), params("""o:"Kardur, Doomscourge""""))
    }

    @Test
    fun accentedLettersAreLoweredLikeAnyOtherLetter() {
        assertEquals(listOf<Any?>("%æther vial%", "%æther vial%", "%æther vial%"), params("""n:"Æther Vial""""))
    }

    @Test
    fun aDoubleFacedNameWithASlashSlashIsOneOrdinaryBareTerm() {
        val q = "\"Fable of the Mirror-Breaker // Reflection of Kiki-Jiki\""
        val p = params(q)
        assertEquals(3, p.size)
        assertTrue((p[0] as String).contains("//"), p[0].toString())
    }

    @Test
    fun aVeryLongBareWordIsStillOneSearchTermRatherThanBeingCutOff() {
        val longName = "A".repeat(200)
        val p = params(longName)
        assertEquals(3, p.size)
        assertEquals("%${longName.lowercase()}%", p[0])
    }

    /**
     * Real bug: `QueryBox`'s own `like()` just wraps the value in `%...%`
     * with no escaping, unlike `Clauses.like()` in CardFilters.kt — which
     * exists for exactly this reason, per its own comment: "%" and "_"
     * are ordinary characters to someone searching for "50%" or
     * "Chandra_", and treating them otherwise silently matches
     * everything. Typing a literal percent or underscore into the query
     * box silently widens the search instead of narrowing it, with no
     * error and no sign anything went wrong — the exact failure mode
     * this file's own doc comment says is worse than a crash.
     */
    @Ignore(
        "REAL BUG: QueryBox's like() does not escape % or _ before building " +
            "the LIKE pattern, so a literal percent or underscore typed into " +
            "the box is read as a SQL wildcard instead of a literal character. " +
            "See CardFilters.kt's Clauses.like(), which escapes both for this " +
            "exact reason.",
    )
    @Test
    fun aLiteralPercentOrUnderscoreIsEscapedRatherThanActingAsAWildcard() {
        assertTrue(sql("t:50%").contains("ESCAPE"), "a literal % must be escaped, not left as a SQL wildcard")
        assertTrue(sql("a:Chandra_").contains("ESCAPE"), "a literal _ must be escaped, not left as a SQL wildcard")
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
