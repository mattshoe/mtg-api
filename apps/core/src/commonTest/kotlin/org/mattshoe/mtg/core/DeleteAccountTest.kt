package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting an account from its page in Admin Settings.
 *
 * Matt: "i want to be able to delete accounts via admin settings, and
 * all associated database info. deleting an account should NOT have any
 * impact on other users whatsoever."
 *
 * The Worker decides what goes. This is the screen's half: it asks
 * first, one account at a time, never your own, and what it is left
 * showing afterwards — the list without that one person in it, and
 * everybody else exactly as they were.
 */
class DeleteAccountTest {

    private val kayla = Person(key = "k4yy0003", name = "Kayla M")
    private val matt = Person(key = "m4tt0001", name = "Matt", role = Role.ADMIN)
    private val other = Person(key = "zzzz0009", name = "Other")
    private val people = People().loaded(listOf(matt, kayla, other))

    private val admin = Admin().signIn(Account(key = "m4tt0001", name = "Matt", role = Role.ADMIN), "t")

    private fun onKaylasPage() = AppState(
        route = Route(View.ADMIN, kayla.key),
        admin = admin,
        people = people,
        users = UsersState().loaded(
            listOf(
                Collector(matt.key, "Matt", null, 10, 1),
                Collector(kayla.key, "Kayla M", null, 5, 2),
                Collector(other.key, "Other", null, 0, 0),
            ),
        ),
    )

    @Test
    fun anAdminIsOfferedToDeleteSomebodyElse() {
        assertTrue(people.mayDelete(kayla.key, me = matt.key))
    }

    @Test
    fun butNeverTheirOwnAccount() {
        assertFalse(people.mayDelete(matt.key, me = matt.key), "the page offers to delete the account you are signed in as")
    }

    @Test
    fun norSomebodyWhoIsNotOnTheList() {
        assertFalse(people.mayDelete("nobody00", me = matt.key))
    }

    @Test
    fun tappingItAsksBeforeAnythingGoes() {
        val asked = people.askToDelete(kayla.key)
        assertTrue(asked.isConfirmingDelete(kayla.key))
        assertFalse(asked.isConfirmingDelete(other.key), "the question is on somebody else's page too")
        assertNull(asked.deleting, "it started deleting without asking")
        assertEquals(people.rows, asked.rows)
    }

    @Test
    fun theQuestionNamesWhoAndWhatGoes() {
        val ask = People.askDelete(kayla)
        assertTrue("Kayla M" in ask, "the question does not say whose account: $ask")
        assertTrue("cards" in ask && "decks" in ask, "the question does not say what goes with it: $ask")
    }

    @Test
    fun keepingItTakesTheQuestionAwayAndChangesNothing() {
        val kept = people.askToDelete(kayla.key).keepAccount()
        assertNull(kept.confirmingDelete)
        assertNull(kept.deleting)
        assertEquals(people.rows, kept.rows)
    }

    @Test
    fun yesMarksThatOneAccountBusy() {
        val going = people.askToDelete(kayla.key).deletingAccount(kayla.key)
        assertTrue(going.isDeleting(kayla.key))
        assertFalse(going.isDeleting(other.key))
        assertNull(going.confirmingDelete, "the question stayed up over the delete")
    }

    @Test
    fun whenTheServerSaysItIsGoneTheListLosesThatPersonAndNobodyElse() {
        val s = onKaylasPage().copy(people = people.askToDelete(kayla.key).deletingAccount(kayla.key))
        val done = s.accountDeleted(kayla.key)
        assertEquals(listOf(matt, other), done.people.rows, "the list is not everybody but Kayla")
        assertNull(done.people.deleting, "the spinner stayed")
        assertNull(done.people.error)
    }

    @Test
    fun andTheUsersTabLosesThemToo() {
        val done = onKaylasPage().accountDeleted(kayla.key)
        assertEquals(listOf(matt.key, other.key), done.users.rows.map { it.key })
        assertEquals(listOf(10, 0), done.users.rows.map { it.cards }, "somebody else's tile changed")
    }

    @Test
    fun andThePageThatWasTheirsGoesBackToTheList() {
        val done = onKaylasPage().accountDeleted(kayla.key)
        assertEquals(Route(View.ADMIN), done.route)
        assertNull(done.person)
    }

    @Test
    fun andItSaysSo() {
        val done = onKaylasPage().accountDeleted(kayla.key)
        assertEquals("Deleted Kayla M's account", done.toast?.text)
        assertFalse(done.toast?.failed ?: true)
    }

    @Test
    fun aRefusalClearsTheSpinnerKeepsThePersonAndSaysWhy() {
        val s = onKaylasPage().copy(people = people.askToDelete(kayla.key).deletingAccount(kayla.key))
        val refused = s.deleteFailed("that needs the admin role")
        assertNull(refused.people.deleting, "the spinner is still going after a refusal")
        assertEquals(people.rows, refused.people.rows, "a refused delete changed the list")
        assertEquals("that needs the admin role", refused.people.error)
        assertEquals(Route(View.ADMIN, kayla.key), refused.route, "a refusal left the page")
        assertTrue(refused.toast?.failed == true, "a refusal was toasted as a success")
        assertTrue(refused.people.mayDelete(kayla.key, matt.key), "it cannot be tried again")
    }

    @Test
    fun aSecondPressWhileOneIsGoingIsIgnored() {
        val going = people.askToDelete(kayla.key).deletingAccount(kayla.key)
        assertEquals(going, going.askToDelete(other.key), "a second delete was asked while one is going")
    }

    @Test
    fun aDeleteForSomebodyNotOnTheListLeavesItAlone() {
        val done = onKaylasPage().accountDeleted("nobody00")
        assertEquals(people.rows, done.people.rows)
        assertEquals(3, done.users.rows.size)
    }
}
