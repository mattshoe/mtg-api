package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whose decks the filter panel's "In a deck" offers.
 *
 * Matt: "the collection filter shows ALL decks and NOT the deck the user
 * owns. this is unacceptable. especially when browsing other users cards".
 * The deck list was every deck in the database whichever collection was
 * on screen, so picking one of Kayla's while looking at your own cards
 * was on offer, and could only ever mean nothing.
 */
class DeckPickerScopeTest {

    private val matt = "m4tt0001"
    private val kayla = "k4yy0003"

    /** Decoded the way the server's answer is, not built by hand. */
    private val decks = FacetQueries.decodeDecks(
        listOf("key", "name", "owner", "owner_key"),
        listOf(
            row("d1", "Alela", "Matt", matt),
            row("d2", "Korvold", "Kayla", kayla),
            row("d3", "Atraxa", "Kayla", kayla),
        ),
    )
    private val facets = Facets(types = listOf("Creature"), decks = decks)

    private fun row(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })

    private fun signedIn() = AppState(admin = Admin().signIn(Account(matt), "t"), facets = facets)

    private fun Facets.deckNames() = this.decks.map { it.name }

    @Test
    fun yourOwnLibraryOffersYourDecksAndNobodyElses() {
        assertEquals(listOf("Alela"), signedIn().navigate(View.LIBRARY).panelFacets.deckNames())
    }

    @Test
    fun somebodyElsesLibraryOffersTheirDecksAndNotYours() {
        assertEquals(
            listOf("Korvold", "Atraxa"),
            signedIn().browseUser(kayla).panelFacets.deckNames(),
        )
    }

    @Test
    fun aSharedLinkOffersTheDecksOfTheCollectionItNames() {
        val stranger = AppState(admin = Admin().signOut(), facets = facets).browsing(kayla)
        assertEquals(listOf("Korvold", "Atraxa"), stranger.panelFacets.deckNames())
    }

    @Test
    fun comingBackFromSomebodyElsesLibraryOffersYourDecksAgain() {
        val back = signedIn().browseUser(kayla).navigate(View.LIBRARY)
        assertEquals(listOf("Alela"), back.panelFacets.deckNames())
    }

    @Test
    fun aVisitorWhoHasNamedNobodyIsLookingAtEveryCollectionSoEveryDeckIsOffered() {
        val visitor = AppState(admin = Admin().signOut(), facets = facets)
        assertEquals(listOf("Alela", "Korvold", "Atraxa"), visitor.panelFacets.deckNames())
    }

    @Test
    fun beforeTheServerHasSaidWhoThisIsNoDecksAreOffered() {
        // An unanswered `/auth/me` is not "nobody". Read as nobody, this
        // is every deck in the database for the moment before the answer.
        assertEquals(emptyList(), AppState(facets = facets).panelFacets.deckNames())
    }

    @Test
    fun aDeckWhoseOwnerIsUnknownIsNotOfferedInSomebodysCollection() {
        val unknown = Facets(decks = FacetQueries.decodeDecks(listOf("key", "name", "owner"), listOf(row("d9", "Who", "x"))))
        val state = AppState(admin = Admin().signIn(Account(matt), "t"), facets = unknown)
        assertEquals(emptyList(), state.panelFacets.deckNames())
    }

    @Test
    fun oneCollectionsDecksAreNamedWithoutTheirOwner() {
        assertEquals(listOf("d1" to "Alela"), signedIn().panelFacets.deckChoices)
    }

    @Test
    fun everyCollectionsDecksSayWhoseTheyAre() {
        val visitor = AppState(admin = Admin().signOut(), facets = facets)
        assertEquals(
            listOf("d1" to "Alela (Matt)", "d2" to "Korvold (Kayla)", "d3" to "Atraxa (Kayla)"),
            visitor.panelFacets.deckChoices,
        )
    }

    @Test
    fun aDeckFilterDoesNotFollowYouIntoSomebodyElsesCollection() {
        // The picker no longer lists it, so it would be a filter nobody
        // can see, on a collection it says nothing about.
        val filtered = signedIn().let { it.copy(library = it.library.copy(filters = Filters(deck = "d1"))) }
        assertEquals("", filtered.browseUser(kayla).library.filters.deck)
    }

    @Test
    fun inAnyDeckAndInNoDeckMeanTheSameInEveryCollectionSoTheyStay() {
        listOf("_any", "_none").forEach { word ->
            val filtered = signedIn().let { it.copy(library = it.library.copy(filters = Filters(deck = word))) }
            assertEquals(word, filtered.browseUser(kayla).library.filters.deck)
        }
    }

    @Test
    fun theDeckFilterStaysWhileTheCollectionDoes() {
        val filtered = signedIn().browseUser(kayla).let {
            it.copy(library = it.library.copy(filters = Filters(deck = "d2")))
        }
        assertEquals("d2", filtered.navigate(Route(View.USERS, kayla)).library.filters.deck)
        assertEquals("d2", filtered.navigate(Route(View.CARD, "sol ring")).library.filters.deck)
    }
}
