package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One attempt at the password, from the state's side.
 *
 * `ShellTest` covers the happy walk — try, unlock, give up. What is
 * left is every way the attempt can end badly: a refusal, a lock
 * pressed mid-flight, a server that answers with nothing. Each of
 * those has to leave the dialog usable, because the alternative is a
 * button that is dead until the page is reloaded.
 */
class AdminAttemptTest {

    /** Two presses inside a frame are still one attempt. */
    @Test
    fun askingTwiceIsStillOneAttempt() {
        val once = Admin().tries()
        assertEquals(once, once.tries(), "the second press changed the attempt")
        assertFalse(once.tries().canTry)
    }

    /** An attempt does not throw away a token that is already good. */
    @Test
    fun anAttemptKeepsTheTokenItAlreadyHas() {
        assertEquals("0.abc", Admin("0.abc").tries().token)
        assertTrue(Admin("0.abc").tries().unlocked, "it locked itself to ask for a password")
    }

    /** Locking mid-flight is not a state you can get stuck in. */
    @Test
    fun lockingDuringAnAttemptStillLeavesItAskable() {
        val locked = Admin("0.abc").tries().lock()
        assertFalse(locked.unlocked)
        assertFalse(locked.trying, "it is still waiting on an answer it threw away")
        assertTrue(locked.canTry, "the password could never be offered again")
    }

    /** A refusal hands back no token and no claim to one. */
    @Test
    fun givingUpGrantsNothing() {
        val refused = Admin().tries().gaveUp()
        assertEquals(null, refused.token)
        assertFalse(refused.unlocked)
        assertFalse(refused.trying)
    }

    /**
     * A server that answers with an empty token is a refusal, not an
     * unlock — but the attempt is over either way, so the dialog is
     * usable again rather than stuck saying "Unlocking…".
     */
    @Test
    fun anEmptyTokenIsNotAnUnlock() {
        val answered = Admin().tries().unlock("")
        assertFalse(answered.unlocked, "a blank token let the admin screens through")
        assertFalse(answered.trying)
        assertTrue(answered.canTry, "a blank token left the button dead")
    }

    /** A gated screen stays out of reach for the whole of an attempt. */
    @Test
    fun theGateHoldsWhileTheAttemptIsOut() {
        val trying = Admin().tries()
        assertFalse(trying.reachable(View.ENTRY), "a gated view opened on hope")
        assertEquals(View.DEFAULT, trying.land(Route(View.LOGS)).view)
        assertTrue(trying.visible.none { it.gated }, "a gated tab appeared mid-attempt")
    }
}
