package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A link to what is on screen, built in one place so both platforms agree. */
class ShareTest {

    private fun openCard() = AppState()
        .copy(library = Library(Filters(q = "sol")))
        .openCard(CardRef("sol ring"), "Sol Ring")

    @Test
    fun aLinkIsTheWholeAddressNotJustTheFragment() {
        val link = Share.link(AppState())
        assertTrue(link.startsWith("https://"), link)
        assertTrue(link.endsWith("/s/search"), link)
    }

    @Test
    fun aLinkIsOneACrawlerCanReadThePageOutOf() {
        // Discord and Slack fetch a pasted link and read its tags. A
        // fragment never reaches a server, so every hash link previewed
        // as the bare site. The route goes in the path, on the Worker
        // that answers with the tags and sends the reader on.
        val link = Share.link(AppState().navigate(Route(View.DECKS, "d0000020", collection = "k4yy0003")))
        assertEquals("https://mtg-api.mattshoe81.workers.dev/s/c/k4yy0003/decks/d0000020", link)
        assertFalse('#' in link, "a fragment is invisible to whatever builds the preview: \$link")
    }

    @Test
    fun theLinkToACardIsTheCardAndNothingElse() {
        // It used to be the card appended to whatever page the drawer
        // was open over, so what arrived was somebody else's search
        // or somebody else's deck with a card on top of it.
        assertEquals("https://mtg-api.mattshoe81.workers.dev/s/card/sol+ring", Share.link(openCard()))
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
        assertEquals("https://mtg-api.mattshoe81.workers.dev/s/decks/alela", Share.link(s))
        assertEquals("Fairy Deck", Share.title(s))
    }

    @Test
    fun anExportGoesToOneOfTwoPlaces() {
        assertEquals(listOf("clipboard", "file"), ExportTo.entries.map { it.slug })
    }

    /** A share is a link or a deck list — the other half of what the button is for. */
    @Test
    fun aShareIsALinkOrADeckList() {
        assertEquals(listOf("link", "decklist"), ShareWhat.entries.map { it.slug })
        assertEquals(listOf("Link", "Deck list"), ShareWhat.entries.map { it.label })
    }
}
