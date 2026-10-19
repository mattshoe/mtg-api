package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Browsing anybody's cards.
 *
 * Matt: "i should have a bottom nav option to browse users ... then i
 * can select any user, and browse their cards, basically a copy of the
 * library page, the entire functionality, but i can browse through
 * users to select a collection first".
 */
class UsersTest {

    private val me = Account(key = "mattkey1", name = "Matt")
    private fun signedIn() = AppState(admin = Admin().signIn(me, "s"))

    private fun row(vararg v: Any?) = JsonArray(v.map {
        when (it) {
            null -> JsonNull
            is Number -> JsonPrimitive(it)
            else -> JsonPrimitive(it.toString())
        }
    })

    @Test
    fun usersIsInTheBarForAnybody() {
        assertTrue(View.USERS in Admin().bar, "a stranger's bar has no Users: ${Admin().bar}")
        assertTrue(View.USERS in Admin().signIn(me, "s").bar, "a signed-in bar has no Users")
        assertEquals(View.USERS, Route.parse("#/users").view)
    }

    @Test
    fun theListNeverReadsAnEmailAndLeavesTheServiceAccountsOut() {
        val q = UserQueries.all()
        assertFalse("email" in q.sql, "the user list reads an email address:\n${q.sql}")
        assertTrue("role <> 'agent'" in q.sql, "the intake agent's account is offered as a collection:\n${q.sql}")
        assertTrue(q.params.isEmpty())
    }

    @Test
    fun aTileSaysWhoItIsAndHowMuchTheyHave() {
        val cols = listOf("key", "name", "avatar", "cards", "decks")
        val found = UserQueries.decode(
            cols,
            listOf(row("k1", "Kayla", "https://x/a.png", 1234, 5), row("k2", null, null, 1, 0)),
        )
        assertEquals(listOf("k1", "k2"), found.map { it.key })
        assertEquals("Kayla", found[0].shownName)
        assertEquals("1,234 cards · 5 decks", found[0].blurb)
        assertEquals("K", found[0].initial)
        // No name: the key, the same as every other place an owner is named.
        assertEquals("k2", found[1].shownName)
        assertNull(found[1].avatar)
        assertEquals("1 card · 0 decks", found[1].blurb)
    }

    @Test
    fun theUsersTabAsksForTheListAndATileAsksForThatLibrary() {
        assertEquals(listOf("users"), Load.needs(Route(View.USERS)))
        assertEquals(listOf("cards", "count"), Load.needs(Route(View.USERS, "k1"), collectionKnown = false))
    }

    @Test
    fun openingATileShowsThatPersonsCardsAndNotYours() {
        val s = signedIn().copy(users = UsersState().loaded(listOf(Collector("k1", "Kayla", null, 3, 1))))
            .browseUser("k1")
        assertEquals(Route(View.USERS, "k1"), s.route)
        assertEquals("k1", s.viewing)
        assertEquals("k1", s.scopedLibrary().filters.owner, "the library searched somebody else's cards")
        assertTrue(s.collectionKnown)
        assertFalse(s.canEdit, "the edit buttons are on somebody else's cards")
        assertEquals("Kayla", s.browsed?.shownName)
    }

    @Test
    fun yourOwnLibraryTabIsStillYours() {
        val s = signedIn().browseUser("k1").navigate(View.LIBRARY)
        assertEquals("mattkey1", s.viewing)
        assertEquals("mattkey1", s.scopedLibrary().filters.owner)
    }

    @Test
    fun changingWhoseCardsAreShownForgetsTheRowsOnScreen() {
        val loaded = signedIn().let { it.copy(library = it.library.loaded(emptyList(), 0)) }
        assertTrue(loaded.library.fresh, "the fixture's rows are not current to begin with")
        val other = loaded.browseUser("k1")
        assertFalse(other.library.fresh, "Kayla's library would show Matt's rows without searching")
        assertFalse(other.navigate(View.LIBRARY).library.fresh, "back on your Library, it shows Kayla's rows")
    }

    @Test
    fun theListLoadingAndFailingIsOnThePage() {
        val busy = signedIn().navigate(View.USERS).fetching()
        assertTrue(busy.users.busy)
        val failed = busy.fetchFailed("the server fell over")
        assertFalse(failed.users.busy, "the spinner outlived the failure")
        assertEquals("the server fell over", failed.users.error)
        assertTrue(failed.users.rows.isEmpty())
        // Try again goes back to loading, from the same page.
        assertTrue(failed.fetching().users.busy)
        assertNull(failed.fetching().users.error)
    }

    @Test
    fun aBrowsedLibraryLoadsAndFailsLikeTheLibrary() {
        val s = signedIn().browseUser("k1").fetching()
        assertTrue(s.library.busy)
        assertEquals("nope", s.fetchFailed("nope").library.error)
    }

    @Test
    fun theTilesLeaveOutYourOwnAccount() {
        val list = UsersState().loaded(
            listOf(Collector("k1", "Kayla", null, 3, 1), Collector("mattkey1", "Matt", null, 9, 2)),
        )
        val mine = signedIn().copy(users = list).navigate(View.USERS)
        assertEquals(listOf("k1"), mine.others.rows.map { it.key }, "your own account is a tile on the Users tab")
        // Signed out there is nobody to leave out.
        assertEquals(listOf("k1", "mattkey1"), AppState(users = list).others.rows.map { it.key })
    }

    @Test
    fun withOnlyYouThereIsNobodyToBrowse() {
        val s = signedIn().copy(users = UsersState().loaded(listOf(Collector("mattkey1", "Matt", null, 9, 2))))
        assertTrue(s.others.isEmpty, "the tiles page has only you and does not say it is empty")
    }

    @Test
    fun aPullOnTheListWaitsForIt() {
        assertTrue(signedIn().navigate(View.USERS).refreshed().refreshing)
    }
}
