package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A search box, read the way Scryfall reads one.
 *
 * Every box used to look for the exact run of characters typed into
 * it, so "draw card" found nothing unless a card said precisely that.
 * The oracle-text box was worse: it handed the whole string to FTS5 as
 * one quoted phrase, which is the opposite of what a full-text index
 * is for.
 */
class TextQueryTest {

    private fun words(raw: String) = TextQuery.parse(raw).map { it.text }
    private fun of(raw: String) = TextQuery.parse(raw)

    @Test
    fun wordsAreSeparateTerms() {
        assertEquals(listOf("draw", "card"), words("draw card"))
        assertEquals(listOf("draw", "card"), words("  draw   card  "))
    }

    @Test
    fun quotesHoldAPhraseTogether() {
        val t = of("\"draw a card\"")
        assertEquals(1, t.size)
        assertEquals("draw a card", t[0].text)
        assertTrue(t[0].phrase)
    }

    @Test
    fun aPhraseAndLooseWordsMix() {
        val t = of("flying \"enters tapped\" token")
        assertEquals(listOf("flying", "enters tapped", "token"), t.map { it.text })
        assertEquals(listOf(false, true, false), t.map { it.phrase })
    }

    @Test
    fun bangExcludes() {
        val t = of("draw !token")
        assertEquals(listOf(false, true), t.map { it.negated })
        assertEquals(listOf("draw", "token"), t.map { it.text })
    }

    @Test
    fun andItExcludesAPhraseToo() {
        val t = of("!\"enters the battlefield tapped\"")
        assertEquals(1, t.size)
        assertTrue(t[0].negated)
        assertTrue(t[0].phrase)
        assertEquals("enters the battlefield tapped", t[0].text)
    }

    @Test
    fun aBangInsideAWordIsJustACharacter() {
        // There are cards with "!" in their text, and somebody typing
        // one is not writing an operator.
        assertEquals(listOf("wow!"), words("wow!"))
        assertEquals(listOf(false), of("wow!").map { it.negated })
    }

    @Test
    fun aLoneBangIsNotATerm() {
        assertEquals(emptyList(), words("!"))
        assertEquals(listOf("draw"), words("draw !"))
    }

    @Test
    fun anUnclosedQuoteTakesTheRest() {
        // Halfway through typing. Results for what is there so far
        // beat a red banner.
        assertEquals(listOf("enters tap"), words("\"enters tap"))
    }

    @Test
    fun nothingTypedIsNoTerms() {
        assertEquals(emptyList(), words(""))
        assertEquals(emptyList(), words("   "))
        assertEquals(emptyList(), words("\"\""))
        assertTrue(TextQuery.isEmpty("  "))
        assertTrue(!TextQuery.isEmpty("a"))
    }

    // ------------------------------------------------------- into SQL

    private fun where(f: Filters) = buildQuery(f).sql.substringAfter("WHERE").substringBefore("GROUP BY")
    private fun params(f: Filters) = buildQuery(f).params

    @Test
    fun twoWordsInTheNameBoxAreTwoConditions() {
        val f = Filters(q = "sol ring")
        // Both words, not the phrase "sol ring" — so "Ring of Solitude"
        // would match too, which is the point.
        assertEquals(2, Regex("c\\.name_norm").findAll(where(f)).count())
        assertEquals(listOf("%sol%", "%sol%", "%sol%", "%ring%", "%ring%", "%ring%"), params(f))
    }

    @Test
    fun aQuotedNameIsOneCondition() {
        val f = Filters(q = "\"sol ring\"")
        assertEquals(1, Regex("c\\.name_norm").findAll(where(f)).count())
        assertEquals(listOf("%sol ring%", "%sol ring%", "%sol ring%"), params(f))
    }

    @Test
    fun anExcludedWordBecomesANotAndSurvivesANullColumn() {
        val sql = where(Filters(flavor = "!goblin"))
        assertTrue(sql.contains("NOT ("), sql)
        // `NULL NOT LIKE '%x%'` is NULL, not true, so without the
        // COALESCE every card with no flavour text would be excluded
        // by a filter asking for cards without "goblin" in it.
        assertTrue(sql.contains("COALESCE(lower(c.flavor_text), '')"), sql)
    }

    @Test
    fun theOracleBoxAndsItsWordsInsteadOfQuotingTheLot() {
        val f = Filters(text = "draw card")
        assertEquals(listOf("\"draw\" AND \"card\""), params(f))
    }

    @Test
    fun aQuotedOracleSearchIsStillAPhrase() {
        assertEquals(listOf("\"draw a card\""), params(Filters(text = "\"draw a card\"")))
    }

    @Test
    fun punctuationInTheOracleBoxIsNotFtsSyntax() {
        // `+1/+1` and `Landfall:` are fts5 syntax errors bare. Quoted,
        // they are what somebody meant.
        assertEquals(listOf("\"+1/+1\""), params(Filters(text = "+1/+1")))
        assertEquals(listOf("\"Landfall:\""), params(Filters(text = "Landfall:")))
    }

    @Test
    fun anExcludedOracleWordIsSubtractedRatherThanMatched() {
        val f = Filters(text = "draw !token")
        val sql = where(f)
        assertTrue(sql.contains("c.id IN (SELECT rowid FROM card_search"), sql)
        assertTrue(sql.contains("c.id NOT IN (SELECT rowid FROM card_search"), sql)
        assertEquals(listOf("\"draw\"", "\"token\""), params(f))
    }

    @Test
    fun onlyExclusionsStillWorks() {
        // fts5's own NOT is binary and has nothing to subtract from
        // when every term is negative, which is why this is two
        // clauses rather than one expression.
        val f = Filters(text = "!token")
        val sql = where(f)
        assertTrue(!sql.contains("c.id IN (SELECT"), sql)
        assertTrue(sql.contains("c.id NOT IN (SELECT"), sql)
        assertEquals(listOf("\"token\""), params(f))
    }

    @Test
    fun severalExclusionsAreOredBeforeBeingSubtracted() {
        // Exclude a card that says either one, not only cards that say
        // both.
        assertEquals(listOf("\"token\" OR \"proliferate\""), params(Filters(text = "!token !proliferate")))
    }

    @Test
    fun everyBoxIsCaseInsensitive() {
        // Lowered on both sides: the pattern here, the column in the
        // statement.
        listOf(
            Filters(q = "SOL") to "%sol%",
            Filters(flavor = "GOBLIN") to "%goblin%",
            Filters(artist = "GUAY") to "%guay%",
            Filters(watermark = "BOROS") to "%boros%",
            Filters(typeLine = "CREATURE") to "%creature%",
        ).forEach { (f, expected) ->
            assertTrue(params(f).contains(expected), "${params(f)} has no $expected")
        }
        assertTrue(where(Filters(typeLine = "X")).contains("lower(c.type_line)"))
    }

    @Test
    fun theWildcardsOfLikeAreStillCharacters() {
        // Tokenising must not have lost the escaping.
        assertTrue(params(Filters(q = "%")).all { it == "%\\%%" }, params(Filters(q = "%")).toString())
        assertTrue(params(Filters(q = "_")).all { it == "%\\_%" })
    }
}
