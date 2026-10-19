package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list, i want to be able to swipe through the
 * missing cards just like the ones in my collection. however, it is a
 * different carousel of its own. i shouldn't be able to swipe into the
 * missing ones while swiping through the collection ones and vice
 * versa", and "i also want to be able to check off the missing ones
 * just like the collection ones".
 */
class FetchListMissingCarouselTest {

    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus\n1 Mox Pearl\n3 Lightning Bolt"

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U", scryfallId = "cs-1"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1, scryfallId = "sr-1"),
        OwnedPrinting("lightning bolt", "Lightning Bolt", null, "Instant", "R", "common", 1, identity = "R", scryfallId = "lb-1"),
    )

    /** On the list page with the list found. Black Lotus and Mox Pearl are not owned, two Bolts are short. */
    private fun found(): AppState {
        val f = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned))
        return AppState(route = Route(View.FETCH, FetchListState.PAGE), fetch = f)
    }

    private fun missing(s: AppState, name: String): MissingCard =
        s.fetch.result!!.missing.first { it.name == name }

    @Test
    fun tappingAMissingCardOpensACarouselOverTheMissingCardsAlone() {
        val s = found()
        val open = s.peekMissing(missing(s, "Mox Pearl"))
        assertTrue(Overlay.CARD_PEEK in open.overlays, "tapping Mox Pearl opened no carousel")
        assertEquals(PeekOf.FETCH_MISSING, open.peek.of, "the carousel is not over the missing cards")
        assertEquals("Mox Pearl", open.peeked?.title, "the carousel opened on another card")
        assertEquals(
            listOf("Black Lotus", "Mox Pearl", "Lightning Bolt"),
            open.peekRun.map { it.title },
            "the missing carousel runs over something other than the missing cards",
        )
    }

    @Test
    fun swipingPastTheLastMissingCardDoesNotReachTheCollectionCards() {
        val s = found().let { it.peekMissing(missing(it, "Lightning Bolt")) }
        val past = s.peekTo(s.peek.at + 1)
        assertEquals("Lightning Bolt", past.peeked?.title, "swiping on from the last missing card left the missing carousel")
        assertEquals("3 of 3", past.peekPlace)
    }

    @Test
    fun swipingPastTheLastCollectionCardDoesNotReachTheMissingOnes() {
        val s = found()
        val last = s.fetch.pageOrder.last()
        val open = s.peekFetch(last)
        val past = open.peekTo(open.peek.at + 1)
        assertEquals(last.name, past.peeked?.title, "swiping on from the last collection card left the collection carousel")
        assertFalse(past.peekRun.any { it.title == "Black Lotus" }, "the collection carousel can reach a missing card")
    }

    @Test
    fun aMissingCardHasAPictureByName() {
        val s = found().let { it.peekMissing(missing(it, "Black Lotus")) }
        val art = assertNotNull(s.peeked?.picture(back = false), "a missing card has no picture to draw")
        assertTrue("named?exact=Black%20Lotus" in art, "the picture is not of Black Lotus: $art")
    }

    @Test
    fun theMissingCarouselsBoxChecksOffTheMissingCard() {
        val s = found().let { it.peekMissing(missing(it, "Black Lotus")) }
        val box = assertNotNull(s.peeked?.tick, "the missing carousel has no box")
        assertFalse(box.ticked)
        val ticked = s.peekTick()
        assertTrue(ticked.fetch.isMissingTicked("Black Lotus"), "the carousel's box did not check off Black Lotus")
        assertTrue(ticked.peeked!!.tick!!.ticked, "the carousel's box did not show the check")
        assertEquals("1 of 3 checked off", ticked.peeked!!.tick!!.progress)
        assertFalse(ticked.peekTick().fetch.isMissingTicked("Black Lotus"), "a second tap did not uncheck it")
    }

    @Test
    fun checkingOffAShortCardDoesNotTickItFoundInTheBinder() {
        val s = found().let { it.copy(fetch = it.fetch.tickMissing("Lightning Bolt")) }
        assertTrue(s.fetch.isMissingTicked("Lightning Bolt"))
        assertFalse(s.fetch.isTicked("Lightning Bolt"), "checking off the two Bolts short ticked the one in the binder")
        assertEquals("0 of 3 found", s.fetch.progress)
    }

    @Test
    fun tickingACollectionCardDoesNotCheckOffItsMissingCopies() {
        val s = found().let { it.copy(fetch = it.fetch.tick("Lightning Bolt")) }
        assertFalse(s.fetch.isMissingTicked("Lightning Bolt"), "ticking the Bolt in the binder checked off the two short")
    }

    @Test
    fun aCheckedOffMissingCardIsKeptOnTheSavedList() {
        val saved = found().let { it.copy(fetch = it.fetch.titled("Alela")) }.savedFetch("2026-10-19T00:00:00Z")
        val s = saved.peekMissing(missing(saved, "Mox Pearl")).peekTick()
        assertEquals(setOf("Mox Pearl"), s.fetch.saved.single().tickedMissing, "the saved list lost the check")
        val reopened = s.copy(fetch = s.fetch.cleared()).let { it.copy(fetch = it.fetch.open(s.fetch.saved.single().id)) }
        assertTrue(reopened.fetch.isMissingTicked("Mox Pearl"), "opening the saved list again lost the check")
    }

    @Test
    fun aNewListStartsWithNothingCheckedOff() {
        val s = found().let { it.copy(fetch = it.fetch.tickMissing("Black Lotus").drafted("1 Sol Ring").started()) }
        assertTrue(s.fetch.tickedMissing.isEmpty(), "a new list kept the last one's checks")
    }

    @Test
    fun removingOnTheMissingCarouselTakesTheMissingCardOffAndStaysInTheMissingCarousel() {
        val s = found().let { it.peekMissing(missing(it, "Black Lotus")) }.peekRemove()
        assertEquals(listOf("Mox Pearl", "Lightning Bolt"), s.fetch.result!!.missing.map { it.name })
        assertEquals(PeekOf.FETCH_MISSING, s.peek.of)
        assertEquals("Mox Pearl", s.peeked?.title, "removing did not move on to the next missing card")
    }
}
