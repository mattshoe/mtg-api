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
    fun theSixViewsAndWhichOfThemAreGated() {
        assertEquals(6, View.entries.size)
        assertEquals(
            listOf(View.ENTRY, View.LOGS),
            View.entries.filter { it.gated },
        )
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
        assertEquals(4, locked.visible.size)
    }

    @Test
    fun unlockedShowsThemAll() {
        val open = Admin().unlock("0.abc")
        assertTrue(open.unlocked)
        assertEquals(6, open.visible.size)
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
}
