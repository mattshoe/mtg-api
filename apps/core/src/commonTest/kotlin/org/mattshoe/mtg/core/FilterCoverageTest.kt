package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every field on the panel, and the SQL it is supposed to produce.
 *
 * `CardFiltersTest` covers the colour modes and the shape of the query.
 * This one is the other half: a case per control, so a field that is
 * wired to the wrong column, or silently dropped, fails here rather
 * than being discovered by someone searching for elves and getting
 * everything.
 *
 * `scripts/filter_sweep.py` runs the same set against the real
 * collection, which is the part a unit test cannot do.
 */
class FilterCoverageTest {

    private fun where(f: Filters) = conditions(f).sql
    private fun params(f: Filters) = conditions(f).params

    /** Every filter has to add a clause. A no-op filter is the bug. */
    private fun addsAClause(name: String, f: Filters) {
        assertTrue(where(f).isNotEmpty(), "$name added no clause at all")
    }

    /**
     * "Copies owned" asks about the stack, which is what the grid
     * shows — `SUM(c.qty)` over every printing. As a WHERE it asked
     * about one printing instead, so a card held as four separate
     * singles was hidden from "at least 4" while the grid printed 4
     * beside it.
     */
    @Test
    fun theStackIsFilteredNotThePrinting() {
        val f = Filters(qtyMin = "4", qtyMax = "8")
        assertEquals("", where(f), "qty must not be a WHERE")
        val post = having(f)
        assertTrue(post.sql.contains("SUM(c.qty) >= ?"), post.sql)
        assertTrue(post.sql.contains("SUM(c.qty) <= ?"), post.sql)
        assertEquals(listOf(4.0, 8.0), post.params)

        // And it has to reach both statements, or the count disagrees
        // with the rows it is counting.
        assertTrue(buildQuery(f).sql.contains("HAVING"))
        assertTrue(buildQuery(f, countOnly = true).sql.contains("HAVING"))
        assertEquals(listOf(4.0, 8.0), buildQuery(f, countOnly = true).params)
    }

    /**
     * Every sort key has to survive the GROUP BY.
     *
     * A bare column takes its value from the `MIN(c.id)` row — the
     * printing imported first — so "newest first" sorted a card by its
     * oldest printing. And "Colour identity" held two comma-separated
     * expressions, which `ORDER BY (a, b)` parses as a row value:
     * SQLite refuses it outright and the grid went blank.
     */
    @Test
    fun everySortKeyIsGroupedOrAggregated() {
        val perPrinting = listOf(
            "c.released_at", "c.setcode", "c.artist", "c.rarity", "c.power", "c.toughness",
        )
        Sort.entries.forEach { sort ->
            assertTrue(sort.keys.isNotEmpty(), "${sort.label} has no sort key")
            sort.keys.forEach { key ->
                // A comma inside `instr('a, b', x)` is fine; one at
                // the top level makes `ORDER BY (…)` a row value,
                // which SQLite refuses.
                assertFalse(
                    key.hasTopLevelComma(),
                    "${sort.label} packs two expressions into one key: $key",
                )
                val bare = perPrinting.any { key == it }
                assertFalse(bare, "${sort.label} sorts on a bare per-printing column: $key")
                if (perPrinting.any { key.contains(it) }) {
                    assertTrue(
                        key.startsWith("MIN(") || key.startsWith("MAX("),
                        "${sort.label} touches a per-printing column without aggregating: $key",
                    )
                }
            }
        }
    }

    /** True only for a comma outside every bracket and quote. */
    private fun String.hasTopLevelComma(): Boolean {
        var depth = 0
        var quoted = false
        forEach { ch ->
            when {
                ch == '\'' -> quoted = !quoted
                quoted -> Unit
                ch == '(' -> depth++
                ch == ')' -> depth--
                ch == ',' && depth == 0 -> return true
            }
        }
        return false
    }

    @Test
    fun theColourSortOrdersByCountThenIdentity() {
        val sql = buildQuery(Filters(sort = Sort.COLOR)).sql
        assertTrue(sql.contains("(MIN(c.color_identity_count)) DESC"), sql)
        assertTrue(sql.contains("(MIN(c.color_identity)) DESC"), sql)
        // The shape that broke it: `ORDER BY (a, b)` is a row value.
        assertFalse(sql.contains("(c.color_identity_count, c.color_identity)"), sql)
    }

    // ------------------------------------------------------------ types

    @Test
    fun eachTypeIsItsOwnExistsSoTheyAnd() {
        val sql = where(Filters(types = listOf("Artifact", "Creature")))
        assertEquals(2, Regex("kind = 'type'").findAll(sql).count())
        assertEquals(listOf("Artifact", "Creature"), params(Filters(types = listOf("Artifact", "Creature"))))
    }

    @Test
    fun excludedTypesAreNotExists() {
        val sql = where(Filters(typesNot = listOf("Land")))
        assertTrue(sql.startsWith("NOT EXISTS"), sql)
        assertTrue(sql.contains("kind = 'type'"))
    }

    @Test
    fun supertypesAndSubtypesUseTheirOwnKind() {
        assertTrue(where(Filters(supertypes = listOf("Legendary"))).contains("kind = 'supertype'"))
        assertTrue(where(Filters(subtypes = listOf("Elf"))).contains("kind = 'subtype'"))
    }

    @Test
    fun subtypesAreMatchedCaseInsensitively() {
        // Typed by hand, so "elf" has to find "Elf".
        assertEquals(listOf("elf"), params(Filters(subtypes = listOf("Elf"))))
        assertTrue(where(Filters(subtypes = listOf("Elf"))).contains("lower(ct.type) IN (?)"))
    }

    // -------------------------------------------------- keywords and tags

    @Test
    fun keywordsAreLowercasedAndEveryOneMustMatch() {
        val f = Filters(keywords = listOf("Flying", "Ward"))
        assertEquals(listOf("flying", "ward"), params(f))
        assertEquals(2, Regex("card_keywords").findAll(where(f)).count())
    }

    @Test
    fun tagsMatchTheSlugExactly() {
        val f = Filters(tags = listOf("mana-rock"))
        assertTrue(where(f).contains("ct.tag_slug = ?"))
        assertEquals(listOf("mana-rock"), params(f))
    }

    // -------------------------------------------------------- printing

    @Test
    fun setCodesAreLowercasedOnBothSides() {
        val f = Filters(sets = listOf("MH3", "2x2"))
        assertTrue(where(f).contains("lower(c.setcode) IN (?,?)"))
        assertEquals(listOf("mh3", "2x2"), params(f))
    }

    @Test
    fun theOtherPrintingListsAreInLists() {
        assertTrue(where(Filters(setTypes = listOf("commander"))).contains("c.set_type IN (?)"))
        assertTrue(where(Filters(layouts = listOf("saga"))).contains("c.layout IN (?)"))
        assertTrue(where(Filters(frames = listOf("2015"))).contains("c.frame IN (?)"))
        assertTrue(where(Filters(borders = listOf("black"))).contains("c.border_color IN (?)"))
        assertTrue(where(Filters(rarities = listOf("rare"))).contains("c.rarity IN (?)"))
    }

    @Test
    fun finishIsAnEqualsAndAnEmptyFinishMeansAny() {
        assertTrue(where(Filters(finish = "foil")).contains("c.finish = ?"))
        assertEquals("", where(Filters(finish = "")))
    }

    /**
     * Ticking two boxes in one list means "either", the way every
     * other checkbox list on the panel behaves. As one ANDed EXISTS
     * each, ticking paper and arena returned only the cards that are
     * both.
     */
    @Test
    fun gamesAreAlternativesNotRequirements() {
        val f = Filters(games = listOf("paper", "mtgo"))
        val sql = where(f)
        assertEquals(1, Regex("card_games").findAll(sql).count())
        assertTrue(sql.contains("g.game IN (?,?)"), sql)
        assertEquals(listOf("paper", "mtgo"), params(f))
    }

    @Test
    fun subtypesAreAlternativesToo() {
        // Elf and Goblin, not Elf-that-is-also-a-Goblin, which is
        // nothing.
        val f = Filters(subtypes = listOf("Elf", "Goblin"))
        val sql = where(f)
        assertEquals(1, Regex("kind = 'subtype'").findAll(sql).count())
        assertTrue(sql.contains("lower(ct.type) IN (?,?)"), sql)
        assertEquals(listOf("elf", "goblin"), params(f))
    }

    /** Types and supertypes stay conjunctive: they compose. */
    @Test
    fun typesAndSupertypesStillMeanAllOfThese() {
        assertEquals(2, Regex("kind = 'type'").findAll(where(Filters(types = listOf("Artifact", "Creature")))).count())
        assertEquals(
            2,
            Regex("kind = 'supertype'").findAll(where(Filters(supertypes = listOf("Legendary", "Snow")))).count(),
        )
    }

    @Test
    fun aYearBecomesTheWholeYear() {
        assertEquals(listOf("2023-01-01"), params(Filters(yearMin = "2023")))
        assertEquals(listOf("2024-12-31"), params(Filters(yearMax = "2024")))
    }

    @Test
    fun theCollectorNumberIsExactNotALike() {
        assertTrue(where(Filters(collnum = "117")).contains("c.collector_number = ?"))
    }

    // -------------------------------------------------------- legality

    @Test
    fun aFormatWithoutAStatusStillAsksForLegal() {
        val f = Filters(format = "commander")
        assertEquals(listOf("commander", "legal"), params(f))
        assertTrue(where(f).startsWith("EXISTS"))
    }

    @Test
    fun bannedAndRestrictedAreTheSameShapeWithADifferentStatus() {
        assertEquals(listOf("commander", "banned"), params(Filters(format = "commander", legality = "banned")))
        assertEquals(
            listOf("vintage", "restricted"),
            params(Filters(format = "vintage", legality = "restricted")),
        )
    }

    @Test
    fun notLegalIsAnAbsenceRatherThanAStatus() {
        // A card with no row for a format is not legal in it, which is
        // not the same as a row saying not_legal.
        val f = Filters(format = "standard", legality = "not_legal")
        assertTrue(where(f).startsWith("NOT EXISTS"), where(f))
        assertEquals(listOf("standard"), params(f))
    }

    @Test
    fun aStatusWithoutAFormatDoesNothing() {
        assertEquals("", where(Filters(legality = "banned")))
    }

    @Test
    fun rulingsGoBothWays() {
        assertTrue(where(Filters(hasRulings = Tri.YES)).startsWith("EXISTS"))
        assertTrue(where(Filters(hasRulings = Tri.NO)).startsWith("NOT EXISTS"))
        assertEquals("", where(Filters(hasRulings = Tri.ANY)))
    }

    // ------------------------------------------------------ collection

    @Test
    fun theThreePoolsAreTheThreeAnswers() {
        assertTrue(where(Filters(pool = Pool.FREE)).contains("COALESCE(u.free, 0) > 0"))
        assertTrue(where(Filters(pool = Pool.COMMITTED)).contains("COALESCE(u.free, 0) <= 0"))
        assertEquals("", where(Filters(pool = Pool.ALL)))
    }

    @Test
    fun deckByNameIsBoundBySlug() {
        val f = Filters(deck = "alela")
        assertTrue(where(f).contains("d.slug = ?"))
        assertEquals(listOf("alela"), params(f))
    }

    @Test
    fun priceUsesTheFinishAwareExpressionOnBothEnds() {
        val f = Filters(priceMin = "5", priceMax = "50")
        val sql = where(f)
        assertEquals(2, Regex("WHEN 'etched'").findAll(sql).count())
        assertEquals(listOf(5.0, 50.0), params(f))
    }

    @Test
    fun edhrecAndColourCountAreNumericRanges() {
        assertEquals(listOf(1.0, 500.0), params(Filters(edhrecMin = "1", edhrecMax = "500")))
        assertEquals(listOf(2.0, 3.0), params(Filters(ciMin = "2", ciMax = "3")))
    }

    @Test
    fun producesIsOnePerColour() {
        val f = Filters(produces = listOf("G", "U"))
        assertEquals(2, Regex("produced_mana").findAll(where(f)).count())
        assertEquals(listOf("%G%", "%U%"), params(f))
    }

    // ------------------------------------------------------------- traps

    /**
     * The one that cost an afternoon.
     *
     * SQLite applies a column's affinity to a text operand, so
     * `c.cmc >= '2'` happens to work. A comparison against an
     * *expression* has no affinity to apply, and text never equals a
     * number — so `COALESCE(u.free, 0) >= '2'` and the price CASE
     * silently match nothing. Every numeric filter has to bind a
     * number.
     */
    @Test
    fun everyNumericFilterBindsANumberNotAString() {
        val f = Filters(
            cmcMin = "1", cmcMax = "6", qtyMin = "2", qtyMax = "4",
            edhrecMin = "1", edhrecMax = "500", ciMin = "1", ciMax = "3",
            priceMin = "5", priceMax = "50", freeMin = "2",
            pow = "3", tou = "3", loy = "3",
        )
        val numbers = params(f)
        assertTrue(numbers.isNotEmpty())
        numbers.forEach {
            assertTrue(it is Number, "a numeric filter bound ${it?.let { v -> v::class.simpleName }}: $it")
        }
    }

    /** Every control on the panel has to reach the SQL. */
    @Test
    fun everyFieldOnThePanelChangesTheQuery() {
        addsAClause("owner", Filters(owner = "matt"))
        addsAClause("pool", Filters(pool = Pool.FREE))
        // qty is a HAVING now, not a WHERE — see `theStackIsFilteredNotThePrinting`.
        addsAClause("freeMin", Filters(freeMin = "1"))
        addsAClause("deck", Filters(deck = "alela"))
        addsAClause("edhrec", Filters(edhrecMax = "500"))
        addsAClause("price", Filters(priceMin = "1"))
        addsAClause("colors", Filters(colors = listOf("G")))
        addsAClause("produces", Filters(produces = listOf("G")))
        addsAClause("ci count", Filters(ciMin = "2"))
        addsAClause("types", Filters(types = listOf("Creature")))
        addsAClause("typesNot", Filters(typesNot = listOf("Land")))
        addsAClause("supertypes", Filters(supertypes = listOf("Legendary")))
        addsAClause("subtypes", Filters(subtypes = listOf("Elf")))
        addsAClause("typeLine", Filters(typeLine = "artifact"))
        addsAClause("cmc", Filters(cmcMin = "1"))
        addsAClause("manaCost", Filters(manaCost = "{G}"))
        addsAClause("power", Filters(pow = "3"))
        addsAClause("toughness", Filters(tou = "3"))
        addsAClause("loyalty", Filters(loy = "3"))
        addsAClause("q", Filters(q = "bolt"))
        addsAClause("text", Filters(text = "draw"))
        addsAClause("textLike", Filters(textLike = "enters"))
        addsAClause("flavor", Filters(flavor = "goblin"))
        addsAClause("artist", Filters(artist = "guay"))
        addsAClause("watermark", Filters(watermark = "prismari"))
        addsAClause("keywords", Filters(keywords = listOf("Flying")))
        addsAClause("tags", Filters(tags = listOf("mana-rock")))
        addsAClause("rarities", Filters(rarities = listOf("rare")))
        addsAClause("finish", Filters(finish = "foil"))
        addsAClause("sets", Filters(sets = listOf("MH3")))
        addsAClause("setTypes", Filters(setTypes = listOf("core")))
        addsAClause("year", Filters(yearMin = "2020"))
        addsAClause("collnum", Filters(collnum = "117"))
        addsAClause("layouts", Filters(layouts = listOf("saga")))
        addsAClause("frames", Filters(frames = listOf("2015")))
        addsAClause("borders", Filters(borders = listOf("black")))
        addsAClause("games", Filters(games = listOf("paper")))
        addsAClause("format", Filters(format = "commander"))
        addsAClause("hasRulings", Filters(hasRulings = Tri.YES))
        Flag.entries.forEach { addsAClause(it.slug, Filters(flags = mapOf(it to Tri.YES))) }
    }

    /**
     * And the panel's own bookkeeping has to agree with the filters,
     * or the badge on a group header lies about what is set inside it.
     */
    @Test
    fun everyFieldIsCountedByExactlyOneGroup() {
        val cases = listOf<Pair<Facet, Filters>>(
            Facet.COLLECTION to Filters(owner = "matt"),
            Facet.COLLECTION to Filters(pool = Pool.FREE),
            Facet.COLLECTION to Filters(qtyMin = "2"),
            Facet.COLLECTION to Filters(freeMin = "1"),
            Facet.COLLECTION to Filters(deck = "alela"),
            Facet.COLLECTION to Filters(edhrecMax = "500"),
            Facet.COLLECTION to Filters(priceMin = "1"),
            Facet.COLOUR to Filters(colors = listOf("G")),
            Facet.COLOUR to Filters(produces = listOf("G")),
            Facet.COLOUR to Filters(ciMin = "2"),
            Facet.TYPE to Filters(types = listOf("Creature")),
            Facet.TYPE to Filters(typesNot = listOf("Land")),
            Facet.TYPE to Filters(supertypes = listOf("Legendary")),
            Facet.TYPE to Filters(subtypes = listOf("Elf")),
            Facet.TYPE to Filters(typeLine = "artifact"),
            Facet.MANA to Filters(cmcMin = "1"),
            Facet.MANA to Filters(manaCost = "{G}"),
            Facet.MANA to Filters(pow = "3"),
            Facet.TEXT to Filters(q = "bolt"),
            Facet.TEXT to Filters(text = "draw"),
            Facet.TEXT to Filters(artist = "guay"),
            Facet.TAGS to Filters(keywords = listOf("Flying")),
            Facet.TAGS to Filters(tags = listOf("mana-rock")),
            Facet.PRINTING to Filters(rarities = listOf("rare")),
            Facet.PRINTING to Filters(finish = "foil"),
            Facet.PRINTING to Filters(sets = listOf("MH3")),
            Facet.PRINTING to Filters(yearMin = "2020"),
            Facet.PRINTING to Filters(collnum = "117"),
            Facet.PHYSICAL to Filters(layouts = listOf("saga")),
            Facet.PHYSICAL to Filters(games = listOf("paper")),
            Facet.FLAGS to Filters(flags = mapOf(Flag.RESERVED to Tri.YES)),
            Facet.LEGALITY to Filters(format = "commander"),
            Facet.LEGALITY to Filters(hasRulings = Tri.YES),
        )
        cases.forEach { (owner, f) ->
            val counted = Facet.entries.filter { it.countIn(f) > 0 }
            assertEquals(listOf(owner), counted, "the wrong group owns $f")
        }
    }

    @Test
    fun anUntouchedPanelCountsNothingAnywhere() {
        Facet.entries.forEach { assertEquals(0, it.countIn(Filters()), it.title) }
        assertTrue(Facet.inUse(Filters()).isEmpty())
    }
}
