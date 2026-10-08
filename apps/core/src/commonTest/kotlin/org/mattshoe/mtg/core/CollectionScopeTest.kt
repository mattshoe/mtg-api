package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whose collection you are looking at, and whether you may change it.
 *
 * Matt: "Then why the fuck can i still edit Kayla's decks,!?!?!? AND
 * WHY THE FUCK ARE YOU SHOWING ME KAYLA'S DECKS AT ALL?!?!"
 *
 * Both were the same hole. Nothing in the app had a notion of *which*
 * collection was on screen, so the decks page listed every owner it
 * could find, and the edit buttons asked `admin.unlocked` — "are you
 * unlocked" — rather than "do you own this". A stored password
 * answers the first question yes for everybody's cards.
 *
 * `viewing` is the collection on screen and `canEdit` is about that
 * collection and no other. The server has always enforced the real
 * rule; this is the app finally asking the same question.
 */
class CollectionScopeTest {

    private val matt = Account(key = "e7de0cb1", name = "Matt")
    private val signedIn = AppState(admin = Admin().signIn(matt, session = "s"))

    @Test
    fun signedInYouAreLookingAtYourOwnCollection() {
        assertEquals("e7de0cb1", signedIn.viewing)
    }

    @Test
    fun theAddressWins() {
        // Browsing somebody else's, which is the point of a public
        // collection having an address at all.
        assertEquals("bprh3d2s", signedIn.navigate(Route(View.DECKS, "", "")).browsing("bprh3d2s").viewing)
    }

    @Test
    fun aStrangerWithNoAddressIsLookingAtNobodyInParticular() {
        // Signed out and no slug: the old behaviour, every collection
        // at once. Not wrong for a visitor, and not what somebody
        // signed in should ever see.
        assertEquals("", AppState().viewing)
    }

    // ------------------------------------------------------- editing

    @Test
    fun youCanEditYourOwnCollection() {
        assertTrue(signedIn.canEdit)
    }

    @Test
    fun youCannotEditSomebodyElsesEvenWhileSignedIn() {
        assertFalse(signedIn.browsing("bprh3d2s").canEdit, "it offered to edit Kayla's collection")
    }

    @Test
    fun anOrdinaryAccountDoesNotGetKaylasCollection() {
        // The actual complaint: being "unlocked" used to answer for
        // every collection there is, and the buttons asked nothing
        // else. A `user` is the default and owns one collection.
        val user = AppState(admin = Admin().signIn(Account(key = "e7de0cb1"), "s"))
        assertTrue(user.admin.unlocked, "the fixture is not signed in")
        assertFalse(user.browsing("bprh3d2s").canEdit, "an ordinary account got somebody else's cards")
        assertTrue(user.browsing("e7de0cb1").canEdit, "it cannot edit its own")
    }

    @Test
    fun theAdminRoleEditsAnything() {
        // Matt hands this role out by name, and it does what it
        // likes: "modify others cards to giving other users admin".
        val ops = AppState(admin = Admin().signIn(matt.copy(role = Role.ADMIN), session = "s"))
        assertTrue(ops.browsing("bprh3d2s").canEdit)
        assertTrue(ops.browsing(matt.key).canEdit)
    }

    @Test
    fun nobodyAtAllEditsNothing() {
        assertFalse(AppState().canEdit)
        assertFalse(AppState().browsing("e7de0cb1").canEdit)
    }

    // ------------------------------------------------- what is loaded

    @Test
    fun theDeckListIsOneCollectionsWorth() {
        // It asked for every deck in the database, which is why
        // Kayla's were on screen.
        val sql = DeckQueries.all("e7de0cb1").sql
        assertTrue("d.owner_id = (SELECT id FROM users WHERE key = ?)" in sql, "the deck query does not scope to an owner: $sql")
        assertEquals(listOf("e7de0cb1"), DeckQueries.all("e7de0cb1").params)
    }

    @Test
    fun noCollectionNamedMeansEverybodysStill() {
        // A visitor with no slug, and the shape every existing test
        // of this query was written against.
        assertFalse("users WHERE key = ?" in DeckQueries.all("").sql)
        assertEquals(emptyList(), DeckQueries.all("").params)
    }

    @Test
    fun theLibraryFollowsTheCollectionYouAreLookingAt() {
        assertEquals("e7de0cb1", signedIn.scopedLibrary().filters.owner)
        assertEquals("bprh3d2s", signedIn.browsing("bprh3d2s").scopedLibrary().filters.owner)
        // And a visitor who named nobody still sees everything.
        assertEquals("both", AppState().scopedLibrary().filters.owner)
    }
}
