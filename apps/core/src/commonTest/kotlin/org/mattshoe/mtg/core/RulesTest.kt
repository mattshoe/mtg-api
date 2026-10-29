package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Guide's rules page: the Comprehensive Rules, searched on the device.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... searching trample should show me all rules that mention trample
 * ... i want each rule to be collapsible, but expanded my default ...
 * Ideally, this would use the live rule set, then parse it out
 * on-device to save server processing power".
 *
 * [TEXT] is cut from the real file, `MagicCompRules 20260925.txt`, with
 * its contents block, its blank lines of one space, its curly quotes and
 * its glossary, so the parser meets what Wizards actually publishes.
 */
class RulesTest {

    private val book get() = RuleBook.parse(TEXT)

    @Test
    fun everyNumberedRuleIsItsOwnRuleInOrder() {
        assertEquals(
            listOf("100.1.", "100.1a", "702.19.", "702.19a", "702.19b", "702.19c", "702.20.", "702.20a"),
            book.rules.map { it.number },
        )
    }

    @Test
    fun theContentsAndTheGlossaryAreNotRules() {
        // The contents list "100. General" and the glossary's own
        // "Trample" entry; neither is a numbered rule.
        assertTrue(book.rules.none { it.number == "100." }, "a contents line became a rule")
        assertTrue(book.rules.none { "keyword ability that modifies" in it.text }, "the glossary became a rule")
    }

    @Test
    fun aRuleKeepsItsTextItsExamplesAndTheSectionItIsIn() {
        val b = book.rules.single { it.number == "702.19b" }
        assertTrue(b.text.startsWith("The controller of an attacking creature with trample first assigns"), b.text)
        assertEquals(2, b.examples.size, "the two examples under 702.19b went missing: ${b.examples}")
        assertTrue(b.examples[0].startsWith("A 2/2 creature that can block"), b.examples[0])
        assertEquals("702. Keyword Abilities", b.section)
        assertEquals("100. General", book.rules.first().section)
    }

    /**
     * Two lines in the 2026-09-25 file are misprinted: `119.1d.` has a
     * rule's dot on a subrule, and `606.5` has lost its dot. Read
     * strictly, both vanished, along with anything they mention.
     */
    @Test
    fun theFilesOwnMisprintedNumbersAreStillRules() {
        val book = RuleBook.parse(
            """
            |100.1. These Magic rules apply to any Magic game.
            |
            |119.1d. In a two-player Brawl game, each player’s starting life total is 25.
            |
            |606.5 If the total cost to activate a loyalty ability contains multiple costs.
            |
            |Glossary
            """.trimMargin(),
        )
        assertEquals(listOf("100.1.", "119.1d.", "606.5"), book.rules.map { it.number })
        assertEquals(listOf("119.1d"), book.search("brawl").map { it.shownNumber })
    }

    @Test
    fun theBookSaysWhenItTookEffect() {
        assertEquals("These rules are effective as of September 25, 2026.", book.effective)
    }

    @Test
    fun searchingTrampleFindsEveryRuleThatMentionsTrampleAndNothingElse() {
        val hits = book.search("trample").map { it.number }
        assertEquals(listOf("702.19.", "702.19a", "702.19b", "702.19c"), hits)
    }

    @Test
    fun searchIgnoresCaseAndNeedsEveryWord() {
        assertEquals(book.search("trample"), book.search("TRAMPLE"))
        assertEquals(listOf("702.19c"), book.search("trample planeswalker").map { it.number })
    }

    @Test
    fun anExampleCountsAsWhatItsRuleMentions() {
        // "protection" is only in 702.19b's second example.
        assertEquals(listOf("702.19b"), book.search("protection").map { it.number })
    }

    @Test
    fun aStraightApostropheFindsTheCurlyOneTheRulesPrint() {
        assertEquals(listOf("702.19a"), book.search("creature's combat").map { it.number })
    }

    @Test
    fun aRuleNumberFindsThatRuleAndItsSubrules() {
        assertEquals(listOf("702.20.", "702.20a"), book.search("702.20").map { it.number })
    }

    /**
     * Matt: "i want rules searches to support "quoted" text that searches
     * anything in the quotes as a single string instead of tokenized
     * words". 702.19a has "assigning", "combat" and "damage" apart;
     * only 702.19c has them side by side.
     */
    @Test
    fun quotedWordsAreFoundSideBySideNotScatteredAcrossTheRule() {
        assertEquals(listOf("702.19a", "702.19c"), book.search("assigning combat damage").map { it.number })
        assertEquals(listOf("702.19c"), book.search("\"assigning combat damage\"").map { it.number })
    }

    @Test
    fun aQuotedPhraseAndLooseWordsMustAllBeThere() {
        assertEquals(listOf("702.19a"), book.search("static \"combat damage\"").map { it.number })
        assertEquals(listOf("702.19c"), book.search("\"combat damage\" planeswalkers").map { it.number })
    }

    @Test
    fun aPhonesCurlyQuotesQuoteAsWell() {
        assertEquals(listOf("702.19c"), book.search("“assigning combat damage”").map { it.number })
    }

    @Test
    fun aQuoteLeftOpenQuotesToTheEnd() {
        assertEquals(listOf("702.19c"), book.search("\"assigning combat damage").map { it.number })
    }

    @Test
    fun aQuotedPhraseIgnoresCaseAndExtraSpaces() {
        assertEquals(listOf("702.19c"), book.search("\"  Assigning   COMBAT damage \"").map { it.number })
    }

    @Test
    fun emptyQuotesFindNothing() {
        assertEquals(emptyList(), book.search("\"\""))
        assertEquals(emptyList(), book.search("\" \""))
    }

    @Test
    fun aBlankSearchFindsNothing() {
        assertEquals(emptyList(), book.search(""))
        assertEquals(emptyList(), book.search("   "))
    }

    @Test
    fun anUnreadableFileIsNoRulesRatherThanACrash() {
        assertEquals(emptyList(), RuleBook.parse("<html>Access Denied</html>").rules)
    }

    // The page's state.

    @Test
    fun everyRuleStartsExpandedAndCollapsesOnATap() {
        val r = Rules().loaded(TEXT).searched("trample")
        assertTrue(r.hits.all { r.isOpen(it) }, "a rule started collapsed")
        val b = r.hits.single { it.number == "702.19b" }
        val shut = r.toggled(b)
        assertFalse(shut.isOpen(b), "tapping 702.19b did not collapse it")
        assertTrue(shut.hits.filter { it != b }.all { shut.isOpen(it) }, "collapsing one collapsed another")
        assertTrue(shut.toggled(b).isOpen(b), "tapping again did not expand it")
    }

    @Test
    fun loadingThenFailingLeavesNoSpinnerAndSaysWhy() {
        val failed = Rules().loading().failed("Wizards answered 404")
        assertFalse(failed.busy, "the spinner is still up after the load failed")
        assertEquals("Wizards answered 404", failed.error)
        val again = failed.loading()
        assertTrue(again.busy)
        assertNull(again.error, "trying again still shows the old error")
    }

    @Test
    fun aLoadedPageIsNotBusyAndKeepsWhatWasTyped() {
        val r = Rules().searched("trample").loading().loaded(TEXT)
        assertFalse(r.busy)
        assertNotNull(r.book)
        assertEquals(4, r.hits.size, "a search typed while the rules loaded was dropped")
    }

    @Test
    fun aWildlyBroadSearchShowsAFirstPageAndSaysHowManyThereWere() {
        val r = Rules().loaded(TEXT).searched("a")
        assertEquals(book.search("a").size, r.total)
        assertTrue(r.hits.size <= Rules.SHOWN)
    }

    @Test
    fun theRulesTileIsInTheGuideAndOpensAtItsOwnAddress() {
        val tile = Guide.tile(Guide.RULES) ?: error("the Guide has no rules tile")
        assertEquals("Rules", tile.title)
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/rules", open.route.toHash())
        assertTrue(open.onRules, "the rules tile is open but the app does not know it")
        assertFalse(AppState().navigate(View.GUIDE).onRules)
    }

    companion object {
        val TEXT = """
            |Magic: The Gathering Comprehensive Rules
            | 
            |These rules are effective as of September 25, 2026.
            | 
            |Introduction
            | 
            |This document is the ultimate authority for Magic: The Gathering® competitive game play.
            | 
            |Contents
            | 
            |1. Game Concepts
            |100. General
            |7. Additional Rules
            |702. Keyword Abilities
            | 
            |Glossary
            | 
            |Credits
            |
            |1. Game Concepts
            | 
            |100. General
            | 
            |100.1. These Magic rules apply to any Magic game with two or more players, including two-player games and multiplayer games.
            | 
            |100.1a A two-player game is a game that begins with only two players.
            | 
            |7. Additional Rules
            | 
            |702. Keyword Abilities
            | 
            |702.19. Trample
            | 
            |702.19a Trample is a static ability that modifies the rules for assigning an attacking creature’s combat damage.
            | 
            |702.19b The controller of an attacking creature with trample first assigns damage to the creature(s) blocking it.
            |Example: A 2/2 creature that can block an additional creature blocks two attackers: a 1/1 with no abilities and a 3/3 with trample.
            |Example: A 6/6 green creature with trample is blocked by a 2/2 creature with protection from green.
            | 
            |702.19c Trample over planeswalkers is a variant of trample that modifies the rules for assigning combat damage to planeswalkers.
            | 
            |702.20. Vigilance
            | 
            |702.20a Vigilance is a static ability that modifies the rules for the declare attackers step.
            | 
            |Glossary
            | 
            |Trample
            |A keyword ability that modifies how a creature assigns combat damage. See rule 702.19, “Trample.”
            | 
            |Credits
            | 
            |Magic: The Gathering Original Game Design: Richard Garfield
        """.trimMargin()
    }
}
