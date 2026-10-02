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

    /**
     * Where a copy comes from is a consequence, not a question.
     *
     * This used to assert the opposite: that nothing could be created
     * until somebody had answered "bulk, transfer or buy" for every
     * line. The answers were never sent — `createDeck` has no
     * `sources` field and never had one — so it was a screen of
     * choices that changed nothing, and "buy" described something the
     * app does not do. Replaced deliberately, not relaxed.
     */
    @Test
    fun aCheckedListIsReadyWithNothingElseToAnswer() {
        val s = ready().validated(checked())
        assertTrue(s.canCreate, "a checked list still wanted something answered")
    }

    @Test
    fun whatTheCollectionHoldsComesOutOfBulk() {
        val s = ready().validated(
            Validation(
                ok = true, checked = 3, unknown = 0,
                cards = listOf(
                    NameCheck(name = "Sol Ring", nameNorm = "sol ring", ok = true, source = "collection"),
                    NameCheck(name = "Arcane Signet", nameNorm = "arcane signet", ok = true, source = "scryfall"),
                    NameCheck(name = "Command Tower", nameNorm = "command tower", ok = true, source = "collection"),
                ),
            ),
        )
        val byName = s.plan.associateBy { it.name.lowercase() }
        assertEquals(Source.BULK, byName["sol ring"]?.from)
        assertEquals(Source.BULK, byName["command tower"]?.from)
        assertEquals(Source.ADD, byName["arcane signet"]?.from, "a card nobody owns is added to bulk")
        assertEquals(listOf("Arcane Signet"), s.adding.map { it.name })
    }

    @Test
    fun aCardNobodyHasCheckedYetIsSomethingToAdd() {
        // No verdict means nothing is known to be held, so the honest
        // answer is that it would be added rather than taken.
        assertTrue(ready().plan.all { it.from == Source.ADD })
    }

    @Test
    fun thePlanIsOneLinePerCardWithItsQuantity() {
        val s = ready().type("4 Lightning Bolt\n1 Sol Ring").validated(checked())
        assertEquals(2, s.plan.size)
        assertEquals(4, s.plan.first().qty)
        assertEquals("Lightning Bolt", s.plan.first().name, "the name is shown as it was written")
    }

    @Test
    fun nothingAnywhereOffersToBuyAnything() {
        assertTrue(Source.entries.none { "buy" in it.label.lowercase() }, "something still says buy")
    }

    /** Editing the list after checking it must not carry the old verdict. */
    @Test
    fun editingTheListThrowsAwayTheCheck() {
        val s = ready().validated(checked())
        val edited = s.type("$threeCards\n1 Opt")
        assertFalse(edited.namesChecked)
        assertFalse(edited.canCreate)
    }

    @Test
    fun aDeckIsNotCreatedTwice() {
        val done = ready().validated(checked()).finished()
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

/** A deck is created once, however many times the button is pressed. */
class CreateInFlightTest {

    private fun ready(): NewDeck {
        val s = NewDeck()
            .pick(Format.COMMANDER)
            .assign(Owner.MATT)
            .rename("Test Deck")
            .setCommander("Alela, Cunning Conqueror")
            .type("1 Sol Ring")
            .validated(Validation(checked = 2, unknown = 0, ok = true))
        return s
    }

    @Test
    fun aFinishedWizardOffersToCreate() {
        assertTrue(ready().canCreate, "a complete wizard should be able to create")
    }

    @Test
    fun butNotWhileItIsAlreadyCreating() {
        // The panel showed "Creating…" instead of the button, which
        // is not the same as the button being refused: the press
        // that lands in the frame before the redraw still got through,
        // and every call carries its own idempotency key.
        assertFalse(ready().working("Creating…").canCreate, "it offered to create a second deck")
    }
}
