package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search semantics, pinned down on every platform.
 *
 * These are the cases where getting it subtly wrong would be invisible —
 * a colour mode that silently matches nothing, a bound value that turns
 * into string concatenation, a default that quietly changes what the
 * Library shows. The web has had all of this right for a while; this is
 * what stops the Kotlin port drifting off it.
 */
class CardFiltersTest {

    private fun where(f: Filters) = conditions(f).sql
    private fun params(f: Filters) = conditions(f).params

    // --------------------------------------------------------- defaults

    @Test
    fun theDefaultsAreBothCollectionsAndPriceDescending() {
        val f = Filters()
        assertEquals("both", f.owner)
        assertEquals(Sort.PRICE, f.sort)
        assertTrue(f.descending)
        assertEquals(100, f.size)
        assertEquals(ColorMode.ATMOST, f.colorMode)
        assertEquals(ColorTarget.IDENTITY, f.colorTarget)
    }

    @Test
    fun theDefaultSearchConstrainsNothing() {
        assertEquals("", where(Filters()))
        assertTrue(params(Filters()).isEmpty())
    }

    @Test
    fun bothMeansNoOwnerClause() {
        assertFalse(where(Filters(owner = "both")).contains("c.owner"))
        assertTrue(where(Filters(owner = "e7de0cb1")).contains("c.owner_id = (SELECT id FROM users WHERE key = ?)"))
        assertEquals(listOf<Any?>("e7de0cb1"), params(Filters(owner = "e7de0cb1")))
    }

    // ---------------------------------------------------------- colours

    /**
     * Colour identity is stored alphabetically — 'UW', never 'WU'. An
     * exact match that sorts the other way matches nothing at all, and
     * says nothing while doing it.
     */
    @Test
    fun exactlyBindsColoursInAlphabeticalOrder() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("U", "W"))
        assertEquals(listOf<Any?>("UW"), params(f))
        val g = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("W", "U"))
        assertEquals(listOf<Any?>("UW"), params(g), "order typed must not change the query")
    }

    @Test
    fun exactlyColourlessIsAnEmptyString() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("C"))
        assertTrue(where(f).contains("= ''"))
    }

    /** "At most WU" has to return Sol Ring. That is the whole point of it. */
    @Test
    fun atMostExcludesEveryColourNotChosen() {
        val f = Filters(colorMode = ColorMode.ATMOST, colors = listOf("W", "U"))
        val sql = where(f)
        assertEquals(3, Regex("NOT LIKE").findAll(sql).count(), "should exclude B, R and G")
        assertEquals(listOf<Any?>("%B%", "%R%", "%G%"), params(f))
    }

    @Test
    fun atLeastRequiresEveryColourChosen() {
        val f = Filters(colorMode = ColorMode.ATLEAST, colors = listOf("B", "G"))
        assertEquals(2, Regex("LIKE \\?").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%B%", "%G%"), params(f))
    }

    @Test
    fun anyOfIsOneOrClause() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = listOf("R", "G"))
        val sql = where(f)
        assertTrue(sql.contains(" OR "), sql)
        assertEquals(1, Regex("\\(").findAll(sql).count() - Regex("COALESCE\\(").findAll(sql).count())
    }

    @Test
    fun anyOfIncludesColourlessWhenAsked() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = listOf("R", "C"))
        assertTrue(where(f).contains("= ''"))
    }

    @Test
    fun noColoursChosenAddsNoColourClause() {
        assertFalse(where(Filters(colorMode = ColorMode.EXACTLY)).contains("COALESCE"))
    }

    @Test
    fun theColourTargetPicksTheColumn() {
        assertTrue(where(Filters(colors = listOf("W"))).contains("c.color_identity"))
        assertTrue(
            where(Filters(colors = listOf("W"), colorTarget = ColorTarget.PRINTED)).contains("c.colors"),
        )
    }

    // ------------------------------------------------------------ words

    @Test
    fun aNameSearchLooksAtBothFacesAndIsBound() {
        val f = Filters(q = "Bolt")
        val sql = where(f)
        assertTrue(sql.contains("c.face2"))
        // A plain placeholder. A numbered one renumbers every bare
        // `?` in the same statement, which misbound the owner filter
        // next to it.
        assertFalse(sql.contains("?1"), sql)
        // One per word, not one per word per column: three columns
        // times enough words put the statement over D1's hundred-
        // parameter ceiling and the search stopped working.
        assertEquals(listOf<Any?>("%bolt%"), params(f))
    }

    @Test
    fun oracleTextUsesFullTextSearch() {
        assertTrue(where(Filters(text = "proliferate")).contains("card_search MATCH ?"))
    }

    @Test
    fun thereIsNoSeparateLiteralTextBoxAnyMore() {
        // A quoted phrase in the rules-text box does what "Exact
        // text" did, so the second box was one more thing to keep in
        // step for nothing.
        val sql = where(FilterUrl.fromHash("?textLike=enters+tapped"))
        assertFalse(sql.contains("oracle_text"), sql)
        // And the phrase that replaced it.
        val phrase = where(Filters(text = "\"enters tapped\""))
        assertTrue(phrase.contains("card_search MATCH ?"), phrase)
    }

    @Test
    fun manaCostIgnoresSpacing() {
        assertEquals(listOf<Any?>("%{1}{u}%"), params(Filters(manaCost = "{1} {U}")))
    }

    // --------------------------------------------------------- numerics

    @Test
    fun emptyNumbersAddNothing() {
        assertEquals("", where(Filters(cmcMin = "", cmcMax = "", qtyMin = "")))
    }

    @Test
    fun nonsenseNumbersAddNothingRatherThanBreakingTheQuery() {
        assertEquals("", where(Filters(cmcMin = "abc", priceMax = "banana")))
    }

    @Test
    fun powerComparesOnlyRealNumbers() {
        val sql = where(Filters(pow = "3", powOp = ">="))
        assertTrue(sql.contains("GLOB '[0-9]*'"), "a power of '1+*' must not be compared as a number")
        assertTrue(sql.contains("CAST(c.power AS INTEGER) >= ?"))
    }

    @Test
    fun anUnknownOperatorFallsBackRatherThanInterpolating() {
        val sql = where(Filters(pow = "3", powOp = "; DROP TABLE cards --"))
        assertFalse(sql.contains("DROP"), "an operator must never reach the SQL unchecked")
        assertTrue(sql.contains(">="))
    }

    // ------------------------------------------------------------ lists

    @Test
    fun rarityBecomesAnInListWithOnePlaceholderEach() {
        val f = Filters(rarities = listOf("rare", "mythic"))
        assertTrue(where(f).contains("c.rarity IN (?,?)"))
        assertEquals(listOf<Any?>("rare", "mythic"), params(f))
    }

    @Test
    fun setCodesAreComparedLowercase() {
        val f = Filters(sets = listOf("M3C", "2X2"))
        assertTrue(where(f).contains("lower(c.setcode) IN"))
        assertEquals(listOf<Any?>("m3c", "2x2"), params(f))
    }

    @Test
    fun anEmptyListAddsNothing() {
        assertEquals("", where(Filters(rarities = emptyList(), sets = emptyList())))
    }

    // ------------------------------------------------------------ flags

    @Test
    fun flagsGoThreeWays() {
        assertEquals("", where(Filters(flags = mapOf(Flag.RESERVED to Tri.ANY))))
        assertTrue(where(Filters(flags = mapOf(Flag.RESERVED to Tri.YES))).contains("c.reserved = 1"))
        assertTrue(
            where(Filters(flags = mapOf(Flag.RESERVED to Tri.NO))).contains("COALESCE(c.reserved, 0) = 0"),
        )
    }

    @Test
    fun theGameChangerFlagUsesItsDatabaseColumn() {
        assertTrue(
            where(Filters(flags = mapOf(Flag.GAME_CHANGER to Tri.YES))).contains("c.game_changer = 1"),
        )
    }

    // ------------------------------------------------------------- pool

    @Test
    fun poolFiltersOnFreeCopies() {
        assertTrue(where(Filters(pool = Pool.FREE)).contains("COALESCE(u.free, 0) > 0"))
        assertTrue(where(Filters(pool = Pool.COMMITTED)).contains("COALESCE(u.free, 0) <= 0"))
        assertEquals("", where(Filters(pool = Pool.ALL)))
    }

    @Test
    fun deckAnyAndNoneAreTheirOwnClauses() {
        assertTrue(where(Filters(deck = "_any")).startsWith("EXISTS"))
        assertTrue(where(Filters(deck = "_none")).startsWith("NOT EXISTS"))
        assertEquals(listOf<Any?>("alela"), params(Filters(deck = "alela")))
    }

    // ------------------------------------------------------------ query

    @Test
    fun theCountQueryCountsGroupedRowsNotPrintings() {
        val sql = buildQuery(Filters(), countOnly = true).sql
        assertTrue(sql.startsWith("SELECT COUNT(*) FROM ("))
        assertTrue(sql.contains("GROUP BY c.name_norm"))
    }

    @Test
    fun thePageQueryGroupsPerCard() {
        val sql = buildQuery(Filters()).sql
        assertTrue(sql.contains("GROUP BY c.name_norm"))
        assertTrue(sql.contains("MIN(c.id) AS id"))
        assertTrue(sql.contains("SUM(c.qty) AS qty"))
    }

    @Test
    fun andNotPerOwner() {
        // A card they both own was two tiles with the same picture and
        // the same name, and nothing on a tile says whose it is.
        assertFalse(
            buildQuery(Filters()).sql.contains("GROUP BY c.owner"),
            "the library is still splitting a card into one row per owner",
        )
        assertFalse(
            buildQuery(Filters(), countOnly = true).sql.contains("GROUP BY c.owner"),
            "the count is still counting a card once per owner",
        )
    }

    @Test
    fun paginationIsPageTimesSize() {
        assertTrue(buildQuery(Filters(page = 1)).sql.contains("LIMIT 100 OFFSET 0"))
        assertTrue(buildQuery(Filters(page = 3)).sql.contains("LIMIT 100 OFFSET 200"))
        assertTrue(buildQuery(Filters(page = 2, size = 25)).sql.contains("LIMIT 25 OFFSET 25"))
    }

    @Test
    fun aPageBelowOneDoesNotProduceANegativeOffset() {
        assertTrue(buildQuery(Filters(page = 0)).sql.contains("OFFSET 0"))
    }

    @Test
    fun nullsSortLastWhicheverDirection() {
        val asc = buildQuery(Filters(sort = Sort.PRICE, descending = false)).sql
        assertTrue(asc.contains("IS NULL"), "a card with no price must not lead the list")
        assertTrue(asc.contains("ASC"))
        assertTrue(buildQuery(Filters()).sql.contains("DESC"))
    }

    @Test
    fun nameIsAlwaysTheTiebreak() {
        assertTrue(buildQuery(Filters(sort = Sort.CMC)).sql.trimEnd().contains("c.name_norm ASC"))
    }

    @Test
    fun everyFilterBindsRatherThanInterpolates() {
        val f = Filters(
            q = "' OR 1=1 --",
            artist = "'; DROP TABLE cards; --",
            sets = listOf("'; DELETE FROM cards; --"),
            collnum = "409'",
        )
        val sql = where(f)
        assertFalse(sql.contains("DROP"), sql)
        assertFalse(sql.contains("DELETE"), sql)
        assertFalse(sql.contains("1=1"), sql)
        assertTrue(params(f).size >= 4, params(f).toString())
    }

    @Test
    fun theWholeQueryJoinsUsageAndPricesExactlyOnce() {
        val sql = buildQuery(Filters()).sql
        assertEquals(1, Regex("LEFT JOIN card_usage").findAll(sql).count())
        assertEquals(1, Regex("LEFT JOIN prices").findAll(sql).count())
    }
}
