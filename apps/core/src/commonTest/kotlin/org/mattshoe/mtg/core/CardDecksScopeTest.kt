package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page's "In decks" is the decks of the collection on screen.
 *
 * Matt: "the 'in decks' section keeps showing decks from other peoples
 * fucking collection". `CardQueries.usedIn` had no owner at all, so his
 * Sol Ring listed Kayla's decks beside his own. It is scoped the way the
 * Decks page is: the collection being looked at, every collection only
 * for a reader the server has said is nobody, and nothing while nobody
 * has said yet. `test/card-decks.test.js` runs the SQL.
 */
class CardDecksScopeTest {

    private val me = Account(key = "e7de0cb1", name = "Matt")

    private fun inDecks(s: AppState): Sql =
        Load.card("sol ring", s.cardScope).first { "FROM deck_cards dc" in it.sql }

    @Test
    fun yourOwnCardPageAsksForYourDecks() {
        val q = inDecks(AppState(admin = Admin().signIn(me, "t")))
        assertEquals(listOf<Any?>("sol ring", "e7de0cb1"), q.params, "the In decks query is not scoped to your collection")
        assertTrue(Owners.owns("d.owner_id") in q.sql, "the In decks query names no owner: ${q.sql}")
    }

    @Test
    fun aCollectionInTheAddressIsTheOneWhoseDecksAreListed() {
        val q = inDecks(AppState(admin = Admin().signIn(me, "t")).browsing("k4yy0003"))
        assertEquals(listOf<Any?>("sol ring", "k4yy0003"), q.params)
    }

    @Test
    fun nothingIsListedBeforeTheServerHasSaidWhoThisIs() {
        val q = inDecks(AppState())
        assertTrue("1=0" in q.sql, "an unanswered /auth/me read every collection's decks: ${q.sql}")
    }

    @Test
    fun aStrangerAtTheRootSeesEveryCollectionAsBefore() {
        val q = inDecks(AppState(admin = Admin().settle()))
        assertEquals(listOf<Any?>("sol ring"), q.params)
    }
}
