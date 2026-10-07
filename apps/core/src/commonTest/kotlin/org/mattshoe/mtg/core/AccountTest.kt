package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who you are, and what that lets you change.
 *
 * Matt: "I want to intuitive the concept of accounts/profiles";
 * "Configure it so that accounts have admin rights by default for
 * their own cards and only their own cards"; "Leverage the profile
 * icon for the account information and log in log out."
 *
 * The password is still here and still works — the nightly job holds
 * one and the phone has nothing else yet — so `Admin` keeps its
 * shape and gains an account beside the token. What changed is what
 * "unlocked" means: signed in, or holding the operator's password.
 *
 * The real rule, "only the collection's owner may edit it", is the
 * server's and is tested there. What an app decides is only whether
 * to draw the button.
 */
class AccountTest {

    private val me = Account(slug = "matt", name = "Matt", avatar = null, role = "user")

    @Test
    fun nobodyIsSignedInToBeginWith() {
        val fresh = Admin()
        assertFalse(fresh.signedIn)
        assertFalse(fresh.unlocked)
        assertEquals(null, fresh.account)
    }

    @Test
    fun signingInUnlocksEditing() {
        val s = Admin().signIn(me)
        assertTrue(s.signedIn)
        assertTrue(s.unlocked, "a signed-in account cannot edit its own collection")
        assertEquals("matt", s.account?.slug)
    }

    // `theOperatorsPasswordStillUnlocksWithoutAnAccount` was here.
    // There is no password in the apps any more: one shared secret
    // that could write to anybody's cards is the thing accounts
    // replaced. `OneWayInTest` is where that is nailed down.

    @Test
    fun theSessionIsTheBearerEveryWriteAlreadySends() {
        // Not a field of its own. `token` is what writes present and
        // what `AdminToken` persists, so an account's session is kept
        // and sent by the code that was doing both already.
        val s = Admin().signIn(me, session = "sess-abc")
        assertEquals("sess-abc", s.token)
        assertTrue(s.unlocked)
    }

    @Test
    fun signingInWithoutASessionLeavesWhateverWasHeld() {
        // The website never sees its session — it is an HttpOnly
        // cookie — so it signs in with an account and no token, and
        // that must not wipe a password somebody is holding.
        assertEquals("pw", Admin(token = "pw").signIn(me).token)
    }

    @Test
    fun signingOutTakesBothAway() {
        val s = Admin().signIn(me, "t").signOut()
        assertFalse(s.signedIn)
        assertFalse(s.unlocked)
        assertEquals(null, s.account)
    }

    @Test
    fun theGatedTabsFollowBeingSignedIn() {
        // Mass Entry writes to a collection, so it appears for an
        // account exactly as it appeared for the password.
        assertTrue(View.ENTRY in Admin().signIn(me).bar)
        assertFalse(View.ENTRY in Admin().bar)
    }

    @Test
    fun theProfileSaysWhoYouAreRatherThanThatYouAreAdmin() {
        assertEquals("Matt", Admin().signIn(me).account?.name)
        assertEquals("matt", Admin().signIn(me).account?.slug)
    }

    @Test
    fun anAccountWithNoNameIsStillCalledSomething() {
        // A Google account can come back with no name on it, and a
        // profile that says nothing at all reads as a failure.
        val nameless = Account(slug = "player-7", name = null, avatar = null, role = "user")
        assertEquals("player-7", Admin().signIn(nameless).shownName)
    }

    @Test
    fun theServerRoleIsNotTheSameAsOwningYourCards() {
        // Every account edits its own collection; the role is for the
        // log and the maintenance job and is nobody's by default.
        assertEquals("user", me.role)
        assertFalse(me.isOperator)
        assertTrue(me.copy(role = "admin").isOperator)
    }

    @Test
    fun anAccountOwnsTheCollectionWhoseSlugItCarries() {
        assertTrue(me.owns("matt"))
        assertFalse(me.owns("kayla"))
        assertFalse(me.owns(""))
    }

    @Test
    fun theAdminRoleOwnsEverybodys() {
        // Matt: "Anyone with the admin role will be able to do
        // whatever they want, from modify others cards to giving
        // other users admin etc etc." Nobody has the role unless he
        // hands it out, which is `RolesTest`'s half.
        val ops = me.copy(role = Role.ADMIN)
        assertTrue(ops.isOperator)
        assertTrue(ops.owns(me.slug))
        assertTrue(ops.owns("kayla"))
    }
}
