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
        val s = NewDeck().pick(Format.COMMANDER).rename("Alela")
        assertFalse(s.canLeaveCommander)
        assertTrue(s.setCommander("Alela, Artful Provocateur").canLeaveCommander)
    }

    @Test
    fun aFormatWithoutACommanderSkipsStraightPastIt() {
        val s = NewDeck().pick(Format.STANDARD).rename("Mono Red")
        assertTrue(s.canLeaveCommander, "Standard must not demand a commander")
    }

    @Test
    fun aDeckNeedsANameBeforeItGoesAnywhere() {
        // It used to need an owner first, which is the step that
        // went: a deck belongs to the collection you are in.
        assertFalse(NewDeck().pick(Format.COMMANDER).canLeaveName)
    }

    @Test
    fun anUnnamedDeckGoesNoFurther() {
        val s = NewDeck().pick(Format.COMMANDER)
        assertFalse(s.canLeaveName)
        assertFalse(s.rename("   ").canLeaveName)
        assertTrue(s.rename("Alela").canLeaveName)
    }

    private fun ready() = NewDeck()
        .pick(Format.COMMANDER).rename("Alela")
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
        assertEquals(DeckStep.NAME, s.goTo(DeckStep.REVIEW).step)
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

    // ------------------------------------------- needsCommander, by format

    @Test
    fun noFormatChosenYetNeedsNoCommander() {
        // A fresh wizard must not read as "wants a commander" before
        // anybody has picked a format at all.
        assertFalse(NewDeck().needsCommander)
    }

    @Test
    fun aCommanderFormatNeedsOneAndANonCommanderFormatDoesNot() {
        assertTrue(NewDeck().pick(Format.COMMANDER).needsCommander)
        assertTrue(NewDeck().pick(Format.OATHBREAKER).needsCommander)
        assertFalse(NewDeck().pick(Format.STANDARD).needsCommander)
    }

    @Test
    fun cardsCannotBeLeftWithAnEmptyListEvenWhenEverythingElseIsAnswered() {
        // canLeaveCards is canLeaveCommander with one more condition
        // (cardCount > 0): a commander-less format must still refuse
        // an empty list, not wave it through because the commander
        // gate was the only one it remembers to check.
        val noCards = NewDeck().pick(Format.STANDARD).rename("Mono Red")
        assertFalse(noCards.canLeaveCards, "an empty list was treated as a complete deck")
        assertTrue(noCards.type("1 Sol Ring").canLeaveCards)
    }

    @Test
    fun cardsCannotBeLeftWithoutACommanderEvenWithCardsTyped() {
        val noCommander = NewDeck().pick(Format.COMMANDER).rename("Alela").type("1 Sol Ring")
        assertFalse(noCommander.canLeaveCards, "cards were reachable before the commander step passed")
    }

    @Test
    fun onlyACardTheCollectionActuallyVouchesForCountsAsHeld() {
        // `held` requires ok == true. A card that failed its own check
        // must not be read as "in bulk" just because something upstream
        // tagged its source as the collection by mistake.
        val s = ready().type("1 Sol Ring").validated(
            Validation(
                ok = false, checked = 1, unknown = 1,
                cards = listOf(
                    NameCheck(name = "Sol Ring", nameNorm = "sol ring", ok = false, source = "collection"),
                ),
            ),
        )
        assertEquals(Source.ADD, s.plan.single().from, "a failed check was read as something owned")
    }

    @Test
    fun failingClearsBusyAndRecordsWhatWentWrong() {
        val s = ready().working("Creating…").failed("the server is down")
        assertNull(s.busy)
        assertEquals("the server is down", s.error)
    }

    @Test
    fun theInstanceSlugIsTheSameRuleAsTheStaticOne() {
        // Screens read `deck.slug`, not `NewDeck.slugify(deck.name)` —
        // if the instance property ever drifted from the companion
        // function, the preview address and the one the server gives
        // the deck would quietly disagree.
        val s = NewDeck(name = "Kardur, Doomscourge")
        assertEquals(NewDeck.slugify("Kardur, Doomscourge"), s.slug)
        assertEquals("kardur-doomscourge", s.slug)
    }
}

/** A deck is created once, however many times the button is pressed. */
class CreateInFlightTest {

    private fun ready(): NewDeck {
        val s = NewDeck()
            .pick(Format.COMMANDER)
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

/**
 * Taking the first card of a pasted list as the commander.
 *
 * Every decklist export puts it first, so asking somebody to retype a
 * name the list already has is work nobody should be asked to redo.
 */
class CommanderFromListTest {

    @Test
    fun theFirstCardMovesIntoTheCommanderBoxAndOutOfTheList() {
        val s = NewDeck(list = "1 Alela, Artful Provocateur\n1 Sol Ring\n1 Opt").commanderFromList()
        assertEquals("Alela, Artful Provocateur", s.commander)
        assertEquals("1 Sol Ring\n1 Opt", s.list)
    }

    @Test
    fun takingTheCommanderOffTheListInvalidatesWhateverWasChecked() {
        // The check that passed was about a list that still had the
        // commander's line in it.
        val s = NewDeck(list = "1 Alela\n1 Sol Ring", checked = Validation(checked = 2, unknown = 0, ok = true))
            .commanderFromList()
        assertNull(s.checked)
        assertFalse(s.canLeaveCheck)
    }

    @Test
    fun theSuggestionBoxShowsTheNameThatWasJustTakenOffTheList() {
        val s = NewDeck(list = "1 Alela, Artful Provocateur\n1 Sol Ring").commanderFromList()
        assertEquals("Alela, Artful Provocateur", s.hint.term)
    }

    @Test
    fun anEmptyListHasNoFirstCardSoNothingMoves() {
        // Nothing to promote, and nothing to break either: pressing
        // the button on an empty box must be a no-op, not a crash.
        val s = NewDeck(commander = "Alela", list = "")
        assertEquals(s, s.commanderFromList())
    }

    @Test
    fun aListThatIsOnlyCommentsAndHeadersHasNoFirstCardEither() {
        val s = NewDeck(list = "# a note\nCommander:")
        assertEquals(s, s.commanderFromList())
    }
}

/** The suggestion box for the commander field, and what typing in it does. */
class CommanderHintingTest {

    @Test
    fun aSuggestionMovingThroughTheListUpdatesTheBoxButNotTheCommanderYet() {
        // `hinting` is the list moving under a highlight, not a pick —
        // it must show whatever the completion widget holds without
        // quietly committing a different commander than what was typed.
        val s = NewDeck(commander = "Al").hinting(Completion(term = "Alela, Artful Provocateur"))
        assertEquals("Alela, Artful Provocateur", s.commander)
    }

    @Test
    fun movingTheHintInvalidatesAStaleCheck() {
        val s = NewDeck(commander = "Al", checked = Validation(checked = 1, unknown = 0, ok = true))
            .hinting(Completion(term = "Alela"))
        assertNull(s.checked)
    }
}

/**
 * Every step the wizard can be asked to reach, and what `goTo` does
 * when the step asked for is not one this build of the deck actually
 * has.
 */
class ReachableAndGoToTest {

    @Test
    fun theFormatStepIsAlwaysReachableEvenBeforeAnythingIsAnswered() {
        assertTrue(NewDeck().reachable(DeckStep.FORMAT))
    }

    @Test
    fun theDoneStepIsOnlyReachableOnceTheDeckExists() {
        val s = NewDeck().pick(Format.COMMANDER).rename("Alela")
            .setCommander("Alela, Artful Provocateur").type("1 Sol Ring")
            .validated(Validation(checked = 1, unknown = 0, ok = true))
        assertFalse(s.reachable(DeckStep.DONE), "done was reachable before anything was created")
        assertTrue(s.finished().reachable(DeckStep.DONE))
    }

    @Test
    fun askingForAStepThisFormatDoesNotHaveAndHasNotEarnedLandsOnFormat() {
        // Standard has no COMMANDER step at all (it is filtered out of
        // `steps`), so when it is also not yet reachable the "land on
        // the furthest step actually answered" search has nothing in
        // the list to match against and has to fall all the way back,
        // rather than getting stuck on a step the UI never draws.
        val s = NewDeck().pick(Format.STANDARD)
        assertFalse(s.canLeaveName, "the test needs an unreachable Commander step to prove anything")
        assertEquals(DeckStep.FORMAT, s.goTo(DeckStep.COMMANDER).step)
    }

    @Test
    fun aStepFilteredOutOfThisFormatButAlreadyEarnedIsStillHandedBack() {
        // `reachable` is a chain of gates, not a check against `steps`,
        // so once the name step is passed, COMMANDER answers true for
        // Standard too even though Standard never draws that screen.
        // Worth pinning down: it means `goTo` alone will not stop a
        // caller from landing on a step this format does not have —
        // only reading from `steps` does.
        val s = NewDeck().pick(Format.STANDARD).rename("Mono Red")
        assertTrue(s.reachable(DeckStep.COMMANDER))
        assertEquals(DeckStep.COMMANDER, s.goTo(DeckStep.COMMANDER).step)
    }

    @Test
    fun askingForDoneBeforeTheDeckExistsFallsAllTheWayBackToFormat() {
        // DONE is filtered out of `steps` itself, so when it is not
        // yet reachable the usual "land on the furthest answered step"
        // search has nothing to search — it has nowhere to land but
        // the very start, rather than getting stuck mid-wizard.
        val s = ready().validated(Validation(checked = 1, unknown = 0, ok = true))
        assertFalse(s.reachable(DeckStep.DONE))
        assertEquals(DeckStep.FORMAT, s.goTo(DeckStep.DONE).step)
    }

    @Test
    fun onceTheDeckExistsGoingToDoneLandsOnDone() {
        val done = ready().validated(Validation(checked = 1, unknown = 0, ok = true)).finished()
        assertEquals(DeckStep.DONE, done.goTo(DeckStep.DONE).step)
    }

    @Test
    fun goingToAStepThatIsActuallyReachableLandsExactlyThere() {
        val s = NewDeck().pick(Format.COMMANDER)
        assertEquals(DeckStep.NAME, s.goTo(DeckStep.NAME).step)
    }

    @Test
    fun goingBackwardsIsAlwaysAllowed() {
        // Every earlier step was, by definition, already answered to
        // get this far, so going back can never be refused.
        val s = ready().validated(Validation(checked = 1, unknown = 0, ok = true))
        assertEquals(DeckStep.FORMAT, s.goTo(DeckStep.FORMAT).step)
        assertEquals(DeckStep.NAME, s.goTo(DeckStep.NAME).step)
    }

    private fun ready() = NewDeck()
        .pick(Format.COMMANDER).rename("Alela")
        .setCommander("Alela, Artful Provocateur").type("1 Sol Ring")
}
