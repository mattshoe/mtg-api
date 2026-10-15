package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "tapping the row (outside checkbox) should show the carousel,
 * and the bottom part should have the checkbox as well so i can swipe
 * through and check them off as i swipe. careful to keep stats in sync
 * between carousel and other page".
 *
 * The carousel's box and the page's box are the same tick in
 * `FetchListState`, so the "1 of 3 found" on one is the other's.
 */
class FetchListCarouselTest {

    private val list = "1 Counterspell\n1 Arcane Signet\n1 Sol Ring\n1 Black Lotus"

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U", scryfallId = "cs-1"),
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1, scryfallId = "as-1"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1, scryfallId = "sr-1"),
    )

    /** On the list page with the list found. Black Lotus is not owned. */
    private fun found(): AppState {
        val f = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned))
        return AppState(route = Route(View.FETCH, FetchListState.PAGE), fetch = f)
    }

    private fun card(s: AppState, name: String): FetchCard =
        s.fetch.result!!.sections.flatMap { it.cards }.first { it.name == name }

    @Test
    fun tappingACardOpensTheCarouselOverTheListAtThatCard() {
        val s = found()
        val open = s.peekFetch(card(s, "Sol Ring"))
        assertTrue(Overlay.CARD_PEEK in open.overlays, "tapping Sol Ring opened no carousel")
        assertEquals(PeekOf.FETCH, open.peek.of, "the carousel is not over the fetch list")
        assertEquals("Sol Ring", open.peeked?.title, "the carousel opened on another card")
        // Page order: Instants, then Artifacts (Arcane Signet at common, Sol Ring at uncommon).
        assertEquals(listOf("Counterspell", "Arcane Signet", "Sol Ring"), open.peekRun.map { it.title })
        assertEquals("3 of 3", open.peekPlace)
    }

    @Test
    fun theCarouselShowsTheCardsArt() {
        val s = found().let { it.peekFetch(card(it, "Counterspell")) }
        assertEquals("cs-1", s.peeked?.scryfallId, "the carousel has no picture of Counterspell")
    }

    @Test
    fun theQueryAsksForAPrintingToDraw() {
        val sql = FetchList.query(listOf("sol ring"), "e7de0cb1")!!.sql
        assertTrue("scryfall_id" in sql, "the fetch query asks for no printing, so the carousel has no art: $sql")
        val rows = FetchList.decode(
            listOf("name_norm", "name", "types", "rarity", "qty", "scryfall_id"),
            listOf(JsonArray(listOf("sol ring", "Sol Ring", "Artifact", "uncommon", "1", "sr-9").map(::JsonPrimitive))),
        )
        assertEquals("sr-9", rows.single().scryfallId)
    }

    @Test
    fun theCarouselsBoxStartsAsThePageHasIt() {
        val s = found().let { it.copy(fetch = it.fetch.tick("Sol Ring")) }
        val open = s.peekFetch(card(s, "Sol Ring"))
        val tick = assertNotNull(open.peeked?.tick, "the carousel over a fetch list has no box")
        assertTrue(tick.ticked, "Sol Ring is ticked on the page and not on the carousel")
        assertEquals("1 of 3 found", tick.progress)
    }

    @Test
    fun tickingOnTheCarouselTicksThePage() {
        val s = found().let { it.peekFetch(card(it, "Arcane Signet")) }.peekTick()
        assertTrue(s.fetch.isTicked("Arcane Signet"), "the tick on the carousel did not reach the page")
        assertEquals("1 of 3 found", s.fetch.progress, "the page's count did not move")
        assertEquals("1 of 3 found", s.peeked?.tick?.progress, "the carousel's count did not move")
        assertFalse(s.peekTick().fetch.isTicked("Arcane Signet"), "a second tap did not untick it")
    }

    @Test
    fun swipingThenTickingTicksTheCardSwipedTo() {
        val s = found().let { it.peekFetch(card(it, "Counterspell")) }
        val next = s.peekTo(1).peekTick().peekTo(2).peekTick()
        assertEquals(setOf("Arcane Signet", "Sol Ring"), next.fetch.ticked)
        assertEquals("2 of 3 found", next.fetch.progress)
    }

    @Test
    fun aTickOnTheCarouselIsKeptOnTheSavedList() {
        val saved = found().let { it.copy(fetch = it.fetch.titled("Alela")) }.savedFetch("2026-10-14T00:00:00Z")
        val s = saved.peekFetch(card(saved, "Sol Ring")).peekTick()
        assertEquals(setOf("Sol Ring"), s.fetch.saved.single().ticked, "the saved list lost the carousel's tick")
    }

    @Test
    fun otherCarouselsHaveNoBox() {
        val deck = AppState(decks = DecksState(cards = listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring"))))
        assertNull(deck.peekAt(0, PeekOf.DECK).peeked?.tick, "a deck's carousel grew a Found box")
    }
}
