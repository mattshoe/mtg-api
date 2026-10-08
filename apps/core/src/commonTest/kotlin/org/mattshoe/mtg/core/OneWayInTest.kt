package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * There is one way in, and it is your account.
 *
 * Matt: "Why the FUCK would you have log in AND sign in with Google?!
 * [...] There is no more fucking admin login!!!! You just log into
 * your FUCKING ACCOUNT!!! And i decide who gets the fucking admin
 * permissions!!!!!! NOBODY GETS FUCKING ADMIN PERMISSIONS!!!!!! YOU
 * JUST GET TO MODIFY YOUR OWN FUCKING CARDS BY DEFAULT!!!!!"
 *
 * The shared password predates accounts. It was one secret that could
 * write to anybody's cards, which is the thing accounts replaced, and
 * leaving it beside the sign-in left the app offering two ways to be
 * somebody — one of them a way to be *everybody*. It is gone from both
 * apps.
 *
 * It is not gone from the Worker: `scripts/backup.py`,
 * `refresh_prices.py` and `tags.mjs` are machines with no account
 * to sign into, and `ADMIN_PASSWORD` is how they get in. A machine
 * credential is not a login screen.
 *
 * What is left is `role`, which is Matt's to hand out — nobody has it
 * by default, and having none of it still lets you do the only thing
 * an account is for: your own cards.
 */
class OneWayInTest {

    private val me = Account(key = "e7de0cb1", name = "Matt")
    private val operator = me.copy(role = "admin")

    @Test
    fun aFreshAccountIsNobodySpecial() {
        assertEquals("user", Account(key = "bprh3d2s").role)
        assertFalse(Account(key = "bprh3d2s").isOperator)
    }

    @Test
    fun andThatIsEnoughToEditYourOwnCollection() {
        // The whole of what an account buys you, with no role at all.
        assertTrue(me.owns("e7de0cb1"))
        assertFalse(me.owns("bprh3d2s"))
    }

    @Test
    fun andTheAdminRoleBuysYouEverybodyElses() {
        // Which is what the role is for, once Matt hands it out —
        // `RolesTest` is where the role system itself lives.
        assertTrue(operator.owns("e7de0cb1"))
        assertTrue(operator.owns("bprh3d2s"))
    }

    @Test
    fun beingSignedInIsTheOnlyWayToBeAbleToChangeAnything() {
        assertFalse(Admin().unlocked, "a signed-out app offered to edit")
        assertTrue(Admin().signIn(me, "s").unlocked)
    }

    @Test
    fun aSessionIsNotAPassword() {
        // `token` carries the session, which only `signIn` can set.
        // Handing one to the constructor used to be how the password
        // got in, and it unlocked the whole app for anybody.
        assertFalse(Admin(token = "0.abcdef").unlocked, "a bare token still unlocks the app")
        assertNull(Admin(token = "0.abcdef").account)
    }

    @Test
    fun entryNeedsAnAccountAndNothingMore() {
        assertFalse(Admin().reachable(View.ENTRY))
        assertTrue(Admin().signIn(me, "s").reachable(View.ENTRY), "a signed-in user cannot enter cards")
    }

    @Test
    fun theServerLogNeedsARoleMattHandedOut() {
        // Signed in is not enough: this one is about running the
        // server, not about owning cards.
        assertFalse(Admin().signIn(me, "s").reachable(View.LOGS), "an ordinary account reached the server log")
        assertTrue(Admin().signIn(operator, "s").reachable(View.LOGS))
    }

    @Test
    fun soTheProfileOffersTheAdminHalfOnlyToAnOperator() {
        assertEquals(emptyList(), Admin().signIn(me, "s").behindProfile)
        assertEquals(
            listOf(View.ADMIN, View.LOGS),
            Admin().signIn(operator, "s").behindProfile,
        )
        assertEquals(emptyList(), Admin().behindProfile, "a stranger was offered the server log")
    }

    @Test
    fun theBarIsThreeUntilYouAreSomebodyAndFourAfter() {
        assertEquals(listOf(View.LIBRARY, View.DECKS, View.STATS), Admin().bar)
        assertEquals(
            listOf(View.LIBRARY, View.DECKS, View.STATS, View.ENTRY),
            Admin().signIn(me, "s").bar,
        )
    }

    @Test
    fun signingOutTakesTheSessionWithIt() {
        val out = Admin().signIn(me, "s").signOut()
        assertNull(out.account)
        assertNull(out.token, "the session outlived the sign-out")
        assertFalse(out.unlocked)
        assertTrue(out.settled, "the page hung after a sign-out")
    }

    @Test
    fun aGatedRouteBouncesWhenThereIsNobodyToReachIt() {
        assertEquals(View.LIBRARY, Admin().settle().land(Route(View.ENTRY)).view)
        assertEquals(View.LIBRARY, Admin().signIn(me, "s").land(Route(View.LOGS)).view)
        assertEquals(View.LOGS, Admin().signIn(operator, "s").land(Route(View.LOGS)).view)
    }

    @Test
    fun aGatedRouteIsHeldRatherThanBouncedBeforeTheAnswerIsIn() {
        // A reload of `#/entry` asks the server who this is, and that
        // is a round trip. Bouncing on the way to the answer put you
        // on the Library every time you opened a bookmark, which is
        // the same mistake as fetching before the answer: an
        // unanswered question is not a "no".
        val waiting = Admin()
        assertFalse(waiting.settled)
        assertEquals(View.ENTRY, waiting.land(Route(View.ENTRY)).view, "it bounced before it knew")
        // And once the answer is in, it bounces.
        assertEquals(View.LIBRARY, waiting.settle().land(Route(View.ENTRY)).view)
    }

    @Test
    fun thereIsNoUnlockOverlayLeftToOpen() {
        // The password box was an overlay, and an overlay nothing can
        // open is a back-gesture step nothing can close.
        assertFalse(Overlay.entries.any { it.name == "UNLOCK" }, Overlay.entries.toString())
    }
}
