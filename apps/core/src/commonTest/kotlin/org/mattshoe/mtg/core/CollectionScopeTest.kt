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

    private val matt = Account(slug = "matt", name = "Matt")
    private val signedIn = AppState(admin = Admin().signIn(matt, session = "s"))

    @Test
    fun signedInYouAreLookingAtYourOwnCollection() {
        assertEquals("matt", signedIn.viewing)
    }

    @Test
    fun theAddressWins() {
        // Browsing somebody else's, which is the point of a public
        // collection having an address at all.
        assertEquals("kayla", signedIn.navigate(Route(View.DECKS, "", "")).browsing("kayla").viewing)
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
        assertFalse(signedIn.browsing("kayla").canEdit, "it offered to edit Kayla's collection")
    }

    @Test
    fun anOrdinaryAccountDoesNotGetKaylasCollection() {
        // The actual complaint: being "unlocked" used to answer for
        // every collection there is, and the buttons asked nothing
        // else. A `user` is the default and owns one collection.
        val user = AppState(admin = Admin().signIn(Account(slug = "matt"), "s"))
        assertTrue(user.admin.unlocked, "the fixture is not signed in")
        assertFalse(user.browsing("kayla").canEdit, "an ordinary account got somebody else's cards")
        assertTrue(user.browsing("matt").canEdit, "it cannot edit its own")
    }

    @Test
    fun theAdminRoleEditsAnything() {
        // Matt hands this role out by name, and it does what it
        // likes: "modify others cards to giving other users admin".
        val ops = AppState(admin = Admin().signIn(matt.copy(role = Role.ADMIN), session = "s"))
        assertTrue(ops.browsing("kayla").canEdit)
        assertTrue(ops.browsing(matt.slug).canEdit)
    }

    @Test
    fun nobodyAtAllEditsNothing() {
        assertFalse(AppState().canEdit)
        assertFalse(AppState().browsing("matt").canEdit)
    }

    // ------------------------------------------------- what is loaded

    @Test
    fun theDeckListIsOneCollectionsWorth() {
        // It asked for every deck in the database, which is why
        // Kayla's were on screen.
        val sql = DeckQueries.all("matt").sql
        assertTrue("d.owner = ?" in sql, "the deck query does not scope to an owner: $sql")
        assertEquals(listOf("matt"), DeckQueries.all("matt").params)
    }

    @Test
    fun noCollectionNamedMeansEverybodysStill() {
        // A visitor with no slug, and the shape every existing test
        // of this query was written against.
        assertFalse("d.owner = ?" in DeckQueries.all("").sql)
        assertEquals(emptyList(), DeckQueries.all("").params)
    }

    @Test
    fun theLibraryFollowsTheCollectionYouAreLookingAt() {
        assertEquals("matt", signedIn.scopedLibrary().filters.owner)
        assertEquals("kayla", signedIn.browsing("kayla").scopedLibrary().filters.owner)
        // And a visitor who named nobody still sees everything.
        assertEquals("both", AppState().scopedLibrary().filters.owner)
    }
}
