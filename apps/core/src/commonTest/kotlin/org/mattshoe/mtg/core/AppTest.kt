package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app as a whole: where a tap lands, and what a screen needs.
 *
 * Both platforms drive this object, so "what does the Decks tab do when
 * you open it" has one answer rather than two that drift.
 */
class AppTest {

    @Test
    fun itOpensOnTheLibrary() {
        assertEquals(View.LIBRARY, AppState().view)
    }

    @Test
    fun navigatingToAGatedViewWhileLockedLandsSomewhereUsable() {
        assertEquals(View.LIBRARY, AppState().navigate(View.ENTRY).view)
        assertEquals(View.ENTRY, AppState(admin = Admin("t")).navigate(View.ENTRY).view)
    }

    @Test
    fun navigatingClearsAStaleToast() {
        assertNull(AppState().say("done").navigate(View.DECKS).toast)
    }

    /** A share opens the wizard with the list in, and nothing else decided. */
    @Test
    fun aShareOpensTheWizardWithTheListAlreadyInIt() {
        val s = AppState(admin = Admin("t")).withShare("Name,Quantity\nSol Ring,1")
        assertEquals(View.ENTRY, s.view)
        assertEquals(1, s.entry.cardCount, "the header row is not a card")
        assertNull(s.entry.direction)
        // The wizard no longer decides whose collection this lands in:
        // an account owns one, and the server refuses a write to any other.
        assertEquals("Name,Quantity\nSol Ring,1", s.sharedList)
    }

    @Test
    fun aShareWhileLockedStillHoldsTheListRatherThanLosingIt() {
        val s = AppState().withShare("1 Sol Ring")
        assertEquals(View.LIBRARY, s.view, "the wizard is gated")
        assertEquals("1 Sol Ring", s.sharedList, "but the list must survive the bounce")
        assertEquals(1, s.entry.cardCount)
    }

    @Test
    fun aShareIsSpentOnce() {
        assertNull(AppState().withShare("1 Sol Ring").shareUsed().sharedList)
    }

    @Test
    fun eachRouteSaysWhatItNeeds() {
        assertEquals(listOf("cards", "count"), Load.needs(Route(View.LIBRARY)))
        assertEquals(listOf("decks"), Load.needs(Route(View.DECKS)))
        assertEquals(listOf("decks", "deck"), Load.needs(Route(View.DECKS, "alela")))
        assertEquals(listOf("totals"), Load.needs(Route(View.STATS)))
        assertTrue(Load.needs(Route(View.ENTRY)).isEmpty())
    }

    @Test
    fun statsScopeComesOutOfTheRoute() {
        assertNull(Load.scopeFrom("").owner)
        assertEquals("matt", Load.scopeFrom("matt").owner)
        assertEquals("kayla", Load.scopeFrom("kayla").owner)
        // A slug this app has never heard of is still a slug: there is
        // no list of the collections there could be any more, so there
        // is nothing to check it against. The query returns nothing,
        // which is what an empty collection looks like.
        assertEquals("nonsense", Load.scopeFrom("nonsense").owner)
    }

    @Test
    fun theLibraryAsksForItsPageAndItsCountTogether() {
        val (page, count) = Load.library(Library(filters = Filters(q = "bolt")))
        assertTrue(page.sql.contains("LIMIT"))
        assertTrue(count.sql.startsWith("SELECT COUNT(*)"))
        assertEquals(page.params, count.params)
    }

    @Test
    fun lockingWhileOnAGatedViewIsCaughtByLandingAgain() {
        val open = AppState(admin = Admin("t")).navigate(View.LOGS)
        assertEquals(View.LOGS, open.view)
        val locked = open.copy(admin = open.admin.lock())
        assertEquals(View.LIBRARY, locked.navigate(locked.route).view)
    }

    @Test
    fun everyViewIsReachableUnlocked() {
        val s = AppState(admin = Admin("t"))
        View.entries.forEach { assertEquals(it, s.navigate(it).view) }
    }

    @Test
    fun noViewIsLostBetweenTheNavAndTheRouter() {
        assertEquals(View.entries.count { it.inNav }, Admin("t").visible.size)
        assertFalse(Admin().visible.any { it.gated })
    }
}
