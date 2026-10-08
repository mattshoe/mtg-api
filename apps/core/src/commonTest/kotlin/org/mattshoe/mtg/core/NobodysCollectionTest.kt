package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page asks for nothing until it knows whose collection it is.
 *
 * Matt: "why are kaylas decks showing for me in the web app?!?!?!"
 *
 * Because `viewing` is empty for the moment between the page opening
 * and the server answering who you are — no key in the address yet,
 * no account yet — and every collection-scoped query treats an empty
 * owner as "no WHERE clause". So the first load of the decks page was
 * every deck in the database, Kayla's included, and on a slow answer
 * that unscoped result is the one that lands last and stays.
 *
 * The fix is not a faster answer. It is that the app asked before
 * there was one — an unanswered `/auth/me` and "nobody is signed in"
 * are the same empty slug — and that an empty owner is not the same
 * question as "every owner". So nothing scoped goes out until the
 * server has said who this is, and the pooled read a stranger gets
 * has to ask for `DeckQueries.EVERY` by name.
 */
class NobodysCollectionTest {

    private val me = Account(key = "e7de0cb1", name = "Matt")

    @Test
    fun aFreshPageHasNotHeardBackYet() {
        assertFalse(AppState().collectionKnown, "it fetched before the server said who this is")
        assertEquals("", AppState().viewing)
    }

    @Test
    fun anAnswerOfNobodyIsStillAnAnswer() {
        // A stranger at the root domain is a legitimate reader, and
        // the page is no use to them sitting empty forever.
        assertTrue(AppState(admin = Admin().settle()).collectionKnown)
    }

    @Test
    fun anAccountSettlesIt() {
        assertTrue(AppState(admin = Admin().signIn(me, "t")).collectionKnown)
        assertEquals("e7de0cb1", AppState(admin = Admin().signIn(me, "t")).viewing)
    }

    @Test
    fun soDoesAKeyInTheAddressWithNobodySignedIn() {
        // A link somebody sent names a collection outright, which is
        // the whole point of having one. It does not wait on
        // `/auth/me` to be readable.
        val s = AppState().browsing("bprh3d2s")
        assertTrue(s.collectionKnown)
        assertEquals("bprh3d2s", s.viewing)
    }

    @Test
    fun signingOutLeavesItSettledRatherThanWaitingAgain() {
        val s = AppState(admin = Admin().signIn(me, "t").signOut())
        assertTrue(s.collectionKnown, "the page hung after a log out")
    }

    @Test
    fun theDecksPageAsksForNothingBeforeItKnowsWhoseItIs() {
        val s = AppState().navigate(Route(View.DECKS))
        assertEquals(emptyList(), Load.needs(s.route, s.collectionKnown))
    }

    @Test
    fun andAsksAsSoonAsItDoes() {
        val s = AppState(admin = Admin().signIn(me, "t")).navigate(Route(View.DECKS))
        assertEquals(listOf("decks"), Load.needs(s.route, s.collectionKnown))
    }

    @Test
    fun aStrangerAsksTooOnceTheAnswerIsIn() {
        val s = AppState(admin = Admin().settle()).navigate(Route(View.DECKS))
        assertEquals(listOf("decks"), Load.needs(s.route, s.collectionKnown))
    }

    @Test
    fun theLibraryAndTheStatsHoldTheSameLine() {
        listOf(View.LIBRARY, View.STATS).forEach { view ->
            val s = AppState().navigate(Route(view))
            assertEquals(emptyList(), Load.needs(s.route, s.collectionKnown), view.slug)
        }
    }

    @Test
    fun theScopedViewsAreNamedOnceAndNotOncePerShell() {
        // Both shells gate `loadFor` on this set. A view added to the
        // app has to be classified here rather than quietly reading
        // every collection at once.
        assertEquals(setOf(View.LIBRARY, View.DECKS, View.STATS), View.COLLECTION)
        assertTrue(View.CARD !in View.COLLECTION, "a card page is the card, not a collection")
        assertTrue(View.ENTRY !in View.COLLECTION)
        assertTrue(View.LOGS !in View.COLLECTION, "the server log is not anybody's cards")
    }

    @Test
    fun aCardIsStillAskedForBecauseACardIsNotACollection() {
        // A link somebody sent to a card page works signed out: the
        // card's own text is not anybody's property, and the page
        // says which collections hold copies.
        val s = AppState().navigate(Route(View.CARD, "sol+ring"))
        assertTrue(Load.needs(s.route, collectionKnown = false).isNotEmpty())
    }

    @Test
    fun noDeckQueryEverGoesOutWithoutACollectionOnIt() {
        // The query itself, not the caller's discipline: an empty
        // owner used to mean "every deck in the database".
        assertTrue("d.owner_id = (SELECT id FROM users WHERE key = ?)" in DeckQueries.all("e7de0cb1").sql)
        assertEquals(listOf("e7de0cb1"), DeckQueries.all("e7de0cb1").params)
        assertTrue(
            "1=0" in DeckQueries.all("").sql,
            "an empty owner still reads every collection: ${DeckQueries.all("").sql}",
        )
        // And the pooled read is a word, so it cannot happen by
        // omission — only by asking for it.
        assertFalse("1=0" in DeckQueries.all(DeckQueries.EVERY).sql)
        assertFalse("users WHERE key = ?" in DeckQueries.all(DeckQueries.EVERY).sql)
        assertEquals(emptyList(), DeckQueries.all(DeckQueries.EVERY).params)
    }

    @Test
    fun andNoLibrarySearchDoesEither() {
        val filters = Filters(owner = "")
        assertEquals("", filters.owner)
        assertTrue(
            "1=0" in Library(filters = filters).queries().first.sql,
            "an unscoped Library search still reads every collection",
        )
        // `both` is the pooled read, by name, and still works.
        assertFalse("1=0" in Library(filters = Filters(owner = "both")).queries().first.sql)
        // And a collection that is known is scoped to it, not to 1=0.
        val mine = AppState(admin = Admin().signIn(me, "t")).scopedLibrary().filters
        assertEquals("e7de0cb1", mine.owner)
        assertTrue("c.owner_id = (SELECT id FROM users WHERE key = ?)" in Library(filters = mine).queries().first.sql)
    }
}
