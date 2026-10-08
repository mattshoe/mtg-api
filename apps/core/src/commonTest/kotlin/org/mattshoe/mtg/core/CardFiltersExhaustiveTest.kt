package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter model, field by field and edge by edge.
 *
 * `CardFiltersTest` pins the colour semantics and the shape of the
 * query; `FilterCoverageTest` checks that every control reaches the
 * SQL at all. Neither asks what a control does when it is handed
 * whitespace, a negative number, a duplicate, a percent sign or a page
 * number big enough to overflow an Int — and those are the inputs that
 * reach a filter panel in the wild, from a hand-edited link if nothing
 * else.
 *
 * The rule every test here is a special case of: whatever was typed is
 * bound, never spliced, and an input the filter cannot make sense of
 * adds no clause rather than a clause that quietly matches nothing.
 */

private fun where(f: Filters) = conditions(f).sql
private fun params(f: Filters) = conditions(f).params
private fun holes(sql: String) = sql.count { it == '?' }

/** The three name columns, joined into the one haystack `search` builds. */
private const val NAMES =
    "COALESCE(c.name_norm, '') || ' ' || COALESCE(lower(c.face1), '') || ' ' || COALESCE(lower(c.face2), '')"

// =====================================================================
//  One field at a time
// =====================================================================

/**
 * Every field on `Filters`, set on its own, against the exact clause it
 * is supposed to produce.
 *
 * `FilterCoverageTest` asks whether a field produces *a* clause. This
 * asks whether it produces the right one — a field wired to a
 * plausible neighbouring column passes the first question and fails
 * this one.
 */
class FilterFieldAloneTest {

    @Test
    fun ownerIsTheIdItsKeyNames() {
        assertEquals("c.owner_id = (SELECT id FROM users WHERE key = ?)", where(Filters(owner = "e7de0cb1")))
        assertEquals(listOf<Any?>("e7de0cb1"), params(Filters(owner = "e7de0cb1")))
    }

    @Test
    fun freeMinComparesTheUsageViewNotACardColumn() {
        val f = Filters(freeMin = "2")
        assertEquals("COALESCE(u.free, 0) >= ?", where(f))
        assertEquals(listOf<Any?>(2.0), params(f))
    }

    @Test
    fun poolFreeAndFreeMinAreDifferentClauses() {
        val both = where(Filters(pool = Pool.FREE, freeMin = "3"))
        assertTrue(both.contains("COALESCE(u.free, 0) > 0"), both)
        assertTrue(both.contains("COALESCE(u.free, 0) >= ?"), both)
    }

    @Test
    fun finishBindsTheFinishRatherThanMatchingIt() {
        val f = Filters(finish = "etched")
        assertEquals("c.finish = ?", where(f))
        assertEquals(listOf<Any?>("etched"), params(f))
    }

    @Test
    fun theNameBoxIsOneHaystackOverThreeColumns() {
        val f = Filters(q = "bolt")
        assertEquals("($NAMES LIKE ? ESCAPE '\\')", where(f))
        assertEquals(listOf<Any?>("%bolt%"), params(f))
    }

    @Test
    fun theNameBoxLowercasesWhatWasTyped() {
        // `name_norm` is stored lowercased, and the other two faces are
        // lowered in the SQL, so the pattern has to come down too.
        assertEquals(listOf<Any?>("%bolt%"), params(Filters(q = "BOLT")))
        assertEquals(listOf<Any?>("%lightning bolt%"), params(Filters(q = "\"Lightning Bolt\"")))
    }

    @Test
    fun eachWordOfTheNameBoxIsItsOwnClause() {
        val f = Filters(q = "sol ring")
        assertEquals(2, Regex("LIKE \\?").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%sol%", "%ring%"), params(f))
    }

    @Test
    fun aQuotedRunInTheNameBoxStaysOneTerm() {
        val f = Filters(q = "\"sol ring\"")
        assertEquals(1, Regex("LIKE \\?").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%sol ring%"), params(f))
    }

    @Test
    fun aNegatedNameWordIsWrappedInNot() {
        val f = Filters(q = "!bolt")
        assertEquals("NOT ($NAMES LIKE ? ESCAPE '\\')", where(f))
        assertEquals(listOf<Any?>("%bolt%"), params(f))
    }

    @Test
    fun onlyALeadingBangNegates() {
        // A card whose name really contains one still has to be findable.
        val f = Filters(q = "!!bolt")
        assertTrue(where(f).startsWith("NOT ("), where(f))
        assertEquals(listOf<Any?>("%!bolt%"), params(f))
    }

    @Test
    fun aBangOnItsOwnIsSomebodyMidKeystroke() {
        assertEquals("", where(Filters(q = "!")))
    }

    @Test
    fun theRulesTextBoxIsAFullTextSubquery() {
        val f = Filters(text = "proliferate")
        assertEquals("c.id IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)", where(f))
        assertEquals(listOf<Any?>("oracle_text : \"proliferate\""), params(f))
    }

    @Test
    fun twoRulesTextWordsAreAndedInsideOneMatch() {
        val f = Filters(text = "draw card")
        assertEquals(1, Regex("card_search MATCH").findAll(where(f)).count())
        assertEquals(listOf<Any?>("oracle_text : \"draw\" AND oracle_text : \"card\""), params(f))
    }

    @Test
    fun anExcludedRulesTextWordIsASecondSubqueryThatSubtracts() {
        val f = Filters(text = "!token")
        assertEquals("c.id NOT IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)", where(f))
        assertEquals(listOf<Any?>("oracle_text : \"token\""), params(f))
    }

    @Test
    fun onlyExclusionsStillWorkBecauseTheyAreTheirOwnClause() {
        // FTS5's own NOT is binary, so an all-negative expression has
        // nothing to subtract from.
        val f = Filters(text = "!token !treasure")
        assertFalse(where(f).contains("c.id IN ("), where(f))
        assertTrue(where(f).contains("c.id NOT IN ("), where(f))
        assertEquals(listOf<Any?>("oracle_text : \"token\" OR oracle_text : \"treasure\""), params(f))
    }

    @Test
    fun positiveAndNegativeRulesTextAreTwoClausesInThatOrder() {
        val f = Filters(text = "draw !token")
        val sql = where(f)
        assertTrue(sql.indexOf("c.id IN (") < sql.indexOf("c.id NOT IN ("), sql)
        assertEquals(
            listOf<Any?>("oracle_text : \"draw\"", "oracle_text : \"token\""),
            params(f),
        )
    }

    @Test
    fun theRulesTextBoxNamesItsColumnSoItDoesNotMatchTheTypeLine() {
        // `card_search` indexes six fields in one row; a bare MATCH is
        // satisfied by any of them, which reads like an OR.
        assertTrue(params(Filters(text = "creature")).single().toString().startsWith("oracle_text :"))
    }

    @Test
    fun flavourSearchesFlavourTextLowercased() {
        val f = Filters(flavor = "Goblin")
        assertEquals("(COALESCE(lower(c.flavor_text), '') LIKE ? ESCAPE '\\')", where(f))
        assertEquals(listOf<Any?>("%goblin%"), params(f))
    }

    @Test
    fun artistSearchesTheArtistColumn() {
        val f = Filters(artist = "Guay")
        assertEquals("(COALESCE(lower(c.artist), '') LIKE ? ESCAPE '\\')", where(f))
        assertEquals(listOf<Any?>("%guay%"), params(f))
    }

    @Test
    fun watermarkSearchesTheWatermarkColumn() {
        assertEquals(
            "(COALESCE(lower(c.watermark), '') LIKE ? ESCAPE '\\')",
            where(Filters(watermark = "prismari")),
        )
    }

    @Test
    fun theTypeLineBoxSearchesTheTypeLine() {
        assertEquals(
            "(COALESCE(lower(c.type_line), '') LIKE ? ESCAPE '\\')",
            where(Filters(typeLine = "elf")),
        )
    }

    @Test
    fun theTypeLineBoxTakesTwoWordsAsBoth() {
        val f = Filters(typeLine = "legendary creature")
        assertEquals(2, Regex("c.type_line").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%legendary%", "%creature%"), params(f))
    }

    @Test
    fun theTypeLineBoxCanExcludeAWord() {
        val f = Filters(typeLine = "creature !land")
        assertEquals(1, Regex("NOT \\(").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%creature%", "%land%"), params(f))
    }

    @Test
    fun aNullColumnIsNotTreatedAsNotContaining() {
        // `NULL NOT LIKE '%x%'` is NULL, so without COALESCE a card
        // with no flavour text was filtered out by `!goblin`.
        assertTrue(where(Filters(flavor = "!goblin")).contains("COALESCE(lower(c.flavor_text), '')"))
    }

    @Test
    fun manaCostIsComparedWithTheSpacesStrippedFromBothSides() {
        val f = Filters(manaCost = "{1} {U}")
        assertEquals("replace(c.mana_cost, ' ', '') LIKE ? ESCAPE '\\'", where(f))
        assertEquals(listOf<Any?>("%{1}{u}%"), params(f))
    }

    @Test
    fun manaCostStripsTabsAndNewlinesToo() {
        assertEquals(listOf<Any?>("%{g}{g}%"), params(Filters(manaCost = "{G}\t{G}\n")))
    }

    @Test
    fun theCollectorNumberIsBoundVerbatim() {
        // Collector numbers are text — '12a', '★117'. No lowercasing,
        // no LIKE.
        val f = Filters(collnum = "12a")
        assertEquals("c.collector_number = ?", where(f))
        assertEquals(listOf<Any?>("12a"), params(f))
    }

    @Test
    fun theCollectorNumberIsTrimmed() {
        assertEquals(listOf<Any?>("117"), params(Filters(collnum = "  117  ")))
    }

    @Test
    fun theCollectorNumberKeepsItsCase() {
        assertEquals(listOf<Any?>("12A"), params(Filters(collnum = "12A")))
    }

    @Test
    fun colourCountIsItsOwnColumnNotTheIdentityString() {
        val f = Filters(ciMin = "2", ciMax = "4")
        assertTrue(where(f).contains("c.color_identity_count >= ?"), where(f))
        assertTrue(where(f).contains("c.color_identity_count <= ?"), where(f))
        assertEquals(listOf<Any?>(2.0, 4.0), params(f))
    }

    @Test
    fun producesIsOneLikePerPip() {
        val f = Filters(produces = listOf("G", "U"))
        assertEquals("c.produced_mana LIKE ?\n  AND c.produced_mana LIKE ?", where(f))
        assertEquals(listOf<Any?>("%G%", "%U%"), params(f))
    }

    @Test
    fun producesAcceptsColourlessBecauseTheSixthPipExists() {
        assertEquals(listOf<Any?>("%C%"), params(Filters(produces = listOf("C"))))
    }

    @Test
    fun manaValueIsARangeOnCmc() {
        val f = Filters(cmcMin = "1", cmcMax = "6")
        assertEquals("c.cmc >= ?\n  AND c.cmc <= ?", where(f))
        assertEquals(listOf<Any?>(1.0, 6.0), params(f))
    }

    @Test
    fun powerUsesThePowerColumn() {
        assertEquals(
            "c.power GLOB '[0-9]*' AND CAST(c.power AS INTEGER) >= ?",
            where(Filters(pow = "4")),
        )
    }

    @Test
    fun toughnessUsesTheToughnessColumn() {
        assertEquals(
            "c.toughness GLOB '[0-9]*' AND CAST(c.toughness AS INTEGER) >= ?",
            where(Filters(tou = "4")),
        )
    }

    @Test
    fun loyaltyUsesTheLoyaltyColumn() {
        assertEquals(
            "c.loyalty GLOB '[0-9]*' AND CAST(c.loyalty AS INTEGER) >= ?",
            where(Filters(loy = "4")),
        )
    }

    @Test
    fun edhrecIsARangeOnTheRankColumn() {
        val f = Filters(edhrecMin = "1", edhrecMax = "500")
        assertTrue(where(f).contains("c.edhrec_rank >= ?"))
        assertTrue(where(f).contains("c.edhrec_rank <= ?"))
    }

    @Test
    fun aTypeIsAnExistsAgainstTheTypesTable() {
        val f = Filters(types = listOf("Creature"))
        assertEquals(
            "EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id " +
                "AND ct.kind = 'type' AND ct.type = ?)",
            where(f),
        )
        assertEquals(listOf<Any?>("Creature"), params(f))
    }

    @Test
    fun aTypeKeepsItsCapitalBecauseTheColumnIsStoredThatWay() {
        assertEquals(listOf<Any?>("Artifact"), params(Filters(types = listOf("Artifact"))))
    }

    @Test
    fun aKeywordIsLowercasedOnBothSides() {
        val f = Filters(keywords = listOf("Flying"))
        assertEquals(
            "EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)",
            where(f),
        )
        assertEquals(listOf<Any?>("flying"), params(f))
    }

    @Test
    fun aTagIsMatchedExactlyOrThroughTheTagsBeneathIt() {
        // What the rows are is test/oracle-tags.test.js, against a real
        // database. This only holds the binding: the tag goes in twice.
        val f = Filters(tags = listOf("mana-rock"))
        assertEquals(
            "EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND " +
                "(ct.tag = ? OR ct.tag IN (SELECT tn.tag FROM tag_names tn WHERE tn.name = ?)))",
            where(f),
        )
        assertEquals(listOf<Any?>("mana-rock", "mana-rock"), params(f))
    }

    @Test
    fun gamesIsOneExistsWithAnInList() {
        val f = Filters(games = listOf("paper"))
        assertEquals(
            "EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game IN (?))",
            where(f),
        )
        assertEquals(listOf<Any?>("paper"), params(f))
    }

    @Test
    fun aYearMinimumBecomesTheFirstOfJanuary() {
        val f = Filters(yearMin = "1993")
        assertEquals("c.released_at >= ?", where(f))
        assertEquals(listOf<Any?>("1993-01-01"), params(f))
    }

    @Test
    fun aYearMaximumBecomesTheThirtyFirstOfDecember() {
        val f = Filters(yearMax = "2026")
        assertEquals("c.released_at <= ?", where(f))
        assertEquals(listOf<Any?>("2026-12-31"), params(f))
    }

    @Test
    fun priceUsesTheFinishAwareExpressionOnTheLowEnd() {
        val f = Filters(priceMin = "0.5")
        assertEquals("($PRICE_EXPR) >= ?", where(f))
        assertEquals(listOf<Any?>(0.5), params(f))
    }

    @Test
    fun priceUsesTheFinishAwareExpressionOnTheHighEnd() {
        assertEquals("($PRICE_EXPR) <= ?", where(Filters(priceMax = "20")))
    }

    @Test
    fun aFormatBindsTheFormatAndTheStatusInThatOrder() {
        assertEquals(listOf<Any?>("modern", "legal"), params(Filters(format = "modern")))
    }

    @Test
    fun rulingsYesIsAnExistsAgainstTheOracleId() {
        assertEquals(
            "EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)",
            where(Filters(hasRulings = Tri.YES)),
        )
    }

    @Test
    fun theQueryBoxIsFoldedInAsOneMoreClause() {
        val f = Filters(adv = "t:creature")
        assertEquals("lower(c.type_line) LIKE ? ESCAPE '\\'", where(f))
        assertEquals(listOf<Any?>("%creature%"), params(f))
    }

    @Test
    fun theQueryBoxComesLastSoTheStructuredClausesReadFirst() {
        val sql = where(Filters(owner = "e7de0cb1", adv = "mv<=2"))
        assertTrue(sql.indexOf("c.owner") < sql.indexOf("c.cmc"), sql)
    }

    @Test
    fun theQueryBoxSyntaxErrorIsNotSwallowedHere() {
        var thrown = false
        try {
            conditions(Filters(adv = "wibble:3"))
        } catch (e: QuerySyntax) {
            thrown = true
            assertTrue(e.message.orEmpty().contains("wibble"), e.message.orEmpty())
        }
        assertTrue(thrown, "an unknown key in the query box has to surface")
    }
}

// =====================================================================
//  Nothing in, nothing out
// =====================================================================

/**
 * Every field handed nothing, blank, or whitespace.
 *
 * A filter that turns an empty box into a clause is the worst kind of
 * bug: the grid is wrong and the panel looks untouched. `c.finish = ''`
 * and `c.released_at >= '-01-01'` both match nothing at all.
 */
class FilterEmptyInputTest {

    private fun addsNothing(name: String, f: Filters) {
        assertEquals("", where(f), "$name made a clause out of nothing")
        assertTrue(params(f).isEmpty(), "$name bound a value for nothing")
    }

    @Test
    fun anEmptyFilterObjectIsNoWhereClauseAtAll() = addsNothing("defaults", Filters())

    /**
     * An empty owner is "nobody said", and it reads nothing.
     *
     * It used to be the one blank that widened a search instead of
     * leaving it alone: no clause, so every collection at once. That
     * is how somebody else's cards got onto the screen in the gap
     * before `/auth/me` answered — Matt: "why are kaylas decks
     * showing for me in the web app?!?!?!" The pooled read has to ask
     * for `both` by name now.
     */
    @Test
    fun anEmptyOwnerReadsNothingRatherThanEverything() {
        assertEquals("1=0", where(Filters(owner = "")), "an empty owner widened the search")
        assertTrue(params(Filters(owner = "")).isEmpty())
    }

    @Test
    fun andSoDoesAWhitespaceOne() {
        assertEquals("1=0", where(Filters(owner = "   ")))
        assertTrue(params(Filters(owner = "   ")).isEmpty())
    }

    @Test
    fun bothIsThePooledReadAndStillAddsNothing() =
        addsNothing("owner", Filters(owner = "both"))

    @Test
    fun anEmptyFinishMeansAnyFinish() = addsNothing("finish", Filters(finish = ""))

    @Test
    fun aWhitespaceFinishMeansAnyFinish() = addsNothing("finish", Filters(finish = " \t "))

    @Test
    fun anEmptyNameBoxSearchesNothing() = addsNothing("q", Filters(q = ""))

    @Test
    fun aWhitespaceNameBoxSearchesNothing() = addsNothing("q", Filters(q = "    "))

    @Test
    fun aNameBoxHoldingOnlyEmptyQuotesSearchesNothing() = addsNothing("q", Filters(q = "\"\""))

    @Test
    fun aNameBoxHoldingOnlyQuotedSpacesSearchesNothing() = addsNothing("q", Filters(q = "\"   \""))

    @Test
    fun anEmptyRulesTextBoxSearchesNothing() = addsNothing("text", Filters(text = ""))

    @Test
    fun aWhitespaceRulesTextBoxSearchesNothing() = addsNothing("text", Filters(text = "\n \t"))

    @Test
    fun anEmptyFlavourBoxSearchesNothing() = addsNothing("flavor", Filters(flavor = ""))

    @Test
    fun aWhitespaceFlavourBoxSearchesNothing() = addsNothing("flavor", Filters(flavor = "  "))

    @Test
    fun anEmptyArtistBoxSearchesNothing() = addsNothing("artist", Filters(artist = ""))

    @Test
    fun aWhitespaceArtistBoxSearchesNothing() = addsNothing("artist", Filters(artist = " "))

    @Test
    fun anEmptyWatermarkBoxSearchesNothing() = addsNothing("watermark", Filters(watermark = ""))

    @Test
    fun aWhitespaceWatermarkBoxSearchesNothing() = addsNothing("watermark", Filters(watermark = "   "))

    @Test
    fun anEmptyTypeLineBoxSearchesNothing() = addsNothing("typeLine", Filters(typeLine = ""))

    @Test
    fun aWhitespaceTypeLineBoxSearchesNothing() = addsNothing("typeLine", Filters(typeLine = " \t "))

    @Test
    fun anEmptyManaCostSearchesNothing() = addsNothing("manaCost", Filters(manaCost = ""))

    @Test
    fun aWhitespaceManaCostSearchesNothingRatherThanMatchingEveryCost() =
        addsNothing("manaCost", Filters(manaCost = "   "))

    @Test
    fun anEmptyCollectorNumberSearchesNothing() = addsNothing("collnum", Filters(collnum = ""))

    @Test
    fun aWhitespaceCollectorNumberSearchesNothing() = addsNothing("collnum", Filters(collnum = "  "))

    @Test
    fun anEmptyDeckMeansEveryDeck() = addsNothing("deck", Filters(deck = ""))

    @Test
    fun aWhitespaceDeckMeansEveryDeck() = addsNothing("deck", Filters(deck = "   "))

    @Test
    fun anEmptyQueryBoxAddsNothing() = addsNothing("adv", Filters(adv = ""))

    @Test
    fun aWhitespaceQueryBoxAddsNothing() = addsNothing("adv", Filters(adv = "   "))

    @Test
    fun anEmptyFormatAddsNothingEvenWithAStatus() =
        addsNothing("format", Filters(format = "", legality = "banned"))

    @Test
    fun aWhitespaceFormatAddsNothing() = addsNothing("format", Filters(format = "  "))

    @Test
    fun everyEmptyNumberBoxAddsNothing() = addsNothing(
        "numbers",
        Filters(
            qtyMin = "", qtyMax = "", freeMin = "", ciMin = "", ciMax = "",
            cmcMin = "", cmcMax = "", pow = "", tou = "", loy = "",
            yearMin = "", yearMax = "", priceMin = "", priceMax = "",
            edhrecMin = "", edhrecMax = "",
        ),
    )

    @Test
    fun everyWhitespaceNumberBoxAddsNothing() = addsNothing(
        "numbers",
        Filters(
            qtyMin = " ", qtyMax = "\t", freeMin = "  ", ciMin = " ", ciMax = " ",
            cmcMin = " ", cmcMax = " ", pow = " ", tou = " ", loy = " ",
            yearMin = " ", yearMax = " ", priceMin = " ", priceMax = " ",
            edhrecMin = " ", edhrecMax = " ",
        ),
    )

    @Test
    fun everyEmptyListAddsNothing() = addsNothing(
        "lists",
        Filters(
            colors = emptyList(), produces = emptyList(), types = emptyList(),
            rarities = emptyList(), sets = emptyList(), setTypes = emptyList(),
            layouts = emptyList(), frames = emptyList(), borders = emptyList(),
            games = emptyList(), keywords = emptyList(), tags = emptyList(),
        ),
    )

    @Test
    fun everyUnsetFlagAddsNothing() =
        addsNothing("flags", Filters(flags = Flag.entries.associateWith { Tri.ANY }))

    @Test
    fun anEmptyWhereClauseMeansNoWhereKeywordAtAll() {
        // The owner's key comes back through a subselect of its own;
        // that WHERE is inside the column list, not over the cards.
        val outer = buildQuery(Filters()).sql.replace(Owners.keyOf("c.owner_id"), "")
        assertFalse(outer.contains("WHERE"), buildQuery(Filters()).sql)
    }

    @Test
    fun anEmptyHavingMeansNoHavingKeywordAtAll() {
        assertFalse(buildQuery(Filters()).sql.contains("HAVING"))
        assertFalse(buildQuery(Filters(), countOnly = true).sql.contains("HAVING"))
    }

    @Test
    fun anUntouchedPanelBindsNothingAtAll() {
        assertTrue(buildQuery(Filters()).params.isEmpty())
        assertTrue(buildQuery(Filters(), countOnly = true).params.isEmpty())
        assertEquals(0, holes(buildQuery(Filters()).sql))
    }

    @Test
    fun aWhitespaceOnlyQtyIsNotAHavingEither() {
        assertEquals("", having(Filters(qtyMin = "  ", qtyMax = "\t")).sql)
    }
}

// =====================================================================
//  Numbers
// =====================================================================

/**
 * Every numeric box, with the values a text input can actually hold.
 *
 * `numeric` binds a Double or adds nothing. Both halves matter: a
 * string bound against an expression never compares equal (SQLite
 * applies column affinity to a column, not to a CASE), and a clause
 * built from an unparseable box empties the grid without saying why.
 */
class FilterNumberEdgeTest {

    private fun one(f: Filters): Any? = params(f).single()

    // ------------------------------------------------------------ cmc

    @Test
    fun aNegativeManaValueIsStillAComparison() {
        assertEquals(-1.0, one(Filters(cmcMin = "-1")))
    }

    @Test
    fun zeroIsAManaValueLikeAnyOther() {
        // The commonest filter there is — Sol Ring's mana value is 1,
        // a land's is 0, and `if (value)` would have dropped it.
        assertEquals(0.0, one(Filters(cmcMin = "0")))
        assertTrue(where(Filters(cmcMin = "0")).isNotEmpty())
    }

    @Test
    fun aDecimalManaValueSurvives() {
        // Un-castable mana values exist: Little Girl is 0.5.
        assertEquals(0.5, one(Filters(cmcMin = "0.5")))
    }

    @Test
    fun aHugeManaValueIsBoundAsItself() {
        assertEquals(1.0E12, one(Filters(cmcMax = "1000000000000")))
    }

    @Test
    fun aNonNumericManaValueAddsNothing() {
        assertEquals("", where(Filters(cmcMin = "two")))
    }

    @Test
    fun aManaValueWithSurroundingSpacesIsTrimmed() {
        assertEquals(3.0, one(Filters(cmcMin = "  3  ")))
    }

    @Test
    fun aManaValueWithAnInternalSpaceIsNotANumber() {
        assertEquals("", where(Filters(cmcMin = "1 2")))
    }

    @Test
    fun exponentNotationIsANumber() {
        assertEquals(1000.0, one(Filters(cmcMax = "1e3")))
    }

    @Test
    fun aPlusSignedNumberIsANumber() {
        assertEquals(2.0, one(Filters(cmcMin = "+2")))
    }

    @Test
    fun anOverflowingNumberIsNotAComparisonAtAll() {
        // "1e400" parses to Infinity, which binds as NULL — and no
        // comparison against NULL is ever true.
        assertEquals("", where(Filters(cmcMin = "1e400")))
    }

    @Test
    fun notANumberIsNotANumber() {
        assertEquals("", where(Filters(cmcMin = "NaN")))
        assertEquals("", where(Filters(cmcMax = "Infinity")))
        assertEquals("", where(Filters(priceMin = "NaN")))
    }

    @Test
    fun aCommaGroupedNumberIsRejectedRatherThanTruncated() {
        assertEquals("", where(Filters(priceMax = "1,000")))
    }

    @Test
    fun aCurrencySymbolIsRejected() {
        assertEquals("", where(Filters(priceMin = "$5")))
    }

    @Test
    fun aTrailingPercentIsRejected() {
        assertEquals("", where(Filters(priceMax = "50%")))
    }

    // -------------------------------------------------- the other boxes

    @Test
    fun negativesReachEveryNumericBox() {
        val cases = listOf(
            "ciMin" to Filters(ciMin = "-1"),
            "ciMax" to Filters(ciMax = "-1"),
            "cmcMin" to Filters(cmcMin = "-1"),
            "edhrecMin" to Filters(edhrecMin = "-1"),
            "freeMin" to Filters(freeMin = "-1"),
            "priceMin" to Filters(priceMin = "-1"),
            "pow" to Filters(pow = "-1"),
            "tou" to Filters(tou = "-1"),
            "loy" to Filters(loy = "-1"),
        )
        cases.forEach { (name, f) ->
            assertEquals(listOf<Any?>(-1.0), params(f), "$name dropped a negative")
        }
    }

    @Test
    fun zeroReachesEveryNumericBox() {
        val cases = listOf(
            Filters(ciMin = "0"), Filters(ciMax = "0"), Filters(cmcMin = "0"),
            Filters(cmcMax = "0"), Filters(edhrecMin = "0"), Filters(edhrecMax = "0"),
            Filters(freeMin = "0"), Filters(priceMin = "0"), Filters(priceMax = "0"),
            Filters(pow = "0"), Filters(tou = "0"), Filters(loy = "0"),
        )
        cases.forEach { f -> assertEquals(listOf<Any?>(0.0), params(f), "a zero was dropped: $f") }
    }

    @Test
    fun decimalsReachEveryNumericBox() {
        val cases = listOf(
            Filters(ciMin = "1.5"), Filters(cmcMax = "1.5"), Filters(edhrecMax = "1.5"),
            Filters(freeMin = "1.5"), Filters(priceMax = "1.5"),
            Filters(pow = "1.5"), Filters(tou = "1.5"), Filters(loy = "1.5"),
        )
        cases.forEach { f -> assertEquals(listOf<Any?>(1.5), params(f), "a decimal was dropped: $f") }
    }

    @Test
    fun nonsenseIsIgnoredByEveryNumericBox() {
        val f = Filters(
            ciMin = "a", ciMax = "b", cmcMin = "c", cmcMax = "d",
            edhrecMin = "e", edhrecMax = "f", freeMin = "g",
            priceMin = "h", priceMax = "i", pow = "j", tou = "k", loy = "l",
            qtyMin = "m", qtyMax = "n", yearMin = "o", yearMax = "p",
        )
        assertEquals("", where(f))
        assertEquals("", having(f).sql)
    }

    @Test
    fun spacesAreTrimmedByEveryNumericBox() {
        val f = Filters(
            ciMin = " 1 ", cmcMax = " 2 ", edhrecMax = " 3 ", freeMin = " 4 ",
            priceMax = " 5 ", pow = " 6 ", tou = " 7 ", loy = " 8 ",
        )
        // In clause order: colour count, mana value, the three stats,
        // the rank, the price, then the usage view.
        assertEquals(listOf<Any?>(1.0, 2.0, 6.0, 7.0, 8.0, 3.0, 5.0, 4.0), params(f))
    }

    @Test
    fun aHugeNumberReachesEveryNumericBoxWithoutBecomingText() {
        val big = "99999999999999"
        listOf(
            Filters(cmcMax = big), Filters(edhrecMax = big), Filters(priceMax = big),
            Filters(freeMin = big), Filters(pow = big), Filters(ciMax = big),
        ).forEach { f ->
            assertTrue(params(f).single() is Double, "$f bound ${params(f).single()}")
        }
    }

    @Test
    fun everyNumberBoundIsADoubleAndNeverAString() {
        val f = Filters(
            ciMin = "1", ciMax = "3", cmcMin = "1", cmcMax = "6",
            edhrecMin = "1", edhrecMax = "9", freeMin = "1",
            priceMin = "1", priceMax = "9", pow = "1", tou = "2", loy = "3",
        )
        params(f).forEach { assertTrue(it is Double, "bound a ${it?.let { v -> v::class.simpleName }}") }
        having(Filters(qtyMin = "1", qtyMax = "2")).params.forEach { assertTrue(it is Double) }
    }

    // ---------------------------------------------------------- the year

    @Test
    fun aYearIsTrimmedBeforeItBecomesADate() {
        assertEquals(listOf<Any?>("2020-01-01"), params(Filters(yearMin = " 2020 ")))
    }

    @Test
    fun aNonNumericYearAddsNothingRatherThanADateNothingMatches() {
        // 'soon-01-01' sorts after every real release date, so a typo
        // used to empty the grid in silence.
        assertEquals("", where(Filters(yearMin = "soon")))
        assertEquals("", where(Filters(yearMax = "soon")))
    }

    @Test
    fun aDecimalYearAddsNothing() {
        assertEquals("", where(Filters(yearMin = "2020.5")))
    }

    @Test
    fun aYearIsStillBoundAsTextBecauseTheColumnIsADateString() {
        assertTrue(params(Filters(yearMin = "2020")).single() is String)
    }

    @Test
    fun aNegativeYearIsStillAYear() {
        // Not reachable from the panel, but it must not splice.
        assertEquals(listOf<Any?>("-5-01-01"), params(Filters(yearMin = "-5")))
    }

    // --------------------------------------------------- the operators

    @Test
    fun everyRecognisedOperatorReachesThePowerClause() {
        listOf(">=", "<=", "=", ">", "<", "!=").forEach { op ->
            assertTrue(
                where(Filters(pow = "3", powOp = op)).contains("CAST(c.power AS INTEGER) $op ?"),
                "the power operator $op did not survive",
            )
        }
    }

    @Test
    fun everyRecognisedOperatorReachesTheToughnessClause() {
        listOf(">=", "<=", "=", ">", "<", "!=").forEach { op ->
            assertTrue(where(Filters(tou = "3", touOp = op)).contains("CAST(c.toughness AS INTEGER) $op ?"))
        }
    }

    @Test
    fun everyRecognisedOperatorReachesTheLoyaltyClause() {
        listOf(">=", "<=", "=", ">", "<", "!=").forEach { op ->
            assertTrue(where(Filters(loy = "3", loyOp = op)).contains("CAST(c.loyalty AS INTEGER) $op ?"))
        }
    }

    @Test
    fun anEmptyOperatorFallsBackToAtLeast() {
        assertTrue(where(Filters(pow = "3", powOp = "")).contains("CAST(c.power AS INTEGER) >= ?"))
    }

    @Test
    fun anOperatorThatIsAlmostRightFallsBackRatherThanBeingSpliced() {
        // `=>` and `==` are the two people type.
        assertTrue(where(Filters(tou = "3", touOp = "=>")).contains("INTEGER) >= ?"))
        assertTrue(where(Filters(tou = "3", touOp = "==")).contains("INTEGER) >= ?"))
    }

    @Test
    fun anOperatorCarryingSqlNeverReachesTheStatement() {
        val sql = where(Filters(loy = "3", loyOp = "> 0 OR 1=1 --"))
        assertFalse(sql.contains("1=1"), sql)
        assertFalse(sql.contains("--"), sql)
        assertTrue(sql.contains("CAST(c.loyalty AS INTEGER) >= ?"), sql)
    }

    @Test
    fun anOperatorWithSpaceAroundItIsNotRecognised() {
        // The set is exact strings, deliberately — trimming here would
        // mean trusting the shape of what arrived.
        assertTrue(where(Filters(pow = "3", powOp = " >= ")).contains("INTEGER) >= ?"))
    }

    @Test
    fun anOperatorWithoutAValueAddsNothing() {
        assertEquals("", where(Filters(powOp = "<=", pow = "")))
        assertEquals("", where(Filters(touOp = "!=", tou = "  ")))
    }

    @Test
    fun aPowerThatIsNotANumberAddsNothing() {
        // A real power: Tarmogoyf is '*', Mistcutter is '1+*'.
        assertEquals("", where(Filters(pow = "*")))
        assertEquals("", where(Filters(pow = "1+*")))
    }
}

// =====================================================================
//  Lists
// =====================================================================

/**
 * Every list-shaped filter, with the lists a panel can build.
 *
 * `inList` interpolates one `?` per element and binds each, so the
 * things to prove are that the hole count tracks the list, that a
 * value is never spliced, and that the two different meanings of a
 * list — all of these (types) and any of these (rarities) — stay
 * different.
 */
class FilterListEdgeTest {

    @Test
    fun oneRarityIsOneHole() {
        assertEquals("c.rarity IN (?)", where(Filters(rarities = listOf("rare"))))
    }

    @Test
    fun fiveRaritiesAreFiveHoles() {
        val f = Filters(rarities = listOf("common", "uncommon", "rare", "mythic", "special"))
        assertEquals("c.rarity IN (?,?,?,?,?)", where(f))
        assertEquals(5, params(f).size)
    }

    @Test
    fun aDuplicateRarityIsBoundTwiceRatherThanDroppingAHole() {
        // Harmless in SQL, but holes and values have to agree or the
        // server answers "wrong number of parameter bindings".
        val f = Filters(rarities = listOf("rare", "rare"))
        assertEquals("c.rarity IN (?,?)", where(f))
        assertEquals(listOf<Any?>("rare", "rare"), params(f))
        assertEquals(holes(where(f)), params(f).size)
    }

    @Test
    fun rarityCaseIsPassedThroughUntouched() {
        // The column holds lowercase; the panel offers lowercase. If
        // this ever needs folding it is a deliberate change, not a
        // silent one.
        assertEquals(listOf<Any?>("Rare"), params(Filters(rarities = listOf("Rare"))))
    }

    @Test
    fun aRarityWithAQuoteIsBoundNotSpliced() {
        val f = Filters(rarities = listOf("rare' OR '1'='1"))
        assertEquals("c.rarity IN (?)", where(f))
        assertEquals(listOf<Any?>("rare' OR '1'='1"), params(f))
    }

    @Test
    fun aRarityWithAPercentIsHarmlessBecauseItIsAnEqualityList() {
        val f = Filters(rarities = listOf("%"))
        assertEquals(listOf<Any?>("%"), params(f))
        assertFalse(where(f).contains("LIKE"), where(f))
    }

    @Test
    fun setCodesAreFoldedToLowercaseOnBothSides() {
        val f = Filters(sets = listOf("MH3", "mh3", "Mh3"))
        assertEquals("lower(c.setcode) IN (?,?,?)", where(f))
        assertEquals(listOf<Any?>("mh3", "mh3", "mh3"), params(f))
    }

    @Test
    fun aSetCodeWithAPercentIsStillAnEquality() {
        assertEquals(listOf<Any?>("%"), params(Filters(sets = listOf("%"))))
    }

    @Test
    fun aSetCodeWithAQuoteIsBound() {
        val f = Filters(sets = listOf("'; DROP TABLE cards --"))
        assertFalse(where(f).contains("DROP"), where(f))
        assertEquals(1, params(f).size)
    }

    @Test
    fun setTypesAreAnInList() {
        val f = Filters(setTypes = listOf("core", "expansion", "commander"))
        assertEquals("c.set_type IN (?,?,?)", where(f))
        assertEquals(listOf<Any?>("core", "expansion", "commander"), params(f))
    }

    @Test
    fun layoutsAreAnInList() {
        assertEquals("c.layout IN (?,?)", where(Filters(layouts = listOf("saga", "split"))))
    }

    @Test
    fun framesAreAnInList() {
        assertEquals("c.frame IN (?,?)", where(Filters(frames = listOf("1993", "2015"))))
    }

    @Test
    fun bordersAreAnInList() {
        assertEquals("c.border_color IN (?,?)", where(Filters(borders = listOf("black", "borderless"))))
    }

    @Test
    fun aBlankElementStillGetsAHoleOfItsOwn() {
        // The URL codec drops blanks, so this is only reachable in
        // code — but holes and values must still agree.
        val f = Filters(layouts = listOf(""))
        assertEquals("c.layout IN (?)", where(f))
        assertEquals(listOf<Any?>(""), params(f))
    }

    @Test
    fun severalTypesAreAndedAsSeparateExists() {
        val f = Filters(types = listOf("Legendary", "Artifact", "Creature"))
        assertEquals(3, Regex("card_types").findAll(where(f)).count())
        assertFalse(where(f).contains(" OR "), "ticking three types must mean all three")
    }

    @Test
    fun aDuplicateTypeIsTwoClausesRatherThanOne() {
        val f = Filters(types = listOf("Creature", "Creature"))
        assertEquals(2, Regex("card_types").findAll(where(f)).count())
        assertEquals(2, params(f).size)
    }

    @Test
    fun aTypeWithAQuoteIsBound() {
        val f = Filters(types = listOf("Creature'"))
        assertEquals(listOf<Any?>("Creature'"), params(f))
        assertEquals(1, holes(where(f)))
    }

    @Test
    fun aTypeWithAPercentIsAnEqualityNotAPattern() {
        assertFalse(where(Filters(types = listOf("%"))).contains("LIKE"))
    }

    @Test
    fun keywordsAreAndedOneExistsEach() {
        val f = Filters(keywords = listOf("Flying", "Trample", "Haste"))
        assertEquals(3, Regex("card_keywords").findAll(where(f)).count())
        assertEquals(listOf<Any?>("flying", "trample", "haste"), params(f))
    }

    @Test
    fun aMixedCaseKeywordListIsFoldedEntirely() {
        assertEquals(
            listOf<Any?>("first strike", "ward"),
            params(Filters(keywords = listOf("First Strike", "WARD"))),
        )
    }

    @Test
    fun aKeywordWithAPercentIsAnEquality() {
        assertEquals(listOf<Any?>("%"), params(Filters(keywords = listOf("%"))))
    }

    @Test
    fun tagsAreAndedOneExistsEach() {
        val f = Filters(tags = listOf("ramp", "mana-rock"))
        assertEquals(2, Regex("card_tags").findAll(where(f)).count())
        assertEquals(listOf<Any?>("ramp", "ramp", "mana-rock", "mana-rock"), params(f))
    }

    @Test
    fun aTagIsFoldedAndTrimmedTheWayItIsStored() {
        assertEquals(listOf<Any?>("ramp"), params(Filters(tags = listOf(" Ramp"))))
    }

    @Test
    fun gamesGrowOneHoleAtATimeInsideTheOneExists() {
        val f = Filters(games = listOf("paper", "arena", "mtgo"))
        assertEquals(1, Regex("card_games").findAll(where(f)).count())
        assertTrue(where(f).contains("g.game IN (?,?,?)"), where(f))
        assertEquals(listOf<Any?>("paper", "arena", "mtgo"), params(f))
    }

    @Test
    fun aDuplicateGameStillBindsTwice() {
        val f = Filters(games = listOf("paper", "paper"))
        assertTrue(where(f).contains("IN (?,?)"))
        assertEquals(2, params(f).size)
    }

    @Test
    fun aGameWithAQuoteIsBound() {
        val f = Filters(games = listOf("paper'"))
        assertEquals(listOf<Any?>("paper'"), params(f))
    }

    @Test
    fun manyProducesPipsAreManyLikes() {
        val f = Filters(produces = listOf("W", "U", "B", "R", "G", "C"))
        assertEquals(6, Regex("produced_mana").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%W%", "%U%", "%B%", "%R%", "%G%", "%C%"), params(f))
    }

    @Test
    fun aDuplicatePipIsTwoClausesButStillBalanced() {
        val f = Filters(produces = listOf("G", "G"))
        assertEquals(2, params(f).size)
        assertEquals(holes(where(f)), params(f).size)
    }

    @Test
    fun aLowercasePipDoesNotMatchTheColumnSoItIsDropped() {
        // The column holds 'WUBRG'. Accepting 'g' here would be a
        // clause that silently matches nothing.
        assertEquals("", where(Filters(produces = listOf("g"))))
    }

    @Test
    fun aListOfOnlyUnknownValuesAddsNoColourClause() {
        assertEquals("", where(Filters(colors = listOf("X", "Y"))))
    }

    @Test
    fun anUnknownColourIsIgnoredWithoutTakingTheRestWithIt() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("W", "X", "U"))
        assertEquals(listOf<Any?>("UW"), params(f))
    }

    @Test
    fun aListWithEveryKindOfAwkwardValueStillBalancesItsHoles() {
        val f = Filters(
            rarities = listOf("rare", "rare", "'", "%", "_", "\\", ""),
            sets = listOf("MH3", "mh3", "a'b", "100%"),
            setTypes = listOf("core", "core"),
            games = listOf("paper", "a'b"),
            types = listOf("Creature", "Creature'"),
            keywords = listOf("Flying", "A'B"),
            tags = listOf("ramp", "a'b"),
        )
        assertEquals(holes(where(f)), params(f).size)
        assertFalse(where(f).contains("'; "), where(f))
    }
}

// =====================================================================
//  LIKE escaping
// =====================================================================

/**
 * `%`, `_` and `\` everywhere a LIKE appears.
 *
 * This is the class of bug that is invisible until it is reported as
 * "search is broken": typing `50%` into a box matched the whole
 * collection, because `%` is LIKE's own wildcard. Every pattern this
 * file builds escapes the three characters and every LIKE that uses
 * one declares the escape — one without the other is as wrong as
 * neither.
 */
class FilterLikeEscapeTest {

    /** Every box whose clause is a LIKE over what was typed. */
    private val likeBoxes = listOf<Pair<String, (String) -> Filters>>(
        "q" to { v -> Filters(q = v) },
        "flavor" to { v -> Filters(flavor = v) },
        "artist" to { v -> Filters(artist = v) },
        "watermark" to { v -> Filters(watermark = v) },
        "typeLine" to { v -> Filters(typeLine = v) },
        "manaCost" to { v -> Filters(manaCost = v) },
    )

    @Test
    fun everyLikeInEveryBoxDeclaresItsEscapeCharacter() {
        likeBoxes.forEach { (name, make) ->
            val sql = where(make("bolt"))
            val likes = Regex("LIKE \\?").findAll(sql).count()
            val escapes = Regex("LIKE \\? ESCAPE '\\\\'").findAll(sql).count()
            assertTrue(likes > 0, "$name produced no LIKE at all")
            assertEquals(escapes, likes, "$name has a LIKE with no ESCAPE: $sql")
        }
    }

    @Test
    fun aPercentIsEscapedInEveryBox() {
        likeBoxes.forEach { (name, make) ->
            assertEquals(listOf<Any?>("%\\%%"), params(make("%")), "$name let a percent through")
        }
    }

    @Test
    fun anUnderscoreIsEscapedInEveryBox() {
        likeBoxes.forEach { (name, make) ->
            assertEquals(listOf<Any?>("%\\_%"), params(make("_")), "$name let an underscore through")
        }
    }

    @Test
    fun aBackslashIsEscapedInEveryBox() {
        likeBoxes.forEach { (name, make) ->
            assertEquals(listOf<Any?>("%\\\\%"), params(make("\\")), "$name let a backslash through")
        }
    }

    @Test
    fun theBackslashIsEscapedBeforeTheWildcardsSoItIsNotDoubleEscaped() {
        // Escape `%` first and `%` becomes `\%`, whose backslash the
        // backslash pass then doubles into `\\%` — a literal
        // backslash followed by a wildcard.
        assertEquals(listOf<Any?>("%\\%%"), params(Filters(q = "%")))
        assertEquals(listOf<Any?>("%\\\\\\%%"), params(Filters(q = "\\%")))
    }

    @Test
    fun aPriceTypedWithAPercentDoesNotMatchTheWholeCollection() {
        // The one that happened. `50%` in the name box is a name
        // search for the characters "50%", not every card there is.
        assertEquals(listOf<Any?>("%50\\%%"), params(Filters(q = "50%")))
    }

    @Test
    fun aNameWithAnUnderscoreIsLiteral() {
        assertEquals(listOf<Any?>("%chandra\\_%"), params(Filters(q = "Chandra_")))
    }

    @Test
    fun aManaCostWithAnUnderscoreIsLiteralAndTheEscapeIsDeclared() {
        val f = Filters(manaCost = "{_}")
        assertEquals("replace(c.mana_cost, ' ', '') LIKE ? ESCAPE '\\'", where(f))
        assertEquals(listOf<Any?>("%{\\_}%"), params(f))
    }

    @Test
    fun aManaCostWithAPercentIsLiteral() {
        assertEquals(listOf<Any?>("%{50\\%}%"), params(Filters(manaCost = "{50%}")))
    }

    @Test
    fun aNegatedTermIsEscapedTheSameWay() {
        assertEquals(listOf<Any?>("%\\%%"), params(Filters(q = "!%")))
        assertTrue(where(Filters(q = "!%")).contains("ESCAPE '\\'"))
    }

    @Test
    fun aQuotedPhraseIsEscapedTheSameWay() {
        assertEquals(listOf<Any?>("%50\\% off%"), params(Filters(q = "\"50% off\"")))
    }

    @Test
    fun aWildcardInOneWordDoesNotLeakIntoTheNext() {
        assertEquals(listOf<Any?>("%a\\%%", "%b%"), params(Filters(q = "a% b")))
    }

    @Test
    fun theEscapeHelperIsTheOnePlaceThisIsDecided() {
        val c = Clauses()
        assertEquals("%bolt%", c.like("Bolt"))
        assertEquals("%\\%%", c.like("%"))
        assertEquals("%\\_%", c.like("_"))
        assertEquals("%\\\\%", c.like("\\"))
        assertEquals("%100\\%\\_x%", c.like("100%_x"))
    }

    @Test
    fun theEscapeHelperLowercasesSoBothSidesOfTheCompareAgree() {
        assertEquals("%abc%", Clauses().like("AbC"))
    }

    @Test
    fun addLikeAlwaysPairsThePatternWithTheEscapeClause() {
        val c = Clauses()
        c.addLike("c.foo", "50%")
        assertEquals(listOf("c.foo LIKE ? ESCAPE '\\'"), c.where)
        assertEquals(listOf<Any?>("%50\\%%"), c.params)
    }

    @Test
    fun theColourLikesNeedNoEscapeBecauseNothingTypedReachesThem() {
        // The pattern is one of five hard-coded letters, so there is
        // nothing to escape — but only because the input is filtered
        // first, which is what the two tests below pin.
        val f = Filters(colorMode = ColorMode.ATLEAST, colors = listOf("G"))
        assertEquals(listOf<Any?>("%G%"), params(f))
    }

    @Test
    fun aColourPercentCannotReachTheColourLike() {
        assertEquals("", where(Filters(colorMode = ColorMode.ATLEAST, colors = listOf("%"))))
        assertEquals("", where(Filters(colorMode = ColorMode.ANYOF, colors = listOf("%"))))
        assertEquals("", where(Filters(colorMode = ColorMode.EXACTLY, colors = listOf("%"))))
    }

    @Test
    fun aProducesPercentCannotReachTheProducesLike() {
        // `produces=%` in a hand-edited link was `LIKE '%%%'` — every
        // card that taps for anything at all.
        assertEquals("", where(Filters(produces = listOf("%"))))
        assertEquals("", where(Filters(produces = listOf("_"))))
        assertEquals("", where(Filters(produces = listOf("\\"))))
        assertEquals("", where(Filters(produces = listOf("WUBRG"))))
    }

    @Test
    fun aProducesPercentDoesNotTakeTheRealPipsDownWithIt() {
        assertEquals(listOf<Any?>("%G%"), params(Filters(produces = listOf("%", "G", "?"))))
    }
}

// =====================================================================
//  Binding, never splicing
// =====================================================================

/**
 * For every filter: the value is in `params` and not in the SQL.
 *
 * Asserted per field rather than once over a composite, so a failure
 * names the field that spliced. The marker is a string no column name
 * or SQL keyword contains.
 */
class FilterBindingTest {

    private val marker = "zqxj"

    /** Every field that takes free text, and what it does with it. */
    private val textFields = listOf<Pair<String, (String) -> Filters>>(
        "owner" to { v -> Filters(owner = v) },
        "deck" to { v -> Filters(deck = v) },
        "finish" to { v -> Filters(finish = v) },
        "q" to { v -> Filters(q = v) },
        "text" to { v -> Filters(text = v) },
        "flavor" to { v -> Filters(flavor = v) },
        "artist" to { v -> Filters(artist = v) },
        "watermark" to { v -> Filters(watermark = v) },
        "typeLine" to { v -> Filters(typeLine = v) },
        "manaCost" to { v -> Filters(manaCost = v) },
        "collnum" to { v -> Filters(collnum = v) },
        "format" to { v -> Filters(format = v) },
        "types" to { v -> Filters(types = listOf(v)) },
        "keywords" to { v -> Filters(keywords = listOf(v)) },
        "tags" to { v -> Filters(tags = listOf(v)) },
        "rarities" to { v -> Filters(rarities = listOf(v)) },
        "sets" to { v -> Filters(sets = listOf(v)) },
        "setTypes" to { v -> Filters(setTypes = listOf(v)) },
        "layouts" to { v -> Filters(layouts = listOf(v)) },
        "frames" to { v -> Filters(frames = listOf(v)) },
        "borders" to { v -> Filters(borders = listOf(v)) },
        "games" to { v -> Filters(games = listOf(v)) },
    )

    @Test
    fun everyTextFieldKeepsItsValueOutOfTheStatement() {
        textFields.forEach { (name, make) ->
            val f = make(marker)
            assertFalse(where(f).contains(marker, ignoreCase = true), "$name spliced its value")
        }
    }

    @Test
    fun everyTextFieldPutsItsValueIntoTheParameters() {
        textFields.forEach { (name, make) ->
            val f = make(marker)
            assertTrue(
                params(f).any { it?.toString()?.contains(marker, ignoreCase = true) == true },
                "$name bound nothing for its value",
            )
        }
    }

    @Test
    fun everyTextFieldProducesExactlyAsManyHolesAsValues() {
        textFields.forEach { (name, make) ->
            val f = make(marker)
            assertEquals(holes(where(f)), params(f).size, "$name is unbalanced")
        }
    }

    @Test
    fun noFieldEverEmitsANumberedPlaceholder() {
        // A single `?1` renumbers every bare `?` in the same
        // statement, which misbound the filter next to it.
        textFields.forEach { (name, make) ->
            assertFalse(Regex("\\?\\d").containsMatchIn(where(make(marker))), "$name numbered a hole")
        }
    }

    @Test
    fun everyNumericFieldKeepsItsValueOutOfTheStatement() {
        val numeric = listOf<Pair<String, (String) -> Filters>>(
            "qtyMin" to { v -> Filters(qtyMin = v) },
            "qtyMax" to { v -> Filters(qtyMax = v) },
            "freeMin" to { v -> Filters(freeMin = v) },
            "ciMin" to { v -> Filters(ciMin = v) },
            "ciMax" to { v -> Filters(ciMax = v) },
            "cmcMin" to { v -> Filters(cmcMin = v) },
            "cmcMax" to { v -> Filters(cmcMax = v) },
            "pow" to { v -> Filters(pow = v) },
            "tou" to { v -> Filters(tou = v) },
            "loy" to { v -> Filters(loy = v) },
            "priceMin" to { v -> Filters(priceMin = v) },
            "priceMax" to { v -> Filters(priceMax = v) },
            "edhrecMin" to { v -> Filters(edhrecMin = v) },
            "edhrecMax" to { v -> Filters(edhrecMax = v) },
            "yearMin" to { v -> Filters(yearMin = v) },
            "yearMax" to { v -> Filters(yearMax = v) },
        )
        numeric.forEach { (name, make) ->
            val f = make("4567")
            val sql = where(f) + having(f).sql
            assertFalse(sql.contains("4567"), "$name spliced its number")
            val bound = params(f) + having(f).params
            assertTrue(
                bound.any { it.toString().contains("4567") },
                "$name bound nothing: $bound",
            )
        }
    }

    @Test
    fun aQuoteInEveryTextFieldIsBoundRatherThanClosingALiteral() {
        textFields.forEach { (name, make) ->
            val sql = where(make("a' OR '1'='1"))
            assertFalse(sql.contains("OR '1'"), "$name spliced a quote: $sql")
        }
    }

    @Test
    fun aSemicolonInEveryTextFieldReachesNoStatement() {
        textFields.forEach { (name, make) ->
            val sql = where(make("x'; DROP TABLE cards; --"))
            assertFalse(sql.contains("DROP"), "$name spliced a statement: $sql")
            assertFalse(sql.contains(";"), "$name let a semicolon through: $sql")
        }
    }

    @Test
    fun aNewlineInAValueDoesNotRestructureTheStatement() {
        textFields.forEach { (name, make) ->
            val sql = where(make("a\nOR 1=1"))
            assertFalse(sql.contains("OR 1=1"), "$name spliced a newline: $sql")
        }
    }

    @Test
    fun theWholePanelAtOnceBindsEveryValueItHolds() {
        val f = everything()
        listOf(buildQuery(f), buildQuery(f, countOnly = true)).forEach { q ->
            assertEquals(holes(q.sql), q.params.size, "unbalanced:\n${q.sql}")
            assertFalse(q.sql.contains(marker), "something spliced")
        }
    }

    @Test
    fun theWholePanelAtOnceEmitsNoNumberedPlaceholders() {
        assertFalse(Regex("\\?\\d").containsMatchIn(buildQuery(everything()).sql))
    }

    @Test
    fun theOnlyQuotesInTheStatementAreTheOnesThisFileWrote() {
        // Every literal the builder owns: the escape character, the
        // empty colour string, the GLOB, the finishes, the kind, and
        // the rarity ladder. Nothing a person typed is ever quoted.
        val sql = buildQuery(everything()).sql
        val literals = Regex("'[^']*'").findAll(sql).map { it.value }.toSet()
        literals.forEach {
            assertFalse(it.contains(marker), "a typed value was quoted into the SQL: $it")
        }
    }

    private fun everything() = Filters(
        owner = marker, qtyMin = "1", qtyMax = "9", pool = Pool.FREE, freeMin = "1",
        deck = marker, finish = marker, q = marker, text = marker, flavor = marker,
        artist = marker, watermark = marker, typeLine = marker,
        colors = listOf("G"), colorMode = ColorMode.ATLEAST, ciMin = "1", ciMax = "3",
        produces = listOf("G"), cmcMin = "1", cmcMax = "6", manaCost = marker,
        pow = "1", tou = "2", loy = "3", types = listOf(marker),
        rarities = listOf(marker), sets = listOf(marker), setTypes = listOf(marker),
        layouts = listOf(marker), frames = listOf(marker), borders = listOf(marker),
        games = listOf(marker), yearMin = "2000", yearMax = "2026", collnum = marker,
        flags = mapOf(Flag.PROMO to Tri.YES, Flag.REPRINT to Tri.NO),
        priceMin = "1", priceMax = "99", keywords = listOf(marker), tags = listOf(marker),
        format = marker, legality = "legal", edhrecMin = "1", edhrecMax = "999",
        hasRulings = Tri.YES,
    )
}

// =====================================================================
//  The parameter budget
// =====================================================================

/**
 * How many values a statement binds, against the server's ceiling.
 *
 * D1 refuses more than a hundred, and the refusal is not graceful — a
 * search that crosses the line comes back "too many SQL variables"
 * rather than coming back. `ParamCountTest` covers the name box; this
 * covers the panel filling up.
 */
class FilterParamBudgetTest {

    private val LIMIT = 100

    private fun both(f: Filters) = listOf(buildQuery(f), buildQuery(f, countOnly = true))

    private fun under(name: String, f: Filters) {
        both(f).forEach { q ->
            assertTrue(q.params.size <= LIMIT, "$name binds ${q.params.size}")
            assertEquals(holes(q.sql), q.params.size, "$name is unbalanced")
        }
    }

    @Test
    fun oneWordPerBoxAcrossEveryTextBox() {
        under(
            "six boxes",
            Filters(q = "a", text = "b", flavor = "c", artist = "d", watermark = "e", typeLine = "f"),
        )
    }

    @Test
    fun tenWordsInEachOfTheSixTextBoxes() {
        fun words(p: String) = (1..10).joinToString(" ") { "$p$it" }
        val f = Filters(
            q = words("a"), text = words("b"), flavor = words("c"),
            artist = words("d"), watermark = words("e"), typeLine = words("f"),
        )
        // The rules-text box collapses into one MATCH however many
        // words it holds, which is most of why this fits.
        under("sixty words", f)
    }

    @Test
    fun aRulesTextBoxOfFiftyWordsIsStillOneParameter() {
        val f = Filters(text = (1..50).joinToString(" ") { "w$it" })
        assertEquals(1, buildQuery(f).params.size)
    }

    @Test
    fun aRulesTextBoxOfFiftyExclusionsIsStillOneParameter() {
        val f = Filters(text = (1..50).joinToString(" ") { "!w$it" })
        assertEquals(1, buildQuery(f).params.size)
    }

    @Test
    fun aRulesTextBoxMixingBothWaysIsTwoParameters() {
        val f = Filters(text = (1..25).joinToString(" ") { "w$it" } + " " + (1..25).joinToString(" ") { "!x$it" })
        assertEquals(2, buildQuery(f).params.size)
    }

    @Test
    fun everySetInTheCollectionTickedAtOnce() {
        under("sixty sets", Filters(sets = (1..60).map { "s$it" }))
    }

    @Test
    fun everyTypeTickedAtOnce() {
        // There are not sixty card types, but the list is whatever the
        // facet query returned.
        under("thirty types", Filters(types = (1..30).map { "t$it" }))
    }

    @Test
    fun everyKeywordTickedAtOnce() {
        under("fifty keywords", Filters(keywords = (1..50).map { "k$it" }))
    }

    @Test
    fun everyFlagSetAtOnceCostsNothing() {
        val f = Filters(flags = Flag.entries.associateWith { Tri.YES })
        assertEquals(0, buildQuery(f).params.size)
        assertEquals(Flag.entries.size, Regex(" = 1").findAll(where(f)).count())
    }

    @Test
    fun everyFlagClearedAtOnceCostsNothing() {
        val f = Filters(flags = Flag.entries.associateWith { Tri.NO })
        assertEquals(0, buildQuery(f).params.size)
    }

    @Test
    fun everyColourPipAndEveryModeCostsAtMostFive() {
        ColorMode.entries.forEach { m ->
            val f = Filters(colorMode = m, colors = COLOR_LETTERS + "C")
            assertTrue(buildQuery(f).params.size <= 5, "$m binds ${buildQuery(f).params.size}")
        }
    }

    @Test
    fun aBusyPanelWithWordsAndSetsAndTypesTogether() {
        under(
            "a busy panel",
            Filters(
                q = "goblin token artifact",
                text = "whenever this creature attacks create a token",
                flavor = "the !never", artist = "guay", watermark = "boros",
                typeLine = "legendary creature",
                sets = (1..30).map { "s$it" },
                types = listOf("Legendary", "Creature"),
                keywords = (1..10).map { "k$it" },
                tags = (1..10).map { "t$it" },
                rarities = listOf("rare", "mythic"),
                setTypes = listOf("core", "expansion"),
                layouts = listOf("normal"),
                frames = listOf("2015"),
                borders = listOf("black"),
                games = listOf("paper", "arena"),
                colors = listOf("R", "G"), colorMode = ColorMode.ATMOST,
                produces = listOf("R", "G"),
                cmcMin = "1", cmcMax = "6", ciMin = "1", ciMax = "2",
                pow = "1", tou = "1", priceMin = "1", priceMax = "99",
                edhrecMin = "1", edhrecMax = "9999", yearMin = "2000", yearMax = "2026",
                qtyMin = "1", qtyMax = "9", freeMin = "1", owner = "matt",
                finish = "nonfoil", collnum = "1", deck = "alela",
                format = "commander", hasRulings = Tri.YES,
                flags = Flag.entries.associateWith { Tri.NO },
                pool = Pool.FREE, manaCost = "{1}{R}",
            ),
        )
    }

    @Test
    fun theExportOfABusyPanelFitsTheSameCeiling() {
        val f = Filters(
            q = (1..20).joinToString(" ") { "w$it" },
            sets = (1..40).map { "s$it" },
            keywords = (1..10).map { "k$it" },
        )
        assertTrue(Export.query(f).params.size <= LIMIT, "export binds ${Export.query(f).params.size}")
        assertEquals(holes(Export.query(f).sql), Export.query(f).params.size)
    }

    @Test
    fun theCountBindsExactlyWhatThePageBinds() {
        // The two have to agree, or the pager counts a different
        // search than the grid shows.
        val f = Filters(q = "sol ring", sets = listOf("MH3"), qtyMin = "2")
        assertEquals(buildQuery(f).params, buildQuery(f, countOnly = true).params)
    }

    @Test
    fun theWhereParametersComeBeforeTheHavingOnes() {
        // The statement orders WHERE before HAVING, so the list must
        // too — swapped, "owned at least 2" became the owner.
        val f = Filters(owner = "matt", qtyMin = "2")
        assertEquals(listOf<Any?>("matt", 2.0), buildQuery(f).params)
        assertEquals(listOf<Any?>("matt", 2.0), buildQuery(f, countOnly = true).params)
    }
}

// =====================================================================
//  Colours
// =====================================================================

/** The four modes against every awkward selection, not just the happy one. */
class FilterColorModeEdgeTest {

    @Test
    fun exactlyOneColourIsThatLetterAlone() {
        assertEquals(listOf<Any?>("G"), params(Filters(colorMode = ColorMode.EXACTLY, colors = listOf("G"))))
    }

    @Test
    fun exactlyFiveColoursIsTheAlphabeticalFive() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("G", "R", "B", "U", "W"))
        assertEquals(listOf<Any?>("BGRUW"), params(f))
    }

    @Test
    fun exactlyIgnoresADuplicateLetter() {
        // 'WW' is not a colour identity.
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("W", "W"))
        assertEquals(listOf<Any?>("W"), params(f))
    }

    @Test
    fun exactlyIsOneEqualityAndNoLikeAtAll() {
        val sql = where(Filters(colorMode = ColorMode.EXACTLY, colors = listOf("U", "B")))
        assertEquals("COALESCE(c.color_identity, '') = ?", sql)
    }

    @Test
    fun exactlyColourlessNeedsNoParameter() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("C"))
        assertEquals("COALESCE(c.color_identity, '') = ''", where(f))
        assertTrue(params(f).isEmpty())
    }

    @Test
    fun exactlyColourlessWithAColourPicksTheColour() {
        // "Exactly white and colourless" is a contradiction; the
        // colour wins rather than producing two clauses that cannot
        // both hold.
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("C", "W"))
        assertEquals(listOf<Any?>("W"), params(f))
        assertEquals(1, Regex("COALESCE").findAll(where(f)).count())
    }

    @Test
    fun atLeastOneColourIsOneLike() {
        assertEquals(
            "COALESCE(c.color_identity, '') LIKE ?",
            where(Filters(colorMode = ColorMode.ATLEAST, colors = listOf("G"))),
        )
    }

    @Test
    fun atLeastFiveColoursIsFiveAndedLikes() {
        val f = Filters(colorMode = ColorMode.ATLEAST, colors = COLOR_LETTERS)
        assertEquals(5, Regex("LIKE \\?").findAll(where(f)).count())
        assertFalse(where(f).contains(" OR "))
        assertEquals(listOf<Any?>("%W%", "%U%", "%B%", "%R%", "%G%"), params(f))
    }

    @Test
    fun atLeastKeepsTheOrderTypedBecauseAndsCommute() {
        assertEquals(
            listOf<Any?>("%G%", "%W%"),
            params(Filters(colorMode = ColorMode.ATLEAST, colors = listOf("G", "W"))),
        )
    }

    @Test
    fun atLeastColourlessAloneIsTheEmptyIdentity() {
        val f = Filters(colorMode = ColorMode.ATLEAST, colors = listOf("C"))
        assertEquals("COALESCE(c.color_identity, '') = ''", where(f))
    }

    @Test
    fun anyOfOneColourIsStillBracketed() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = listOf("R"))
        assertEquals("(COALESCE(c.color_identity, '') LIKE ?)", where(f))
    }

    @Test
    fun anyOfFiveColoursIsFourOrs() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = COLOR_LETTERS)
        assertEquals(4, Regex(" OR ").findAll(where(f)).count())
        assertEquals(5, params(f).size)
    }

    @Test
    fun anyOfColourlessAloneIsJustTheEmptyIdentity() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = listOf("C"))
        assertEquals("(COALESCE(c.color_identity, '') = '')", where(f))
        assertTrue(params(f).isEmpty())
    }

    @Test
    fun anyOfPutsColourlessLastSoTheParametersLineUpWithTheHoles() {
        val f = Filters(colorMode = ColorMode.ANYOF, colors = listOf("C", "R", "G"))
        assertEquals(holes(where(f)), params(f).size)
        assertEquals(listOf<Any?>("%R%", "%G%"), params(f))
        assertTrue(where(f).endsWith("= '')"), where(f))
    }

    @Test
    fun atMostOneColourExcludesTheOtherFour() {
        val f = Filters(colorMode = ColorMode.ATMOST, colors = listOf("W"))
        assertEquals(4, Regex("NOT LIKE").findAll(where(f)).count())
        assertEquals(listOf<Any?>("%U%", "%B%", "%R%", "%G%"), params(f))
    }

    @Test
    fun atMostAllFiveColoursConstrainsNothingAtAll() {
        // "Nothing outside WUBRG" is every card, which is the honest
        // answer rather than an empty clause list.
        val f = Filters(colorMode = ColorMode.ATMOST, colors = COLOR_LETTERS)
        assertEquals("", where(f))
    }

    @Test
    fun atMostColourlessAloneIsTheEmptyIdentityAndNotFiveExclusions() {
        val f = Filters(colorMode = ColorMode.ATMOST, colors = listOf("C"))
        assertEquals(5, Regex("NOT LIKE").findAll(where(f)).count())
        assertTrue(where(f).contains("= ''"), where(f))
    }

    @Test
    fun atMostAColourAlreadyIncludesColourlessSoTheExtraClauseIsDropped() {
        // A colourless card has no letter outside the chosen set, so
        // the exclusions already admit it. Adding `= ''` as well would
        // mean "at most W" returned only Sol Ring.
        val f = Filters(colorMode = ColorMode.ATMOST, colors = listOf("W", "C"))
        assertFalse(where(f).contains("= ''"), where(f))
        assertEquals(4, Regex("NOT LIKE").findAll(where(f)).count())
    }

    @Test
    fun theOrderTypedNeverChangesTheAtMostClause() {
        val a = Filters(colorMode = ColorMode.ATMOST, colors = listOf("G", "W"))
        val b = Filters(colorMode = ColorMode.ATMOST, colors = listOf("W", "G"))
        assertEquals(where(a), where(b))
        assertEquals(params(a), params(b))
    }

    @Test
    fun theOrderTypedNeverChangesTheExactlyClause() {
        val a = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("G", "W", "U"))
        val b = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("U", "G", "W"))
        assertEquals(params(a), params(b))
    }

    @Test
    fun everyModeTargetsThePrintedColoursWhenAsked() {
        ColorMode.entries.forEach { m ->
            val f = Filters(colorMode = m, colors = listOf("W"), colorTarget = ColorTarget.PRINTED)
            assertTrue(where(f).contains("COALESCE(c.colors, '')"), "$m ignored the target")
            assertFalse(where(f).contains("color_identity"), "$m used the identity anyway")
        }
    }

    @Test
    fun everyModeAddsNothingForAnEmptySelection() {
        ColorMode.entries.forEach { m ->
            assertEquals("", where(Filters(colorMode = m)), "$m made a clause out of no colours")
        }
    }

    @Test
    fun theColourClauseIsNullSafeInEveryMode() {
        ColorMode.entries.forEach { m ->
            val f = Filters(colorMode = m, colors = listOf("W", "U"))
            if (where(f).isNotEmpty()) {
                assertTrue(where(f).contains("COALESCE("), "$m compares a possibly-null column raw")
            }
        }
    }

    @Test
    fun aModeSlugRoundTripsAndAnUnknownOneFallsBackToAtMost() {
        ColorMode.entries.forEach { assertEquals(it, ColorMode.of(it.slug)) }
        assertEquals(ColorMode.ATMOST, ColorMode.of("sideways"))
        assertEquals(ColorMode.ATMOST, ColorMode.of(""))
        assertEquals(ColorMode.ATMOST, ColorMode.of("EXACTLY"))
    }

    @Test
    fun everyModeExplainsItselfDifferently() {
        val said = ColorMode.entries.map { it.explains }
        assertEquals(said.size, said.toSet().size, "two modes say the same thing")
        said.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun theTwoColourTargetsNameDifferentColumns() {
        assertEquals("c.color_identity", ColorTarget.IDENTITY.column)
        assertEquals("c.colors", ColorTarget.PRINTED.column)
        assertNotEquals(ColorTarget.IDENTITY.slug, ColorTarget.PRINTED.slug)
    }

    @Test
    fun theFiveLettersAreTheFiveInWubrgOrder() {
        assertEquals(listOf("W", "U", "B", "R", "G"), COLOR_LETTERS)
    }
}

// =====================================================================
//  Flags
// =====================================================================

/** All ten flags, all three states, one column each. */
class FilterFlagMatrixTest {

    @Test
    fun everyFlagSetToYesComparesItsOwnColumnToOne() {
        Flag.entries.forEach { flag ->
            assertEquals("c.${flag.column} = 1", where(Filters(flags = mapOf(flag to Tri.YES))), flag.slug)
        }
    }

    @Test
    fun everyFlagSetToNoTreatsNullAsNotSet() {
        Flag.entries.forEach { flag ->
            assertEquals(
                "COALESCE(c.${flag.column}, 0) = 0",
                where(Filters(flags = mapOf(flag to Tri.NO))),
                flag.slug,
            )
        }
    }

    @Test
    fun everyFlagLeftAnyAddsNothing() {
        Flag.entries.forEach { flag ->
            assertEquals("", where(Filters(flags = mapOf(flag to Tri.ANY))), flag.slug)
        }
    }

    @Test
    fun aFlagMissingFromTheMapIsTheSameAsAny() {
        assertEquals("", where(Filters(flags = emptyMap())))
    }

    @Test
    fun noFlagEverBindsAValue() {
        Flag.entries.forEach { flag ->
            listOf(Tri.YES, Tri.NO).forEach { t ->
                assertTrue(params(Filters(flags = mapOf(flag to t))).isEmpty(), "${flag.slug} bound a value")
            }
        }
    }

    @Test
    fun theFlagClausesComeOutInEnumOrderSoTheSqlIsStable() {
        // Built back to front on purpose. Iterating the map instead of
        // the enum makes the statement depend on the order the panel
        // happened to tick things in, which `CoreSqlDump` would then
        // see change for no reason.
        val sql = where(Filters(flags = Flag.entries.reversed().associateWith { Tri.YES }))
        val seen = Flag.entries.map { sql.indexOf("c.${it.column} = 1") }
        assertFalse(seen.contains(-1), "a flag went missing: $sql")
        assertEquals(seen.sorted(), seen, "the flag order depends on map iteration: $sql")
    }

    @Test
    fun theTickingOrderNeverChangesTheStatement() {
        val forwards = Filters(flags = Flag.entries.associateWith { Tri.YES })
        val backwards = Filters(flags = Flag.entries.reversed().associateWith { Tri.YES })
        assertEquals(where(forwards), where(backwards))
    }

    @Test
    fun aMixOfYesAndNoProducesOneClauseEach() {
        val f = Filters(flags = mapOf(Flag.PROMO to Tri.YES, Flag.REPRINT to Tri.NO))
        assertTrue(where(f).contains("c.promo = 1"))
        assertTrue(where(f).contains("COALESCE(c.reprint, 0) = 0"))
        assertEquals(2, where(f).split("\n  AND ").size)
    }

    @Test
    fun everyFlagHasItsOwnColumn() {
        val columns = Flag.entries.map { it.column }
        assertEquals(columns.size, columns.toSet().size, "two flags share a column")
    }

    @Test
    fun everyFlagHasItsOwnSlug() {
        val slugs = Flag.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun everyFlagHasItsOwnLabel() {
        val labels = Flag.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun everyFlagColumnIsASnakeCaseIdentifierAndNothingElse() {
        // These are interpolated, not bound — they are identifiers
        // this file owns. The guard is that they stay identifiers.
        Flag.entries.forEach {
            assertTrue(Regex("^[a-z][a-z0-9_]*$").matches(it.column), "${it.slug} has column ${it.column}")
        }
    }

    @Test
    fun rulingsAndTheFlagsAreDifferentThingsEntirely() {
        assertFalse(where(Filters(hasRulings = Tri.YES)).contains("= 1"))
        assertEquals(
            "NOT EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)",
            where(Filters(hasRulings = Tri.NO)),
        )
    }

    @Test
    fun theThreeValuedStatesAreExactlyThree() {
        assertEquals(3, Tri.entries.size)
        assertEquals(Tri.ANY, Tri.entries.first(), "ANY has to be the default, and the default is the first")
    }
}

// =====================================================================
//  Combinations
// =====================================================================

/** Two filters at once, and a filter next to a sort or a page. */
class FilterCombinationTest {

    @Test
    fun twoFiltersAreJoinedWithAnAnd() {
        val sql = where(Filters(owner = "e7de0cb1", finish = "foil"))
        assertEquals("c.owner_id = (SELECT id FROM users WHERE key = ?)\n  AND c.finish = ?", sql)
    }

    @Test
    fun twoFiltersBindInTheOrderTheClausesAppear() {
        assertEquals(listOf<Any?>("e7de0cb1", "foil"), params(Filters(owner = "e7de0cb1", finish = "foil")))
    }

    @Test
    fun threeFiltersAreTwoAnds() {
        val f = Filters(owner = "e7de0cb1", finish = "foil", collnum = "1")
        assertEquals(2, Regex("\n  AND ").findAll(where(f)).count())
    }

    @Test
    fun aFilterAndASortAreIndependent() {
        val sql = buildQuery(Filters(owner = "e7de0cb1", sort = Sort.CMC)).sql
        assertTrue(sql.contains("WHERE c.owner_id = (SELECT id FROM users WHERE key = ?)"), sql)
        assertTrue(sql.contains("ORDER BY (MIN(c.cmc))"), sql)
    }

    @Test
    fun aFilterAndAPageAreIndependent() {
        val q = buildQuery(Filters(q = "bolt", page = 4, size = 20))
        assertTrue(q.sql.contains("LIMIT 20 OFFSET 60"), q.sql)
        assertEquals(listOf<Any?>("%bolt%"), q.params)
    }

    @Test
    fun theWhereComesBeforeTheGroupByWhichComesBeforeTheHaving() {
        val sql = buildQuery(Filters(owner = "matt", qtyMin = "2")).sql
        val w = sql.indexOf("WHERE")
        val g = sql.indexOf("GROUP BY")
        val h = sql.indexOf("HAVING")
        val o = sql.indexOf("ORDER BY")
        assertTrue(w in 1..<g, sql)
        assertTrue(g < h, sql)
        assertTrue(h < o, sql)
    }

    @Test
    fun aPerPrintingFilterStaysInTheWhereAndAStackFilterInTheHaving() {
        val f = Filters(rarities = listOf("rare"), qtyMin = "4")
        assertTrue(where(f).contains("c.rarity"))
        assertFalse(where(f).contains("SUM("))
        assertTrue(having(f).sql.contains("SUM(c.qty) >= ?"))
        assertFalse(having(f).sql.contains("c.rarity"))
    }

    @Test
    fun aStackFilterAloneStillProducesAWhereLessStatement() {
        val sql = buildQuery(Filters(qtyMin = "4")).sql
        assertFalse(sql.replace(Owners.keyOf("c.owner_id"), "").contains("WHERE"), sql)
        assertTrue(sql.contains("HAVING SUM(c.qty) >= ?"), sql)
    }

    @Test
    fun bothEndsOfTheStackRangeAreOneHavingWithAnAnd() {
        assertEquals(
            "SUM(c.qty) >= ?\n  AND SUM(c.qty) <= ?",
            having(Filters(qtyMin = "1", qtyMax = "4")).sql,
        )
    }

    @Test
    fun aStackMinimumOfZeroIsStillAHaving() {
        assertEquals(listOf<Any?>(0.0), having(Filters(qtyMin = "0")).params)
    }

    @Test
    fun aNonsenseStackRangeAddsNoHaving() {
        assertEquals("", having(Filters(qtyMin = "lots")).sql)
    }

    @Test
    fun freeCopiesAreAWhereBecauseTheViewIsAlreadyPerCard() {
        // `card_usage` is joined 1:1 on (owner, name_norm), so it is
        // the same value on every row of the group.
        assertTrue(where(Filters(freeMin = "1")).contains("COALESCE(u.free, 0)"))
        assertEquals("", having(Filters(freeMin = "1")).sql)
    }

    @Test
    fun aColourModeAndAColourCountCanDisagreeWithoutBreakingTheSql() {
        val f = Filters(colorMode = ColorMode.EXACTLY, colors = listOf("W"), ciMin = "3")
        assertEquals(holes(where(f)), params(f).size)
        assertEquals(listOf<Any?>("W", 3.0), params(f))
    }

    @Test
    fun aPoolAndADeckAreTwoDifferentQuestions() {
        val f = Filters(pool = Pool.COMMITTED, deck = "alela")
        assertTrue(where(f).contains("COALESCE(u.free, 0) <= 0"))
        assertTrue(where(f).contains("d.key = ?"))
    }

    @Test
    fun deckAnyBindsNothingBecauseItIsAboutTheOwnersOwnDecks() {
        assertTrue(params(Filters(deck = "_any")).isEmpty())
        assertTrue(where(Filters(deck = "_any")).contains("d.owner_id = c.owner_id"))
    }

    @Test
    fun deckNoneIsTheSameSubqueryNegated() {
        val any = where(Filters(deck = "_any"))
        val none = where(Filters(deck = "_none"))
        assertEquals("NOT $any", none)
    }

    @Test
    fun aNamedDeckAsksByKeyRatherThanByOwner() {
        val sql = where(Filters(deck = "q8ytka9m"))
        assertTrue(sql.contains("d.key = ?"), sql)
        assertFalse(sql.contains("d.owner_id = c.owner_id"), sql)
    }

    @Test
    fun aDeckCalledUnderscoreAnythingElseIsStillAKey() {
        assertEquals(listOf<Any?>("_other"), params(Filters(deck = "_other")))
    }

    @Test
    fun theQueryBoxAndsOnTopOfThePanelRatherThanReplacingIt() {
        val f = Filters(owner = "e7de0cb1", adv = "t:creature")
        assertTrue(where(f).contains("c.owner_id = (SELECT id FROM users WHERE key = ?)"))
        assertTrue(where(f).contains("lower(c.type_line) LIKE ?"))
        assertEquals(listOf<Any?>("e7de0cb1", "%creature%"), params(f))
    }

    @Test
    fun aQueryBoxExpressionWithSeveralTermsKeepsItsOwnAnds() {
        val f = Filters(adv = "t:creature mv<=3")
        assertEquals(2, params(f).size)
        assertEquals(holes(where(f)), params(f).size)
    }

    @Test
    fun theWholeSweepOfSingleFieldFiltersStaysBalanced() {
        singleFieldSweep().forEach { (name, f) ->
            listOf(buildQuery(f), buildQuery(f, countOnly = true), Export.query(f)).forEach { q ->
                assertEquals(holes(q.sql), q.params.size, "$name is unbalanced:\n${q.sql}")
                assertFalse(Regex("\\?\\d").containsMatchIn(q.sql), "$name numbered a hole")
            }
        }
    }

    @Test
    fun everySingleFieldFilterSurvivesEverySort() {
        Sort.entries.forEach { sort ->
            singleFieldSweep().forEach { (name, f) ->
                val q = buildQuery(f.copy(sort = sort))
                assertEquals(holes(q.sql), q.params.size, "$name + ${sort.slug} is unbalanced")
            }
        }
    }

    @Test
    fun everySingleFieldFilterSurvivesTheCountQuery() {
        singleFieldSweep().forEach { (name, f) ->
            val q = buildQuery(f, countOnly = true)
            assertTrue(q.sql.startsWith("SELECT COUNT(*) FROM ("), name)
            assertFalse(q.sql.contains("ORDER BY"), "$name left an ORDER BY in the count")
        }
    }
}

/** One `Filters` per field, which several tests sweep over. */
private fun singleFieldSweep(): List<Pair<String, Filters>> = listOf(
    "owner" to Filters(owner = "matt"),
    "qtyMin" to Filters(qtyMin = "2"),
    "qtyMax" to Filters(qtyMax = "8"),
    "pool.free" to Filters(pool = Pool.FREE),
    "pool.committed" to Filters(pool = Pool.COMMITTED),
    "freeMin" to Filters(freeMin = "1"),
    "deck" to Filters(deck = "alela"),
    "deck.any" to Filters(deck = "_any"),
    "deck.none" to Filters(deck = "_none"),
    "finish" to Filters(finish = "foil"),
    "q" to Filters(q = "sol ring"),
    "q.negated" to Filters(q = "ring !sol"),
    "text" to Filters(text = "draw a card"),
    "text.negated" to Filters(text = "!token"),
    "flavor" to Filters(flavor = "goblin"),
    "artist" to Filters(artist = "guay"),
    "watermark" to Filters(watermark = "boros"),
    "typeLine" to Filters(typeLine = "legendary creature"),
    "colors.exactly" to Filters(colorMode = ColorMode.EXACTLY, colors = listOf("W", "U")),
    "colors.atmost" to Filters(colorMode = ColorMode.ATMOST, colors = listOf("W", "U")),
    "colors.atleast" to Filters(colorMode = ColorMode.ATLEAST, colors = listOf("W", "U")),
    "colors.anyof" to Filters(colorMode = ColorMode.ANYOF, colors = listOf("W", "C")),
    "colors.printed" to Filters(colors = listOf("G"), colorTarget = ColorTarget.PRINTED),
    "ciMin" to Filters(ciMin = "2"),
    "ciMax" to Filters(ciMax = "3"),
    "produces" to Filters(produces = listOf("G", "C")),
    "cmcMin" to Filters(cmcMin = "1"),
    "cmcMax" to Filters(cmcMax = "6"),
    "manaCost" to Filters(manaCost = "{1}{G}"),
    "pow" to Filters(pow = "3"),
    "tou" to Filters(tou = "3", touOp = "<="),
    "loy" to Filters(loy = "3", loyOp = "="),
    "types" to Filters(types = listOf("Artifact", "Creature")),
    "rarities" to Filters(rarities = listOf("rare", "mythic")),
    "sets" to Filters(sets = listOf("MH3", "2X2")),
    "setTypes" to Filters(setTypes = listOf("commander")),
    "layouts" to Filters(layouts = listOf("saga")),
    "frames" to Filters(frames = listOf("2015")),
    "borders" to Filters(borders = listOf("black")),
    "games" to Filters(games = listOf("paper", "arena")),
    "yearMin" to Filters(yearMin = "2020"),
    "yearMax" to Filters(yearMax = "2026"),
    "collnum" to Filters(collnum = "117"),
    "flags.yes" to Filters(flags = mapOf(Flag.RESERVED to Tri.YES)),
    "flags.no" to Filters(flags = mapOf(Flag.PROMO to Tri.NO)),
    "priceMin" to Filters(priceMin = "1"),
    "priceMax" to Filters(priceMax = "50"),
    "keywords" to Filters(keywords = listOf("Flying")),
    "tags" to Filters(tags = listOf("ramp")),
    "format" to Filters(format = "commander"),
    "format.banned" to Filters(format = "commander", legality = "banned"),
    "format.notLegal" to Filters(format = "standard", legality = "not_legal"),
    "edhrecMin" to Filters(edhrecMin = "1"),
    "edhrecMax" to Filters(edhrecMax = "500"),
    "hasRulings.yes" to Filters(hasRulings = Tri.YES),
    "hasRulings.no" to Filters(hasRulings = Tri.NO),
    "adv" to Filters(adv = "t:creature mv<=3"),
)

// =====================================================================
//  Sorting
// =====================================================================

/** Every sort key, both directions, and the slug codec around them. */
class SortExhaustiveTest {

    /**
     * The SQL direction a sort should get. The arrow means "best first"
     * everywhere, and for a rank the best end is the low one, so EDHREC
     * is the one column whose SQL runs the other way. Named here by hand
     * rather than read off `Sort`, so the property cannot vouch for itself.
     */
    private fun sqlDir(sort: Sort, descending: Boolean): String =
        if (descending != (sort == Sort.EDHREC)) "DESC" else "ASC"

    @Test
    fun everySortPutsAllOfItsKeysInTheOrderBy() {
        Sort.entries.forEach { sort ->
            val sql = buildQuery(Filters(sort = sort)).sql
            sort.keys.forEach { key ->
                assertTrue(sql.contains("($key) ${sqlDir(sort, true)}"), "${sort.slug} lost the key $key:\n$sql")
            }
        }
    }

    @Test
    fun everySortPutsNullsLastDescending() {
        Sort.entries.forEach { sort ->
            val sql = buildQuery(Filters(sort = sort, descending = true)).sql
            sort.keys.forEach { key ->
                assertTrue(sql.contains("($key) IS NULL, ($key) ${sqlDir(sort, true)}"), "${sort.slug}:\n$sql")
            }
        }
    }

    @Test
    fun everySortPutsNullsLastAscendingToo() {
        Sort.entries.forEach { sort ->
            val sql = buildQuery(Filters(sort = sort, descending = false)).sql
            sort.keys.forEach { key ->
                assertTrue(sql.contains("($key) IS NULL, ($key) ${sqlDir(sort, false)}"), "${sort.slug}:\n$sql")
            }
        }
    }

    @Test
    fun everySortFallsBackToTheNameAsATiebreak() {
        Sort.entries.forEach { sort ->
            listOf(true, false).forEach { desc ->
                assertTrue(
                    buildQuery(Filters(sort = sort, descending = desc)).sql.endsWith(
                        ", c.name_norm ASC\nLIMIT 100 OFFSET 0",
                    ),
                    "${sort.slug} desc=$desc has no stable tiebreak",
                )
            }
        }
    }

    @Test
    fun theTiebreakIsAlwaysAscendingEvenWhenTheSortIsNot() {
        // Otherwise two equally-priced cards swap places between the
        // page and the count, and the pager drops one.
        assertTrue(buildQuery(Filters(descending = true)).sql.contains(", c.name_norm ASC"))
    }

    @Test
    fun everySortSlugRoundTrips() {
        Sort.entries.forEach { assertEquals(it, Sort.of(it.slug), it.slug) }
    }

    @Test
    fun anUnknownSortSlugIsTheDefaultRatherThanAnError() {
        assertEquals(Sort.DEFAULT, Sort.of("wibble"))
        assertEquals(Sort.DEFAULT, Sort.of(""))
        assertEquals(Sort.DEFAULT, Sort.of("   "))
    }

    @Test
    fun aSortSlugIsCaseSensitiveSoAMiscasedLinkFallsBack() {
        assertEquals(Sort.DEFAULT, Sort.of("NAME"))
        assertEquals(Sort.DEFAULT, Sort.of("Price"))
    }

    @Test
    fun theDefaultSortIsPriceBecauseThatIsTheQuestionAsked() {
        assertEquals(Sort.PRICE, Sort.DEFAULT)
        assertEquals(Sort.DEFAULT, Filters().sort)
        assertTrue(Filters().descending)
    }

    @Test
    fun everySortSlugIsUnique() {
        val slugs = Sort.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun everySortLabelIsUnique() {
        val labels = Sort.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun everySortKeyListIsUnique() {
        val keys = Sort.entries.map { it.keys }
        assertEquals(keys.size, keys.toSet().size, "two sorts order by the same thing")
    }

    @Test
    fun everyKeyIsEitherGroupedAggregatedOrAnOutputAlias() {
        // The query groups by name_norm, so anything else takes its
        // value from whichever row SQLite happened to pick — which is
        // how "newest first" came to sort a 2026 printing by its 1993
        // one.
        val aliases = setOf("qty", "free", "price", "value")
        Sort.entries.forEach { sort ->
            sort.keys.forEach { key ->
                val ok = key == "c.name_norm" || key in aliases ||
                    key.startsWith("MIN(") || key.startsWith("MAX(") ||
                    key.startsWith("SUM(") || key.startsWith("COUNT(")
                assertTrue(ok, "${sort.slug} orders by $key, which the GROUP BY does not define")
            }
        }
    }

    @Test
    fun theOnlyBareColumnAnySortUsesIsTheOneGroupedBy() {
        val bare = Sort.entries.flatMap { it.keys }.filter { it.startsWith("c.") }
        assertEquals(listOf("c.name_norm"), bare.distinct())
    }

    @Test
    fun everyOutputAliasASortUsesIsActuallySelected() {
        val select = buildQuery(Filters()).sql.substringBefore("\nFROM cards c")
        val aliases = setOf("qty", "free", "price", "value")
        Sort.entries.flatMap { it.keys }.filter { it in aliases }.forEach {
            assertTrue(select.contains(" AS $it"), "the sort key $it is not an output column")
        }
    }

    @Test
    fun theWordSortsSayAToZRatherThanSmallestFirst() {
        listOf(Sort.NAME, Sort.ARTIST, Sort.SET).forEach {
            assertEquals("A to Z", it.directionLabel(false), it.slug)
            assertEquals("Z to A", it.directionLabel(true), it.slug)
        }
    }

    @Test
    fun theDateSortSaysNewestAndOldest() {
        assertEquals("Newest first", Sort.RELEASED.directionLabel(true))
        assertEquals("Oldest first", Sort.RELEASED.directionLabel(false))
    }

    @Test
    fun theRankSortSaysPlayedBecauseALowerRankIsMorePlayed() {
        assertEquals("Most played first", Sort.EDHREC.directionLabel(true))
        assertEquals("Least played first", Sort.EDHREC.directionLabel(false))
    }

    @Test
    fun theNumericSortsFallBackToLargestAndSmallest() {
        listOf(Sort.CMC, Sort.QTY, Sort.FREE, Sort.PRICE, Sort.VALUE, Sort.POWER, Sort.TOUGHNESS, Sort.RARITY)
            .forEach {
                assertEquals("Largest first", it.directionLabel(true), it.slug)
                assertEquals("Smallest first", it.directionLabel(false), it.slug)
            }
    }

    @Test
    fun everySortGivesADifferentLabelForEachDirection() {
        Sort.entries.forEach {
            assertNotEquals(it.directionLabel(true), it.directionLabel(false), it.slug)
        }
    }

    @Test
    fun theColourSortIsTwoKeysSoItReadsCountThenIdentity() {
        assertEquals(2, Sort.COLOR.keys.size)
        val sql = buildQuery(Filters(sort = Sort.COLOR)).sql
        assertTrue(
            sql.indexOf("color_identity_count") < sql.indexOf("(MIN(c.color_identity)) IS NULL"),
            sql,
        )
    }

    @Test
    fun theRaritySortOrdersByTheLadderRatherThanAlphabetically() {
        // Alphabetically, 'common' beats 'mythic' and 'rare' beats
        // 'uncommon'.
        val key = Sort.RARITY.keys.single()
        assertTrue(key.contains("common uncommon rare mythic special bonus"), key)
        assertTrue(key.startsWith("MAX(instr("), key)
    }

    @Test
    fun thePowerSortOnlyLooksAtNumericPower() {
        assertTrue(Sort.POWER.keys.single().contains("GLOB '[0-9]*'"))
        assertTrue(Sort.TOUGHNESS.keys.single().contains("GLOB '[0-9]*'"))
    }

    @Test
    fun theDirectionOnlyChangesTheDirectionWords() {
        val desc = buildQuery(Filters(sort = Sort.NAME, descending = true)).sql
        val asc = buildQuery(Filters(sort = Sort.NAME, descending = false)).sql
        assertEquals(desc.replace(") DESC", ") ASC"), asc)
    }

    @Test
    fun theSortNeverBindsAParameter() {
        Sort.entries.forEach { sort ->
            assertTrue(buildQuery(Filters(sort = sort)).params.isEmpty(), sort.slug)
        }
    }

    @Test
    fun theCountQueryHasNoOrderByBecauseThereIsNothingToOrder() {
        Sort.entries.forEach { sort ->
            assertFalse(
                buildQuery(Filters(sort = sort), countOnly = true).sql.contains("ORDER BY"),
                sort.slug,
            )
        }
    }
}

// =====================================================================
//  Paging
// =====================================================================

/** The two interpolated numbers in the statement, at their edges. */
class PagingBoundaryTest {

    private fun limit(f: Filters) = Regex("LIMIT (-?\\d+) OFFSET (-?\\d+)")
        .find(buildQuery(f).sql)!!
        .let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }

    @Test
    fun pageOneStartsAtZero() {
        assertEquals(100L to 0L, limit(Filters(page = 1)))
    }

    @Test
    fun pageTwoStartsAtTheSecondPage() {
        assertEquals(100L to 100L, limit(Filters(page = 2)))
    }

    @Test
    fun pageZeroIsTreatedAsTheFirstPage() {
        assertEquals(100L to 0L, limit(Filters(page = 0)))
    }

    @Test
    fun aNegativePageIsTreatedAsTheFirstPage() {
        assertEquals(100L to 0L, limit(Filters(page = -1)))
        assertEquals(100L to 0L, limit(Filters(page = -999999)))
    }

    @Test
    fun aSizeOfZeroFallsBackToTheOnePageSize() {
        assertEquals(100L to 0L, limit(Filters(page = 1, size = 0)))
        assertEquals(100L to 100L, limit(Filters(page = 2, size = 0)))
    }

    @Test
    fun aNegativeSizeFallsBackToTheOnePageSize() {
        assertEquals(100L to 0L, limit(Filters(size = -5)))
    }

    @Test
    fun aSizeOfOneIsAllowedBecauseAPermalinkMayAskForIt() {
        assertEquals(1L to 2L, limit(Filters(page = 3, size = 1)))
    }

    @Test
    fun theOffsetIsPageMinusOneTimesSizeForEverySize() {
        listOf(1, 5, 25, 100, 250, 5000).forEach { size ->
            assertEquals(
                size.toLong() to 3L * size,
                limit(Filters(page = 4, size = size)),
                "size $size",
            )
        }
    }

    @Test
    fun theOffsetNeverGoesNegativeHoweverBigThePageIs() {
        listOf(1, 2, 1000, 21_474_837, 30_000_000, Int.MAX_VALUE).forEach { page ->
            val (_, offset) = limit(Filters(page = page))
            assertTrue(offset >= 0, "page $page gave offset $offset")
        }
    }

    @Test
    fun aHugePageNumberDoesNotOverflowBackIntoAnEarlierPage() {
        // `(page - 1) * size` in Int arithmetic wrapped past about 21
        // million, so a daft page number quietly showed page one.
        assertEquals(2_999_999_900L, limit(Filters(page = 30_000_000)).second)
        assertEquals(
            (Int.MAX_VALUE.toLong() - 1L) * 100L,
            limit(Filters(page = Int.MAX_VALUE)).second,
        )
    }

    @Test
    fun aHugePageAndAHugeSizeTogetherStillGrow() {
        val (size, offset) = limit(Filters(page = 1_000_000, size = 5000))
        assertEquals(5000L, size)
        assertEquals(4_999_995_000L, offset)
    }

    @Test
    fun thePageSizeIsTheOneThePanelDefaultsTo() {
        assertEquals(100, PAGE_SIZE)
        assertEquals(PAGE_SIZE, Filters().size)
        assertEquals(1, Filters().page)
    }

    @Test
    fun theCountQueryHasNoLimitBecauseItCountsEverything() {
        assertFalse(buildQuery(Filters(page = 3), countOnly = true).sql.contains("LIMIT"))
        assertFalse(buildQuery(Filters(size = 25), countOnly = true).sql.contains("OFFSET"))
    }

    @Test
    fun pagingNeverBindsAParameter() {
        assertTrue(buildQuery(Filters(page = 7, size = 33)).params.isEmpty())
    }

    @Test
    fun theExportIgnoresThePageAndTakesTheServerCap() {
        val sql = Export.query(Filters(page = 9, size = 10)).sql
        assertTrue(sql.contains("LIMIT ${Export.CAP} OFFSET 0"), sql)
    }

    @Test
    fun theExportKeepsTheFiltersAndTheSort() {
        val q = Export.query(Filters(q = "bolt", sort = Sort.NAME, descending = false))
        assertEquals(listOf<Any?>("%bolt%"), q.params)
        assertTrue(q.sql.contains("(c.name_norm) ASC"), q.sql)
    }
}

// =====================================================================
//  The shape of the statement
// =====================================================================

/** What surrounds the filters: the joins, the grouping and the columns. */
class QueryShapeTest {

    @Test
    fun thePageQueryJoinsUsageOnOwnerAndNameSoItCannotFanRowsOut() {
        assertTrue(buildQuery(Filters()).sql.contains("ON u.owner_id = c.owner_id AND u.name_norm = c.name_norm"))
    }

    @Test
    fun thePriceJoinIsOnTheScryfallIdBecausePricesArePerPrinting() {
        assertTrue(buildQuery(Filters()).sql.contains("ON pr.scryfall_id = c.scryfall_id"))
    }

    @Test
    fun theCountQueryJoinsBothTablesExactlyOnceToo() {
        val sql = buildQuery(Filters(), countOnly = true).sql
        assertEquals(1, Regex("LEFT JOIN card_usage").findAll(sql).count())
        assertEquals(1, Regex("LEFT JOIN prices").findAll(sql).count())
    }

    @Test
    fun bothJoinsAreLeftSoAnUnpricedUnusedCardStillAppears() {
        assertTrue(USAGE_JOIN.startsWith("LEFT JOIN"))
        assertTrue(PRICE_JOIN.startsWith("LEFT JOIN"))
    }

    @Test
    fun theJoinsAreThereEvenWhenNoFilterNeedsThem() {
        // They feed the columns on the tile, not only the filters.
        val sql = buildQuery(Filters()).sql
        assertTrue(sql.contains(USAGE_JOIN))
        assertTrue(sql.contains(PRICE_JOIN))
    }

    @Test
    fun thePriceExpressionIsFinishAwareInEveryPlaceItAppears() {
        val sql = buildQuery(Filters(priceMin = "1")).sql
        // the filter, plus price, value and unpriced on the tile
        assertEquals(4, Regex("WHEN 'etched'").findAll(sql).count(), sql)
    }

    @Test
    fun aFoilFallsBackThroughFoilToPlainAndEtchedThroughBoth() {
        assertTrue(PRICE_EXPR.contains("WHEN 'foil'   THEN COALESCE(pr.usd_foil, pr.usd)"))
        assertTrue(PRICE_EXPR.contains("WHEN 'etched' THEN COALESCE(pr.usd_etched, pr.usd_foil, pr.usd)"))
    }

    @Test
    fun theRepresentativePrintingIsTheLowestIdAndTheColumnsComeWithIt() {
        val sql = buildQuery(Filters()).sql
        assertTrue(sql.contains("SELECT MIN(c.id) AS id"))
        listOf("c.name", "c.scryfall_id", "c.setcode", "c.finish", "c.artist").forEach {
            assertTrue(sql.contains(it), "the tile lost $it")
        }
    }

    @Test
    fun freeIsCappedAtWhatIsOwnedInThisGroup() {
        // `card_usage.free` counts the whole collection, so filtering
        // to one finish showed "1 owned, 4 free".
        assertTrue(buildQuery(Filters()).sql.contains("MIN(COALESCE(u.free, 0), SUM(c.qty)) AS free"))
    }

    @Test
    fun theTileCountsPrintingsAndUnpricedCopiesSeparately() {
        val sql = buildQuery(Filters()).sql
        assertTrue(sql.contains("COUNT(*) AS printings"))
        assertTrue(sql.contains("AS unpriced"))
    }

    @Test
    fun stackValueIsRoundedToTheCent() {
        assertTrue(buildQuery(Filters()).sql.contains("ROUND(SUM(c.qty * ("))
        assertTrue(buildQuery(Filters()).sql.contains(")), 2) AS value"))
    }

    @Test
    fun theCountCountsTheGroupedRowsRatherThanSummingThem() {
        val sql = buildQuery(Filters(), countOnly = true).sql
        assertTrue(sql.startsWith("SELECT COUNT(*) FROM (SELECT 1 FROM cards c"), sql)
        assertTrue(sql.endsWith(")"), sql)
    }

    @Test
    fun theCountSelectsNothingBecauseItNeedsNoColumns() {
        val sql = buildQuery(Filters(), countOnly = true).sql
        assertFalse(sql.contains("MIN(c.id)"), sql)
        assertFalse(sql.contains("AS printings"), sql)
    }

    @Test
    fun theCountAndThePageFilterIdentically() {
        val f = Filters(q = "bolt", rarities = listOf("rare"), qtyMin = "2", pool = Pool.FREE)
        val conds = conditions(f).sql
        assertTrue(buildQuery(f).sql.contains(conds))
        assertTrue(buildQuery(f, countOnly = true).sql.contains(conds))
        assertTrue(buildQuery(f).sql.contains("HAVING ${having(f).sql}"))
        assertTrue(buildQuery(f, countOnly = true).sql.contains("HAVING ${having(f).sql}"))
    }

    @Test
    fun theGroupingIsByNameAndNothingElseInBothStatements() {
        listOf(buildQuery(Filters()), buildQuery(Filters(), countOnly = true)).forEach {
            assertEquals(1, Regex("GROUP BY").findAll(it.sql).count())
            assertTrue(it.sql.contains("GROUP BY c.name_norm"))
        }
    }

    @Test
    fun theStatementIsOneStatementWithNoSemicolon() {
        listOf(buildQuery(Filters()), buildQuery(Filters(), countOnly = true)).forEach {
            assertFalse(it.sql.contains(";"), it.sql)
        }
    }

    @Test
    fun theFilterClausesAreJoinedWithAndRatherThanOr() {
        val f = Filters(owner = "matt", rarities = listOf("rare"), cmcMin = "1")
        assertFalse(where(f).contains(" OR "), where(f))
        assertEquals(2, Regex("\n  AND ").findAll(where(f)).count())
    }

    @Test
    fun theWhereIsOmittedEntirelyWhenNothingIsFiltered() {
        // Not `WHERE 1=1`, which SQLite has to evaluate per row.
        assertFalse(buildQuery(Filters()).sql.contains("1=1"))
        assertTrue(buildQuery(Filters()).sql.contains("FROM cards c\n$USAGE_JOIN\n$PRICE_JOIN\n\nGROUP BY"))
    }

    @Test
    fun everyColumnTheTileReadsIsSelectedOrAggregated() {
        val select = buildQuery(Filters()).sql.substringBefore("\nFROM cards c")
        listOf("qty", "printings", "free", "price", "value", "unpriced", "id").forEach {
            assertTrue(select.contains(" AS $it"), "the tile has no $it")
        }
    }

    @Test
    fun theFtsSubqueryMatchesOnRowidSoItLinesUpWithTheCardId() {
        assertTrue(where(Filters(text = "draw")).contains("SELECT rowid FROM card_search"))
    }

    @Test
    fun theFtsPhraseHelperDoublesAnInternalQuote() {
        assertEquals("\"it's\"", ftsPhrase("it's"))
        assertEquals("\"say \"\"what\"\"\"", ftsPhrase("say \"what\""))
        assertEquals("\"\"", ftsPhrase(""))
    }
}

// =====================================================================
//  The bugs this file found
// =====================================================================

/**
 * One test per bug, named so a regression says which one came back.
 *
 * Each of these failed when it was written.
 */
class CardFiltersRegressionTest {

    @Test
    fun producesCannotBeTurnedIntoAWildcardByEditingTheLink() {
        // `#/search?produces=%25` bound `%%%`, which is every card
        // that taps for anything.
        assertEquals("", where(Filters(produces = listOf("%"))))
    }

    @Test
    fun theManaCostLikeDeclaresTheEscapeItsPatternUses() {
        // The pattern was escaped and the clause was not, so a cost
        // typed with `%` or `_` in it looked for a literal backslash
        // and found nothing.
        assertTrue(where(Filters(manaCost = "{U}")).endsWith("ESCAPE '\\'"), where(Filters(manaCost = "{U}")))
    }

    @Test
    fun aYearThatIsNotANumberDoesNotBuildADateOutOfIt() {
        assertEquals("", where(Filters(yearMin = "soon", yearMax = "later")))
    }

    @Test
    fun theOffsetArithmeticDoesNotOverflow() {
        assertTrue(buildQuery(Filters(page = 30_000_000)).sql.contains("OFFSET 2999999900"))
    }

    @Test
    fun aNonFiniteNumberIsNotBoundAsNull() {
        // NaN and Infinity both bind as SQL NULL, and no comparison
        // against NULL is ever true — an empty grid with no reason.
        listOf("NaN", "Infinity", "-Infinity", "1e400").forEach { v ->
            assertEquals("", where(Filters(cmcMin = v)), v)
            assertEquals("", where(Filters(priceMax = v)), v)
            assertEquals("", where(Filters(pow = v)), v)
            assertEquals("", having(Filters(qtyMin = v)).sql, v)
        }
    }

    @Test
    fun theFiniteCheckStillLetsEveryOrdinaryNumberThrough() {
        val c = Clauses()
        assertEquals(0.0, c.number("0"))
        assertEquals(-3.5, c.number(" -3.5 "))
        assertEquals(1.0E12, c.number("1e12"))
        assertNull(c.number(""))
        assertNull(c.number("  "))
        assertNull(c.number("x"))
    }
}
