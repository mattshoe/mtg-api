package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The new deck wizard's rules.
 *
 * Creating a deck moves real cards — it pulls from bulk and buys what
 * bulk cannot cover — so every gate on the way is load-bearing and none
 * of them belongs in a UI.
 */
class NewDeckTest {

    private val threeCards = "1 Sol Ring\n1 Arcane Signet\n1 Command Tower"
    private fun checked(ok: Boolean = true) = Validation(ok = ok, checked = 3, unknown = if (ok) 0 else 1)

    @Test
    fun startsAtTheFormatWithNothingChosen() {
        val s = NewDeck()
        assertEquals(DeckStep.FORMAT, s.step)
        assertFalse(s.canLeaveFormat)
    }

    @Test
    fun theCommanderStepOnlyExistsForFormatsThatWantOne() {
        assertTrue(NewDeck().pick(Format.COMMANDER).steps.contains(DeckStep.COMMANDER))
        assertFalse(NewDeck().pick(Format.STANDARD).steps.contains(DeckStep.COMMANDER))
    }

    @Test
    fun aCommanderFormatWillNotPassTheCommanderStepEmpty() {
        val s = NewDeck().pick(Format.COMMANDER).assign(Owner.MATT).rename("Alela")
        assertFalse(s.canLeaveCommander)
        assertTrue(s.setCommander("Alela, Artful Provocateur").canLeaveCommander)
    }

    @Test
    fun aFormatWithoutACommanderSkipsStraightPastIt() {
        val s = NewDeck().pick(Format.STANDARD).assign(Owner.MATT).rename("Mono Red")
        assertTrue(s.canLeaveCommander, "Standard must not demand a commander")
    }

    @Test
    fun neitherOwnerIsAssumed() {
        assertFalse(NewDeck().pick(Format.COMMANDER).canLeaveOwner)
    }

    @Test
    fun anUnnamedDeckGoesNoFurther() {
        val s = NewDeck().pick(Format.COMMANDER).assign(Owner.MATT)
        assertFalse(s.canLeaveName)
        assertFalse(s.rename("   ").canLeaveName)
        assertTrue(s.rename("Alela").canLeaveName)
    }

    private fun ready() = NewDeck()
        .pick(Format.COMMANDER).assign(Owner.MATT).rename("Alela")
        .setCommander("Alela, Artful Provocateur").type(threeCards)

    @Test
    fun namesMustBeCheckedBeforeSourcing() {
        assertFalse(ready().canLeaveCheck)
        assertTrue(ready().validated(checked()).canLeaveCheck)
    }

    @Test
    fun aFailedCheckBlocksTheRestOfTheWizard() {
        assertFalse(ready().validated(checked(ok = false)).canLeaveCheck)
    }

    /** A slot with no source would be conjured from nothing. */
    @Test
    fun everyCardNeedsASourceBeforeADeckCanBeCreated() {
        val s = ready().validated(checked())
        assertFalse(s.canCreate)
        assertEquals(3, s.undecided.size)

        val sourced = DeckList.cardLines(threeCards).fold(s) { acc, line -> acc.source(line, Source.BULK) }
        assertTrue(sourced.sourcesDecided)
        assertTrue(sourced.canCreate)
        assertTrue(sourced.undecided.isEmpty())
    }

    @Test
    fun whatIsBeingBoughtIsListedSeparately() {
        val s = ready().validated(checked())
            .source("1 Sol Ring", Source.BULK)
            .source("1 Arcane Signet", Source.BUY)
            .source("1 Command Tower", Source.TRANSFER)
        assertEquals(listOf("1 arcane signet"), s.buying)
    }

    /** Editing the list after checking it must not carry the old verdict. */
    @Test
    fun editingTheListThrowsAwayTheCheckAndEverySourcingChoice() {
        val s = ready().validated(checked()).source("1 Sol Ring", Source.BULK)
        val edited = s.type("$threeCards\n1 Opt")
        assertFalse(edited.namesChecked)
        assertTrue(edited.sources.isEmpty())
        assertFalse(edited.canCreate)
    }

    @Test
    fun aDeckIsNotCreatedTwice() {
        val done = DeckList.cardLines(threeCards)
            .fold(ready().validated(checked())) { a, l -> a.source(l, Source.BULK) }
            .finished()
        assertEquals(DeckStep.DONE, done.step)
        assertFalse(done.canCreate)
    }

    @Test
    fun jumpingAheadLandsOnTheLastStepActuallyAnswered() {
        val s = NewDeck().pick(Format.COMMANDER)
        assertEquals(DeckStep.OWNER, s.goTo(DeckStep.REVIEW).step)
    }

    @Test
    fun theSlugIsWhatTheApiWillCallIt() {
        assertEquals("alela-artful-provocateur", NewDeck.slugify("Alela, Artful Provocateur"))
        assertEquals("mono-red", NewDeck.slugify("  Mono   Red  "))
        assertEquals("kardur-doomscourge", NewDeck.slugify("Kardur, Doomscourge"))
        assertEquals("deck-2", NewDeck.slugify("Deck #2"))
    }

    @Test
    fun everyFormatKnowsWhetherItWantsACommander() {
        assertTrue(Format.COMMANDER.wantsCommander)
        assertTrue(Format.BRAWL.wantsCommander)
        assertFalse(Format.MODERN.wantsCommander)
        assertEquals(100, Format.COMMANDER.size)
        assertEquals(Format.PAUPER, Format.of("pauper"))
    }

    // -------------------------------------- taking a suggested spelling

    @Test
    fun aSuggestionFixesTheCommanderWhenThatIsWhatIsWrong() {
        // The commander is its own field, so a correction that only
        // rewrote the list did nothing — and a button that does
        // nothing reads as broken, not as "it is kept elsewhere".
        val d = NewDeck(commander = "Kardur Doomscourge", list = "1 Sol Ring\n1 Opt")
            .correct("Kardur Doomscourge", "Kardur, Doomscourge")
        assertEquals("Kardur, Doomscourge", d.commander)
        assertEquals("1 Sol Ring\n1 Opt", d.list, "the list was not the problem")
    }

    @Test
    fun andFixesTheListWhenThatIsWhereItIs() {
        val d = NewDeck(commander = "Alela", list = "1 Sol Ring\n1 Lighting Bolt\n1 Opt")
            .correct("Lighting Bolt", "Lightning Bolt")
        assertEquals("1 Sol Ring\n1 Lightning Bolt\n1 Opt", d.list)
        assertEquals("Alela", d.commander)
    }

    @Test
    fun andBothWhenTheCardIsInBoth() {
        val d = NewDeck(commander = "Kardur Doomscourge", list = "1 Kardur Doomscourge\n1 Opt")
            .correct("Kardur Doomscourge", "Kardur, Doomscourge")
        assertEquals("Kardur, Doomscourge", d.commander)
        assertEquals("1 Kardur, Doomscourge\n1 Opt", d.list)
    }

    @Test
    fun aQuantityIsKeptAndOnlyTheNameChanges() {
        val d = NewDeck(list = "4 Lighting Bolt").correct("Lighting Bolt", "Lightning Bolt")
        assertEquals("4 Lightning Bolt", d.list)
    }

    @Test
    fun aNameInsideAnotherNameIsLeftAlone() {
        // "Bolt" must not rewrite the middle of "Lightning Bolt", or
        // taking one suggestion quietly breaks another line.
        val d = NewDeck(list = "1 Lightning Bolt\n1 Bolt").correct("Bolt", "Bolt Bend")
        assertEquals("1 Lightning Bolt\n1 Bolt Bend", d.list)
    }

    @Test
    fun takingASuggestionMeansCheckingAgain() {
        // The check that produced it was about the old spelling.
        val d = NewDeck(commander = "Kardur Doomscourge", checked = Validation(checked = 2, unknown = 1))
            .correct("Kardur Doomscourge", "Kardur, Doomscourge")
        assertNull(d.checked)
        assertFalse(d.canLeaveCheck)
    }
}
