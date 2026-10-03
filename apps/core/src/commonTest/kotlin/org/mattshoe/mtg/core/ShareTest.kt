package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(link.endsWith("#/search"), link)
    }

    @Test
    fun theLinkToACardIsTheCardAndNothingElse() {
        // It used to be the card appended to whatever page the drawer
        // was open over, so what arrived was somebody else's search
        // or somebody else's deck with a card on top of it.
        //
        // And it used to be a hash URL. Changed deliberately: a
        // fragment never reaches a server, so every link pasted into
        // a chat unfurled as the bare site. `PreviewLinkTest` holds
        // the new rule.
        assertEquals("${Share.PREVIEW}/s/card/sol%20ring", Share.link(openCard()))
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
        // Previewable, not the hash URL — see `PreviewLinkTest`.
        assertEquals("${Share.PREVIEW}/s/deck/alela", Share.link(s))
        assertEquals("Fairy Deck", Share.title(s))
    }

    @Test
    fun anExportGoesToOneOfTwoPlaces() {
        assertEquals(listOf("clipboard", "file"), ExportTo.entries.map { it.slug })
    }
}

/**
 * A link somebody else opens.
 *
 * The site routes on a hash and a fragment never reaches a server, so
 * every link pasted into a chat unfurled as the bare site whatever it
 * pointed at. A deck and a card now get an address that names them in
 * the path, which is the only place a crawler can read.
 */
class PreviewLinkTest {

    private fun atDeck(slug: String) = AppState().navigate(Route(View.DECKS, slug))

    @Test
    fun aDeckLinkNamesTheDeckWhereAServerCanSeeIt() {
        val link = Share.link(atDeck("alela"))
        assertTrue(link.startsWith(Share.PREVIEW), "a deck link still points at the hash site: $link")
        assertTrue(link.endsWith("/s/deck/alela"), link)
        assertTrue("#" !in link, "a crawler cannot read a fragment: $link")
    }

    @Test
    fun aCardLinkDoesTheSame() {
        val link = Share.link(AppState().openCard(CardRef("sol ring")))
        assertEquals("${Share.PREVIEW}/s/card/sol%20ring", link)
        assertTrue("#" !in link)
    }

    @Test
    fun anythingElseIsStillJustTheSite() {
        val link = Share.link(AppState().navigate(Route(View.LIBRARY)))
        assertTrue(link.startsWith(Share.SITE), link)
    }

    @Test
    fun theDeckListItselfIsNotADeck() {
        // `#/decks` with no slug names no deck, so there is nothing
        // per-page to preview.
        assertTrue(Share.link(AppState().navigate(Route(View.DECKS))).startsWith(Share.SITE))
    }

    @Test
    fun aNameWithPunctuationSurvivesTheUrl() {
        val link = Share.link(AppState().openCard(CardRef("alela, cunning conqueror")))
        assertTrue("alela%2C%20cunning%20conqueror" in link, link)
        assertTrue(" " !in link, "a raw space breaks the link: $link")
    }

    @Test
    fun andSoDoesAnApostrophe() {
        val link = Share.link(AppState().openCard(CardRef("gaea's cradle")))
        assertTrue("gaea%27s%20cradle" in link, link)
    }

    @Test
    fun aNonAsciiNameIsEncodedAsUtf8() {
        val link = Share.link(AppState().openCard(CardRef("jötun grunt")))
        assertTrue("j%C3%B6tun" in link, link)
    }
}
