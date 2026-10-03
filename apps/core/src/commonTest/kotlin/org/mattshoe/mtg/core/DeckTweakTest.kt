package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maintaining a deck one card at a time.
 *
 * What is checked here is the list that goes to the server, because
 * that is the whole mechanism: there is no per-card endpoint, a
 * change is the deck's own list with one line different, and the
 * server diffs it exactly as it diffs a full rewrite.
 */
class DeckTweakTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1, role: String? = null) =
        DeckCard(name, qty, role, qty, nameNorm = name.lowercase())

    private val cards = listOf(
        card("Alela, Cunning Conqueror", role = "commander"),
        card("Sol Ring"),
        card("Counterspell", qty = 2),
        card("Swamp", qty = 10),
    )

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    private fun list(t: DeckTweak) = t.listAfter(cards)

    // ----------------------------------------------------------- adding

    @Test
    fun addingACardPutsItOnTheEnd() {
        val t = DeckTweak.add(deck, "Alela, Cunning Conqueror").picked(bolt).count(1)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp\n1 Lightning Bolt", list(t))
    }

    @Test
    fun addingOneTheDeckAlreadyHoldsIsOneMoreOfIt() {
        // Not a second row. The server would fold them anyway, and a
        // list that says the same card twice reads as a mistake.
        val t = DeckTweak.add(deck, "x").picked(Found(2, "Counterspell", null, null, 4, "matt")).count(2)
        assertEquals("1 Sol Ring\n4 Counterspell\n10 Swamp", list(t))
    }

    @Test
    fun theCommanderIsNeverInTheList() {
        // It travels in its own field, so writing it into the list
        // would make the server see a second copy of it.
        val t = DeckTweak.add(deck, "Alela, Cunning Conqueror").picked(bolt)
        assertTrue("Alela" !in list(t), list(t))
    }

    // --------------------------------------------------------- removing

    @Test
    fun removingACardTakesTheWholeLineOut() {
        val t = DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.REMOVE)
        assertEquals("1 Sol Ring\n10 Swamp", list(t))
    }

    @Test
    fun removingSomethingTheDeckDoesNotHaveChangesNothing() {
        val t = DeckTweak.on(deck, "x", card("Black Lotus"), Tweak.REMOVE)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", list(t))
    }

    // ------------------------------------------------------- how many

    @Test
    fun changingTheCountRewritesThatLineOnly() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY).count(7)
        assertEquals("1 Sol Ring\n2 Counterspell\n7 Swamp", list(t))
    }

    @Test
    fun takingTheCountToNoughtIsARemoval() {
        val t = DeckTweak.on(deck, "x", card("Sol Ring"), Tweak.QUANTITY).count(0)
        assertEquals("2 Counterspell\n10 Swamp", list(t))
    }

    @Test
    fun aCountThatHasNotChangedIsNotReady() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY)
        assertFalse(t.ready, "nothing to do, but the button was live")
        assertTrue(t.count(9).ready)
    }

    // --------------------------------------------------------- swapping

    @Test
    fun aSwapPutsTheNewCardWhereTheOldOneWas() {
        // In place, so a list read top to bottom does not reshuffle
        // every time one card changes.
        val t = DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.SWAP).picked(bolt)
        assertEquals("1 Sol Ring\n2 Lightning Bolt\n10 Swamp", list(t))
    }

    @Test
    fun aSwapTakesAsManyAsItGives() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).picked(bolt)
        assertTrue("10 Lightning Bolt" in list(t), list(t))
    }

    @Test
    fun aSwapCanChangeHowManyAsWell() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).picked(bolt).count(3)
        assertEquals("1 Sol Ring\n2 Counterspell\n3 Lightning Bolt", list(t))
    }

    @Test
    fun swappingIntoACardTheDeckAlreadyHoldsAddsToIt() {
        val t = DeckTweak.on(deck, "x", card("Sol Ring"), Tweak.SWAP)
            .picked(Found(3, "Counterspell", null, null, 4, "matt"))
        assertEquals("3 Counterspell\n10 Swamp", list(t))
    }

    // ------------------------------------------------- what it will say

    @Test
    fun everyChangeSaysWhatItIsInOneLine() {
        assertEquals(
            "Add 2× Lightning Bolt",
            DeckTweak.add(deck, "x").picked(bolt).count(2).summary,
        )
        assertEquals(
            "Counterspell → Lightning Bolt",
            DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.SWAP).picked(bolt).summary,
        )
        assertEquals(
            "Remove 2× Counterspell",
            DeckTweak.on(deck, "x", card("Counterspell", 2), Tweak.REMOVE).summary,
        )
        assertEquals(
            "Swamp: 10 → 7",
            DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY).count(7).summary,
        )
    }

    // ------------------------------------------------------- the gates

    @Test
    fun nothingIsPlannedUntilACardIsNamed() {
        val t = DeckTweak.add(deck, "x")
        assertFalse(t.ready, "an add with no card was ready to go")
        assertTrue(t.picked(bolt).ready)
    }

    @Test
    fun nothingIsWrittenUntilThePlanIsBack() {
        val t = DeckTweak.add(deck, "x").picked(bolt)
        assertFalse(t.canApply, "it would have written without a dry run")
        assertTrue(t.planned(DeckPlan()).canApply)
    }

    @Test
    fun pickingADifferentCardThrowsAwayThePlan() {
        // The plan was about the other card. Applying it would write
        // something nobody approved.
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan())
        assertTrue(t.canApply)
        assertFalse(t.picked(Found(9, "Opt", null, null, 1, "matt")).canApply)
    }

    @Test
    fun changingHowManyThrowsAwayThePlanToo() {
        val t = DeckTweak.add(deck, "x").picked(bolt).count(1).planned(DeckPlan())
        assertFalse(t.count(4).canApply)
    }

    @Test
    fun aChangeThatHasBeenWrittenCannotBeWrittenAgain() {
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan()).finished()
        assertFalse(t.canApply)
        assertTrue(t.saved)
    }

    // ------------------------------------------------ finding the card

    @Test
    fun whatIsOwnedComesFirst() {
        val t = DeckTweak.add(deck, "x").searched(
            listOf(bolt),
            listOf("Lightning Helix", "Lightning Strike"),
        )
        assertEquals(
            listOf("Lightning Bolt", "Lightning Helix", "Lightning Strike"),
            t.found.map { it.name },
        )
        assertEquals(4, t.found[0].qty, "the owned one lost its count")
        assertEquals(0, t.found[1].qty, "a card nobody owns reads as owned")
    }

    @Test
    fun aCardNobodyOwnsCanStillBePutInADeck() {
        // That is what the plan calls "to buy". A finder that only
        // lists owned cards cannot be used to add one.
        val t = DeckTweak.add(deck, "x").searched(emptyList(), listOf("Black Lotus"))
        assertEquals(listOf("Black Lotus"), t.found.map { it.name })
        assertTrue(t.picked(t.found.single()).ready)
    }

    @Test
    fun theSameCardIsNotOfferedTwice() {
        // Scryfall knows every card, including the ones in the
        // collection, so the two lists overlap by definition.
        val t = DeckTweak.add(deck, "x").searched(listOf(bolt), listOf("lightning bolt", "Lightning Helix"))
        assertEquals(listOf("Lightning Bolt", "Lightning Helix"), t.found.map { it.name })
    }
}

/**
 * The two questions underneath `ready`: what kind of change is this,
 * and does it still need a card named before it means anything.
 */
class DeckTweakWhatKindTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1) =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase())

    @Test
    fun addingAndSwappingNeedACardRemovingAndCountingDoNot() {
        assertTrue(DeckTweak.add(deck, "x").needsACard)
        assertTrue(DeckTweak.on(deck, "x", card("Swamp"), Tweak.SWAP).needsACard)
        assertFalse(DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).needsACard)
        assertFalse(DeckTweak.on(deck, "x", card("Swamp"), Tweak.QUANTITY).needsACard)
    }

    @Test
    fun beforeAKindIsPickedItDoesNotNeedACardEither() {
        // `needsACard` is specifically about ADD and SWAP — a sheet
        // that has not even been told what it is doing yet must not
        // read as "waiting on a card name" before it reads as "waiting
        // on a choice".
        assertFalse(DeckTweak.on(deck, "x", card("Swamp")).needsACard)
    }

    @Test
    fun theSheetKnowsWhenItIsStillAskingWhatToDo() {
        val asking = DeckTweak.on(deck, "x", card("Swamp"))
        assertTrue(asking.choosing)
        assertFalse(asking.doing(Tweak.REMOVE).choosing)
    }

    @Test
    fun aSheetStillChoosingIsNeverReady() {
        // Not ADD, REMOVE, SWAP or QUANTITY — `ready`'s `when` has no
        // branch that reads "undecided" as go, so a card opened with
        // no kind chosen must never offer to preview anything.
        assertFalse(DeckTweak.on(deck, "x", card("Swamp")).ready)
    }

    @Test
    fun removingIsReadyAsSoonAsThereIsARowToRemove() {
        assertTrue(DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).ready)
    }

    @Test
    fun removingWithNoRowNamedIsNotReady() {
        // Not reachable through the factory functions — `on` always
        // supplies a subject — but the state shape allows it, and nothing
        // else here stops a REMOVE with nothing to remove from reading
        // as ready.
        val noSubject = DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).copy(subject = null)
        assertFalse(noSubject.ready, "a removal with nothing named was ready to preview")
    }

    @Test
    fun aBusySheetIsNeverReadyEvenWithEverythingElseAnswered() {
        val t = DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).working()
        assertFalse(t.ready, "a change already being sent was offered again")
    }

    @Test
    fun aSavedSheetIsNeverReadyEitherEvenIfReopenedSomehow() {
        val t = DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).copy(saved = true)
        assertFalse(t.ready)
    }

    @Test
    fun aBusyOrSavedSheetCannotApplyEvenWithAPlanInHand() {
        val planned = DeckTweak.add(deck, "x").picked(Found(1, "Opt", null, null, 1, "matt")).planned(DeckPlan())
        assertTrue(planned.canApply)
        assertFalse(planned.working().canApply, "a change already being sent could be applied again")
        assertFalse(planned.copy(saved = true).canApply, "an already-saved change could be applied again")
    }
}

/**
 * The sheet's own lifecycle moves: opening a kind, searching, sending,
 * and failing. None of these are exercised by walking a tweak through
 * to a plan, so each gets its own, direct test.
 */
class DeckTweakLifecycleTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1) =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase())

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    @Test
    fun lookingMarksTheSheetAsSearching() {
        val t = DeckTweak.add(deck, "x").looking()
        assertTrue(t.searching)
    }

    @Test
    fun pickingAKindResetsWhateverTheFinderWasHolding() {
        // Choosing REMOVE after having typed into the ADD finder must
        // not leave a stale term, hit list or pick sitting around for
        // a kind of change that does not use them.
        val wasSearching = DeckTweak.add(deck, "x").typed("lig").searched(listOf(bolt))
        val switched = wasSearching.doing(Tweak.REMOVE)
        assertEquals(Tweak.REMOVE, switched.kind)
        assertEquals("", switched.term)
        assertTrue(switched.found.isEmpty())
        assertFalse(switched.searching)
        assertEquals(null, switched.pick)
    }

    @Test
    fun pickingAKindDropsAnyPlanTheOldKindHadMade() {
        val planned = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan())
        assertTrue(planned.canApply)
        assertFalse(planned.doing(Tweak.REMOVE).canApply, "a plan for Add survived switching to Remove")
    }

    @Test
    fun workingClearsTheLastErrorButNotWhatWasTyped() {
        val t = DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE)
            .failed("network error")
            .working()
        assertTrue(t.busy)
        assertEquals(null, t.error)
        assertEquals("Swamp", t.subject?.name, "the row being acted on was forgotten mid-retry")
    }

    @Test
    fun aFailureDropsThePlanSoApplyCannotFireOnAStalePlan() {
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan()).failed("422: bad request")
        assertFalse(t.canApply)
        assertEquals("422: bad request", t.error)
    }

    @Test
    fun aFailureCanCarryLineLevelErrorsAsWellAsTheHeadline() {
        val t = DeckTweak.add(deck, "x").failed("2 line(s) could not be read", listOf("xx", "yy"))
        assertEquals(listOf("xx", "yy"), t.errors)
    }

    @Test
    fun finishingMarksItSavedAndNoLongerBusy() {
        val t = DeckTweak.add(deck, "x").picked(bolt).planned(DeckPlan()).working().finished()
        assertTrue(t.saved)
        assertFalse(t.busy)
    }
}

/** What each kind of change says about itself, including the cases that never reach the factory functions. */
class DeckTweakSummaryEdgeCasesTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1) =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase())

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    @Test
    fun withNoKindChosenYetTheSummaryNamesTheRowIfThereIsOne() {
        val onARow = DeckTweak.on(deck, "x", card("Swamp"))
        assertEquals("Swamp", onARow.summary)
    }

    @Test
    fun withNoKindAndNoRowTheSummaryIsBlankRatherThanNull() {
        val blank = DeckTweak.on(deck, "x", card("Swamp")).copy(subject = null)
        assertEquals("", blank.summary)
    }

    @Test
    fun anAddWithNoCardPickedYetSaysSoRatherThanShowingBlank() {
        assertEquals("Add a card", DeckTweak.add(deck, "x").summary)
    }

    @Test
    fun aRemoveWithNoRowNamedSaysSoRatherThanShowingBlank() {
        val noSubject = DeckTweak.on(deck, "x", card("Swamp"), Tweak.REMOVE).copy(subject = null)
        assertEquals("Remove a card", noSubject.summary)
    }

    @Test
    fun aQuantityChangeWithNoRowNamedSaysSoRatherThanShowingBlank() {
        val noSubject = DeckTweak.on(deck, "x", card("Swamp"), Tweak.QUANTITY).copy(subject = null)
        assertEquals("Change how many", noSubject.summary)
    }

    @Test
    fun aSwapWithARowChosenButNoNewCardYetNamesTheRowComingOut() {
        val noPickYet = DeckTweak.on(deck, "x", card("Swamp"), Tweak.SWAP)
        assertEquals("Swap out Swamp", noPickYet.summary)
    }

    @Test
    fun aSwapWithOnlyACardPickedAndNoRowYetReadsAsSwapACard() {
        // The arrow form ("X → Y") needs both ends. With only the
        // incoming card chosen, the subject being null takes the whole
        // branch to the same fallback as having picked nothing at all
        // — which is correct, but worth pinning: the half-picked state
        // must not show an arrow pointing from nothing.
        val onlyPick = DeckTweak.on(deck, "x", card("Swamp"), Tweak.SWAP).copy(subject = null, pick = bolt)
        assertEquals("Swap a card", onlyPick.summary)
    }

    @Test
    fun aSwapWithNeitherEndChosenSaysSoRatherThanShowingBlank() {
        val neither = DeckTweak.on(deck, "x", card("Swamp"), Tweak.SWAP).copy(subject = null, pick = null)
        assertEquals("Swap a card", neither.summary)
    }
}

/**
 * `listAfter`'s early-outs: the states a plan is never actually built
 * from in practice (no card named, nothing to act on) but that the
 * function has to not crash on and not quietly corrupt the list for.
 */
class DeckTweakListAfterEdgeCasesTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)

    private fun card(name: String, qty: Int = 1, role: String? = null) =
        DeckCard(name, qty, role, qty, nameNorm = name.lowercase())

    private val cards = listOf(card("Sol Ring"), card("Counterspell", qty = 2), card("Swamp", qty = 10))

    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    @Test
    fun addingWithNoCardPickedLeavesTheListExactlyAsItWas() {
        val t = DeckTweak.add(deck, "x")
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", t.listAfter(cards))
    }

    @Test
    fun removingWithNoRowNamedLeavesTheListExactlyAsItWas() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.REMOVE).copy(subject = null)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", t.listAfter(cards))
    }

    @Test
    fun aQuantityChangeWithNoRowNamedLeavesTheListExactlyAsItWas() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.QUANTITY).copy(subject = null)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", t.listAfter(cards))
    }

    @Test
    fun aSwapWithNoRowNamedLeavesTheListExactlyAsItWas() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).copy(subject = null, pick = bolt)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", t.listAfter(cards))
    }

    @Test
    fun aSwapWithNoCardPickedLeavesTheListExactlyAsItWas() {
        val t = DeckTweak.on(deck, "x", card("Swamp", 10), Tweak.SWAP).copy(pick = null)
        assertEquals("1 Sol Ring\n2 Counterspell\n10 Swamp", t.listAfter(cards))
    }

    @Test
    fun aSwapForACardNotActuallyInTheListStillInsertsTheNewOne() {
        // `indexOf(s.shown)` comes back -1 when the subject named is
        // not one of the rows handed in — stale state, since the list
        // this screen opened from is the one the sheet was given. The
        // new card still has to go in somewhere rather than vanish.
        val notInList = card("Black Lotus", 1)
        val t = DeckTweak.on(deck, "x", notInList, Tweak.SWAP).picked(bolt)
        assertTrue("Lightning Bolt" in t.listAfter(cards), t.listAfter(cards))
        assertTrue("Black Lotus" !in t.listAfter(cards))
    }
}
