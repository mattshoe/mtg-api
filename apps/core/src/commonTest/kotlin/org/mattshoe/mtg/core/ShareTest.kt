package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A link to what is on screen, built in one place so both platforms agree. */
class ShareTest {

    private fun openCard() = AppState()
        .copy(
            library = Library(Filters(q = "sol")),
            card = CardDetail(name = "Sol Ring", owner = "matt", nameNorm = "sol ring"),
        )
        .opening(Overlay.CARD)

    @Test
    fun aLinkIsTheWholeAddressNotJustTheFragment() {
        val link = Share.link(AppState())
        assertTrue(link.startsWith("https://"), link)
        assertTrue(link.endsWith("#/search"), link)
    }

    @Test
    fun theLinkToACardCarriesTheCardAndTheSearchUnderIt() {
        // Sharing a card must not lose the search it was found in.
        val link = Share.link(openCard())
        assertTrue(link.contains("q=sol"), link)
        assertTrue(link.contains("card=matt:sol+ring"), link)
    }

    @Test
    fun theTitleIsTheCardWhenOneIsOpenAndThePageWhenNot() {
        assertEquals("Sol Ring", Share.title(openCard()))
        assertEquals("Library", Share.title(AppState()))
        assertEquals("Decks", Share.title(AppState().navigate(View.DECKS)))
    }

    @Test
    fun theLinkToADeckIsTheDeck() {
        val decks = DecksState()
            .loaded(listOf(Deck("alela", "Fairy Deck", "kayla", null, "BU", 3, null)))
            .opened("alela", emptyList())
        val s = AppState().navigate(Route(View.DECKS, "alela")).copy(decks = decks)
        assertEquals("https://mtg.mattshoe.org/#/decks/alela", Share.link(s))
        assertEquals("Fairy Deck", Share.title(s))
    }

    @Test
    fun anExportGoesToOneOfTwoPlaces() {
        assertEquals(listOf("clipboard", "file"), ExportTo.entries.map { it.slug })
    }
}
