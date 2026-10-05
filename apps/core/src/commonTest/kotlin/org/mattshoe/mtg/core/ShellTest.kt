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
        assertEquals(6, View.entries.size)
        assertEquals(
            listOf(View.ENTRY, View.LOGS),
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
    fun unlockedShowsThemAll() {
        val open = Admin().unlock("0.abc")
        assertTrue(open.unlocked)
        assertEquals(5, open.visible.size)
        assertTrue(open.reachable(View.LOGS))
    }

    /** A bookmark to a gated view while locked must not render a dead shell. */
    @Test
    fun aGatedRouteBouncesWhileLocked() {
        assertEquals(View.LIBRARY, Admin().land(Route.parse("#/entry")).view)
        assertEquals(View.ENTRY, Admin().unlock("t").land(Route.parse("#/entry")).view)
    }

    @Test
    fun bouncingKeepsTheQueryStringSoNothingTypedIsLost() {
        assertEquals("q=bolt", Admin().land(Route.parse("#/entry?q=bolt")).query)
    }

    @Test
    fun anEmptyTokenIsNotUnlocked() {
        assertFalse(Admin("").unlocked)
        assertFalse(Admin("   ").unlocked)
    }

    @Test
    fun lockingForgetsTheToken() {
        assertFalse(Admin().unlock("0.abc").lock().unlocked)
    }

    /**
     * `Admin.lock()` only ever cleared the copy in memory — reload the
     * page or restart the app and the token in `localStorage` or
     * `SharedPreferences` brought you right back in. Both platforms now
     * route every write through `AdminToken.sync`, so this is the test
     * that the *persisted* copy is gone, not just the flag in memory.
     */
    @Test
    fun lockingClearsWhatWasStoredNotJustTheFlag() {
        val store = Store.inMemory()
        val anon = Admin()
        val unlocked = anon.unlock("0.abc")
        AdminToken.sync(store, anon, unlocked)
        assertEquals("0.abc", store.get(AdminToken.KEY), "unlocking should have written the token")

        val locked = unlocked.lock()
        AdminToken.sync(store, unlocked, locked)
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
        val unlocked = Admin().unlock("0.abc")
        AdminToken.sync(store, Admin(), unlocked)
        AdminToken.sync(store, unlocked, unlocked.tries())
        assertEquals("0.abc", store.get(AdminToken.KEY))
    }
}

/** The password goes to the server once per press, not once per frame. */
class UnlockInFlightTest {

    @Test
    fun aLockedAdminWillTryAPassword() {
        assertTrue(Admin().canTry, "it refused to try at all")
    }

    @Test
    fun butNotASecondTimeWhileTheFirstIsOut() {
        assertFalse(Admin().tries().canTry, "it offered to send the password again")
    }

    @Test
    fun aTokenBackEndsTheAttempt() {
        assertFalse(Admin().tries().unlock("t").trying, "it is still saying it is trying")
    }

    @Test
    fun soDoesABadPassword() {
        assertTrue(Admin().tries().gaveUp().canTry, "a wrong password locked the button out for good")
    }
}
