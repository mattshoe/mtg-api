package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Starting a deck is the third answer to the entry wizard's first
 * question.
 *
 * Matt: "On the entry screen, we need a new option 'new deck' that
 * launches the new deck flow. Then get rid of the one on the decks
 * list page."
 *
 * Which answer was given is `MassEntry`'s to hold, not a screen's, so
 * that the button's label, whether Continue is pressable and where
 * Continue goes are one set of rules rather than one set per app.
 *
 * `canLeaveWhich` deliberately does **not** change. It gates the
 * list path, and the list path needs a direction — `ListStep` reads
 * `direction!!`. Picking the deck wizard satisfies the first
 * question without satisfying that, which is what `canContinue`
 * says and `canLeaveWhich` must keep refusing.
 */
class NewDeckFromEntryTest {

    @Test
    fun nothingIsChosenToBeginWith() {
        val fresh = MassEntry()
        assertFalse(fresh.startingADeck)
        assertFalse(fresh.canContinue, "the first question starts with an answer in it")
    }

    @Test
    fun pickingTheDeckWizardIsAnAnswer() {
        val s = MassEntry().startADeck()
        assertTrue(s.startingADeck)
        assertTrue(s.canContinue)
    }

    @Test
    fun pickingTheDeckWizardDoesNotOpenTheListPath() {
        val s = MassEntry().startADeck()
        assertFalse(s.canLeaveWhich, "a deck has no direction, so the list step must stay shut")
        assertEquals(Step.WHICH, s.goTo(Step.LIST).step, "it walked into the list with no direction")
    }

    @Test
    fun theThreeAnswersAreExclusive() {
        val deck = MassEntry().choose(Direction.ADD).startADeck()
        assertFalse(deck.canLeaveWhich, "a direction survived picking the deck wizard")
        val adding = MassEntry().startADeck().choose(Direction.REMOVE)
        assertFalse(adding.startingADeck, "the deck wizard survived picking a direction")
        assertTrue(adding.canLeaveWhich)
    }

    @Test
    fun aDirectionStillLeavesByTheListPath() {
        val s = MassEntry().choose(Direction.ADD)
        assertTrue(s.canContinue)
        assertFalse(s.startingADeck)
        assertEquals(Step.LIST, s.goTo(Step.LIST).step)
    }

    @Test
    fun startingOverForgetsTheDeckWizardToo() {
        assertFalse(MassEntry().startADeck().again().startingADeck)
    }

    // ------------------------------------------- back, inside the deck wizard

    @Test
    fun theDeckWizardKnowsWhatIsBehindIt() {
        val s = NewDeck().pick(Format.COMMANDER).goTo(DeckStep.NAME)
        assertEquals(DeckStep.FORMAT, s.previousStep)
        assertEquals(null, NewDeck().previousStep, "the first step has something behind it")
    }

    @Test
    fun theStepBehindSkipsTheCommanderWhenTheFormatHasNone() {
        // `steps` already drops the commander for a sixty-card deck,
        // and Back has to walk the same list or it stops on a step
        // that is not in the wizard.
        val s = NewDeck().pick(Format.MODERN).rename("Burn").goTo(DeckStep.CARDS)
        assertEquals(DeckStep.CARDS, s.step, "the fixture never reached the card list")
        assertEquals(DeckStep.NAME, s.previousStep)
    }

    @Test
    fun theDeckWizardDoesNotClampAStepBack() {
        // A guard, not a fix: `reachable` here only ever asks about
        // answers given *before* a step, so emptying the card box
        // could never pin you the way the entry wizard's clamp did.
        // Worth pinning anyway, because the rule is now written down
        // rather than true by accident.
        val s = NewDeck().pick(Format.COMMANDER).rename("Alela")
            .setCommander("Alela, Artful Provocateur")
            .type("1 Sol Ring").goTo(DeckStep.CHECK).type("")
        assertEquals(DeckStep.CHECK, s.step, "the fixture is not where this test thinks")
        assertEquals(DeckStep.NAME, s.goTo(DeckStep.NAME).step, "back was clamped forward")
        assertEquals(DeckStep.CARDS, s.goTo(DeckStep.CARDS).step, "back was clamped forward")
    }

    @Test
    fun theBackGestureStepsTheDeckWizardRatherThanClosingIt() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
            .navigate(View.ENTRY)
            .opening(Overlay.NEW_DECK)
            .let { it.copy(newDeck = it.newDeck.pick(Format.COMMANDER).goTo(DeckStep.NAME)) }
        val back = assertNotNull(s.back())
        assertTrue(Overlay.NEW_DECK in back.overlays, "one press threw the whole wizard away")
        assertEquals(DeckStep.FORMAT, back.newDeck.step)
    }

    @Test
    fun theBackGestureClosesTheDeckWizardFromItsFirstStep() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
            .navigate(View.ENTRY)
            .opening(Overlay.NEW_DECK)
        assertFalse(Overlay.NEW_DECK in assertNotNull(s.back()).overlays)
    }
}
