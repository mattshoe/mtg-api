package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list carousel, i want to be able to remove cards
 * from the list. if i do so, the carousel should update in place with
 * the card removed, but i should keep my place in the carousel, just
 * moved forward one card to the next (unless i remove the last
 * obviously)".
 *
 * The same remove the page's row does, so the list text, the filing,
 * the ticks and the saved list all lose it too.
 */
class FetchListCarouselRemoveTest {

    private val list = "1 Counterspell\n1 Arcane Signet\n1 Sol Ring\n1 Black Lotus"

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U", scryfallId = "cs-1"),
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1, scryfallId = "as-1"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1, scryfallId = "sr-1"),
    )

    /** On the list page with the list found. Page order: Counterspell, Arcane Signet, Sol Ring. */
    private fun found(): AppState {
        val f = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned))
        return AppState(route = Route(View.FETCH, FetchListState.PAGE), fetch = f)
    }

    private fun peekAt(name: String): AppState = found().let { s ->
        s.peekFetch(s.fetch.pageOrder.first { it.name == name })
    }

    @Test
    fun removingACardShowsTheNextOneInItsPlace() {
        val s = peekAt("Arcane Signet").peekRemove()
        assertTrue(Overlay.CARD_PEEK in s.overlays, "removing a card shut the carousel")
        assertEquals(listOf("Counterspell", "Sol Ring"), s.peekRun.map { it.title }, "the carousel still has Arcane Signet")
        assertEquals("Sol Ring", s.peeked?.title, "the carousel did not move on to the next card")
        assertEquals("2 of 2", s.peekPlace)
    }

    @Test
    fun removingTheFirstCardShowsTheSecond() {
        val s = peekAt("Counterspell").peekRemove()
        assertEquals("Arcane Signet", s.peeked?.title, "the carousel did not move on from the first card")
        assertEquals("1 of 2", s.peekPlace)
    }

    @Test
    fun removingTheLastCardShowsTheOneBeforeIt() {
        val s = peekAt("Sol Ring").peekRemove()
        assertTrue(Overlay.CARD_PEEK in s.overlays, "removing the last card shut the carousel")
        assertEquals("Arcane Signet", s.peeked?.title, "removing the last card did not step back to the one before it")
        assertEquals("2 of 2", s.peekPlace)
    }

    @Test
    fun removingTheOnlyCardLeftShutsTheCarousel() {
        val s = peekAt("Sol Ring").peekRemove().peekRemove().peekRemove()
        assertFalse(Overlay.CARD_PEEK in s.overlays, "the carousel is still open over an empty list")
        assertFalse(s.peek.open, "the carousel still holds a place in an empty list")
        assertEquals(emptyList(), s.fetch.pageOrder.map { it.name })
    }

    @Test
    fun theCardIsGoneFromThePageAndTheListText() {
        val s = peekAt("Arcane Signet").peekRemove()
        assertEquals(listOf("Counterspell", "Sol Ring"), s.fetch.pageOrder.map { it.name }, "the page still files Arcane Signet")
        assertFalse("Arcane Signet" in s.fetch.list, "the list text still names Arcane Signet: ${s.fetch.list}")
        assertEquals("0 of 2 found", s.fetch.progress)
    }

    @Test
    fun aTickedCardRemovedIsNotCounted() {
        val s = peekAt("Counterspell").peekTick().peekTo(1).peekTick().peekTo(0).peekRemove()
        assertEquals(setOf("Arcane Signet"), s.fetch.ticked, "Counterspell's tick outlived it")
        assertEquals("1 of 2 found", s.peeked?.tick?.progress, "the carousel's count still counts Counterspell")
        assertTrue(s.peeked?.tick?.ticked == true, "Arcane Signet, now on show, lost its tick")
    }

    @Test
    fun theSavedListLosesItToo() {
        val saved = found().let { it.copy(fetch = it.fetch.titled("Alela")) }.savedFetch("2026-10-15T00:00:00Z")
        val s = saved.peekFetch(saved.fetch.pageOrder.first { it.name == "Sol Ring" }).peekRemove()
        assertFalse("Sol Ring" in s.fetch.saved.single().list, "the saved list still has Sol Ring")
    }

    @Test
    fun aZoomedCardRemovedLeavesTheNextOneZoomedOut() {
        val s = peekAt("Counterspell").peekZoom(CardZoom(scale = 2f)).peekRemove()
        assertFalse(s.peek.zoom.zoomed, "the next card came up zoomed in")
    }

    @Test
    fun anyOtherCarouselIsLeftAlone() {
        val deck = AppState(decks = DecksState(cards = listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring"))))
        val open = deck.peekAt(0, PeekOf.DECK)
        assertEquals(open, open.peekRemove(), "a deck's carousel removed a card through the fetch list's remove")
    }
}
