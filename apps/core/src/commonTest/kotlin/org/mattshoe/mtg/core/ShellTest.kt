package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Routing and the lock.
 *
 * Which views exist and which need a token is one answer, not two —
 * getting that apart is how a build ends up with an admin page reachable
 * while locked, or a screen one platform does not have.
 */
class ShellTest {

    @Test
    fun theViewsAndWhichOfThemAreGated() {
        assertEquals(7, View.entries.size)
        assertEquals(
            listOf(View.ENTRY, View.ADMIN, View.LOGS),
            View.entries.filter { it.gated },
        )
        // A card is a destination, not a place the menu offers.
        assertEquals(listOf(View.CARD), View.entries.filterNot { it.inNav })
    }

    @Test
    fun aRouteParses() {
        assertEquals(View.STATS, Route.parse("#/stats").view)
        assertEquals("matt", Route.parse("#/stats/matt").rest)
        assertEquals("q=bolt", Route.parse("#/search?q=bolt").query)
        assertEquals("matt", Route.parse("#/stats/matt?x=1").rest)
        assertEquals("x=1", Route.parse("#/stats/matt?x=1").query)
    }

    /** A stale bookmark should land somewhere useful, not on nothing. */
    @Test
    fun anUnknownViewIsTheDefault() {
        assertEquals(View.LIBRARY, Route.parse("#/nonsense").view)
        assertEquals(View.LIBRARY, Route.parse("").view)
        assertEquals(View.LIBRARY, Route.parse(null).view)
    }

    @Test
    fun theOldAddAndRemoveNamesLandOnTheWizard() {
        assertEquals(View.ENTRY, Route.parse("#/add").view)
        assertEquals(View.ENTRY, Route.parse("#/remove").view)
    }

    @Test
    fun aRouteRoundTrips() {
        listOf("#/search", "#/stats/matt", "#/search?q=bolt", "#/decks/alela").forEach {
            assertEquals(it, Route.parse(it).toHash())
        }
    }

    @Test
    fun lockedHidesTheAdminViewsEntirely() {
        val locked = Admin()
        assertFalse(locked.unlocked)
        assertFalse(locked.reachable(View.ENTRY))
        assertFalse(locked.reachable(View.LOGS))
        assertTrue(locked.reachable(View.LIBRARY))
        assertEquals(3, locked.visible.size)
    }

    @Test
    fun signedInShowsTheFour() {
        // Four, not five: the log needs a role, and a fresh account
        // has none. Matt hands those out.
        val me = Admin().signIn(Account(key = "e7de0cb1"), "s")
        assertTrue(me.unlocked)
        assertEquals(4, me.visible.size)
        assertFalse(me.reachable(View.LOGS))
        assertTrue(me.reachable(View.ENTRY))
    }

    @Test
    fun andAnOperatorSeesTheAdminHalfAsWell() {
        val op = Admin().signIn(Account(key = "e7de0cb1", role = Role.ADMIN), "s")
        assertEquals(6, op.visible.size)
        assertTrue(op.reachable(View.LOGS))
        assertTrue(op.reachable(View.ADMIN))
    }

    /** A bookmark to a gated view while locked must not render a dead shell. */
    @Test
    fun aGatedRouteBouncesWhileLocked() {
        assertEquals(View.LIBRARY, Admin().settle().land(Route.parse("#/entry")).view)
        assertEquals(View.ENTRY, Admin().signIn(Account(key = "e7de0cb1"), "s").land(Route.parse("#/entry")).view)
    }

    @Test
    fun bouncingKeepsTheQueryStringSoNothingTypedIsLost() {
        assertEquals("q=bolt", Admin().settle().land(Route.parse("#/entry?q=bolt")).query)
    }

    @Test
    fun aTokenWithNoAccountBehindItIsNotUnlocked() {
        // It used to be the whole of being unlocked: a shared password
        // that could write to anybody's cards. Only `signIn` sets a
        // token now, so one arriving any other way proves nothing.
        assertFalse(Admin("").unlocked)
        assertFalse(Admin("0.abc").unlocked)
    }

    @Test
    fun signingOutForgetsTheSession() {
        assertFalse(Admin().signIn(Account(key = "e7de0cb1"), "s").signOut().unlocked)
    }

    /**
     * Signing out only ever cleared the copy in memory — reload the
     * page or restart the app and the session in `localStorage` or
     * `SharedPreferences` brought you right back in. Both platforms now
     * route every write through `AdminToken.sync`, so this is the test
     * that the *persisted* copy is gone, not just the flag in memory.
     */
    @Test
    fun signingOutClearsWhatWasStoredNotJustTheFlag() {
        val store = Store.inMemory()
        val anon = Admin()
        val signedIn = anon.signIn(Account(key = "e7de0cb1"), "0.abc")
        AdminToken.sync(store, anon, signedIn)
        assertEquals("0.abc", store.get(AdminToken.KEY), "signing in should have written the session")

        val out = signedIn.signOut()
        AdminToken.sync(store, signedIn, out)
        assertEquals(
            null,
            store.get(AdminToken.KEY),
            "the stored token should be gone, not merely the in-memory one",
        )
    }

    @Test
    fun aTokenNeverWrittenRestoresAsNull() {
        assertEquals(null, AdminToken.restore(Store.inMemory()))
    }

    @Test
    fun syncIgnoresAWriteThatDidNotTouchTheToken() {
        // Diffing old against new, not writing on every call: a state
        // update that leaves the token alone — opening a dialog,
        // navigating — must not disturb what is already stored.
        val store = Store.inMemory()
        val signedIn = Admin().signIn(Account(key = "e7de0cb1"), "0.abc")
        AdminToken.sync(store, Admin(), signedIn)
        AdminToken.sync(store, signedIn, signedIn.copy(settled = true))
        assertEquals("0.abc", store.get(AdminToken.KEY))
    }
}

// `UnlockInFlightTest` was here — four tests over "the password is in
// flight, do not send it again". There is no password to send: you
// sign in with Google, and the one button that starts it leaves the
// page.
