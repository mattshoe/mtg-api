package org.mattshoe.mtg.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pinch zoom on the carousel's card.
 *
 * Matt: "pinch zoom should zoom in and stay at the specified zoom
 * tapping back should fully zoom out (as well as pinching to zoom all
 * the way out) swiping to the next card should only work while fully
 * zoomed out you should be able to drag the image around to move the
 * visible part of the image with 1 finger while zoomed in"
 *
 * Every one of those is a rule rather than a rendering, so the zoom
 * lives in `Peek` and both shells only feed it fingers.
 */
class CardZoomTest {

    private val w = 300f
    private val h = 420f

    private fun near(want: Float, got: Float, what: String) =
        assertTrue(abs(want - got) < 0.01f, "$what: expected $want but was $got")

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
    )

    private fun peeking(): AppState = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring"))),
            )
        }
        .peekAt(1)

    private fun zoomedIn(): AppState =
        peeking().let { it.peekZoom(it.peek.zoom.pinch(2.5f, 0f, 0f, w, h)) }

    // ------------------------------------------------------ the gesture

    @Test
    fun spreadingTwoFingersZoomsIn() {
        val z = CardZoom().pinch(2f, 0f, 0f, w, h)
        near(2f, z.scale, "a spread of two did not double the card")
        assertTrue(z.zoomed)
    }

    @Test
    fun thePointBetweenTheFingersStaysUnderThem() {
        // Pinching over the top-left of the art should enlarge the
        // top-left, not the middle of the card.
        val z = CardZoom().pinch(2f, -100f, -150f, w, h)
        near(100f, z.x, "the art slid out from under the fingers sideways")
        near(150f, z.y, "the art slid out from under the fingers vertically")
    }

    @Test
    fun itCannotZoomOutPastTheWholeCard() {
        val z = CardZoom().pinch(0.3f, 0f, 0f, w, h)
        assertEquals(CardZoom(), z, "pinching in shrank the card below its own size")
    }

    @Test
    fun itStopsSomewhereReadable() {
        val z = CardZoom().pinch(50f, 0f, 0f, w, h)
        near(CardZoom.MAX, z.scale, "zoom ran away")
    }

    @Test
    fun pinchingAllTheWayOutPutsTheCardBackWhereItWas() {
        val z = CardZoom().pinch(3f, -100f, 80f, w, h).pinch(1f / 3f, 0f, 0f, w, h)
        assertEquals(CardZoom(), z, "zoomed all the way out and the card is still shifted")
    }

    @Test
    fun aPinchThatEndsNearlyOutSnapsFullyOut() {
        val z = CardZoom().pinch(1.03f, 0f, 0f, w, h).settled()
        assertFalse(z.zoomed, "a hair of zoom was left on, which would lock the swipe")
    }

    @Test
    fun aPinchThatEndsZoomedInStaysThere() {
        val z = CardZoom().pinch(2f, 0f, 0f, w, h).settled()
        near(2f, z.scale, "the zoom did not stay where it was left")
    }

    @Test
    fun oneFingerMovesTheArtWhileZoomedIn() {
        val z = CardZoom().pinch(2f, 0f, 0f, w, h).pan(-40f, 25f, w, h)
        near(-40f, z.x, "a drag did not move the art sideways")
        near(25f, z.y, "a drag did not move the art vertically")
    }

    @Test
    fun theArtCannotBeDraggedOffTheCard() {
        // At 2x the art is twice the card, so it can move half a card
        // either way before its edge comes into view.
        val z = CardZoom().pinch(2f, 0f, 0f, w, h).pan(-1000f, 1000f, w, h)
        near(-150f, z.x, "dragged past the art's edge sideways")
        near(210f, z.y, "dragged past the art's edge vertically")
    }

    @Test
    fun fingersWithNoPositionLeaveTheZoomAlone() {
        // Compose reports the centre of no fingers as NaN on the event
        // where the last one lifts. On the emulator that NaN went into
        // the zoom and the art vanished.
        val z = CardZoom().pinch(2f, 0f, 0f, w, h)
        assertEquals(z, z.pinch(1f, Float.NaN, Float.NaN, w, h), "a pinch about nowhere moved the art")
        assertEquals(z, z.pan(Float.NaN, Float.NaN, w, h), "a drag of nothing moved the art")
    }

    @Test
    fun aDragDoesNothingZoomedOut() {
        assertEquals(CardZoom(), CardZoom().pan(50f, 50f, w, h))
    }

    // ------------------------------------------------- the carousel

    @Test
    fun theCarouselHoldsTheZoom() {
        near(2.5f, zoomedIn().peek.zoom.scale, "the app did not keep the zoom")
    }

    @Test
    fun swipingDoesNothingWhileZoomedIn() {
        assertEquals(1, zoomedIn().peekTo(2).peek.at, "swiped to the next card while zoomed in")
    }

    @Test
    fun swipingWorksOnceZoomedOut() {
        val out = zoomedIn().let { it.peekZoom(it.peek.zoom.pinch(0.1f, 0f, 0f, w, h)) }
        assertEquals(2, out.peekTo(2).peek.at)
    }

    @Test
    fun backZoomsOutBeforeItClosesAnything() {
        val back = zoomedIn().back()!!
        assertTrue(Overlay.CARD_PEEK in back.overlays, "back closed the carousel instead of zooming out")
        assertEquals(CardZoom(), back.peek.zoom, "back did not zoom fully out")
        assertEquals(1, back.peek.at, "back moved to another card")
    }

    @Test
    fun backThenClosesTheCarouselAsBefore() {
        val back = zoomedIn().back()!!.back()!!
        assertFalse(Overlay.CARD_PEEK in back.overlays, "the second back did not close the carousel")
    }

    @Test
    fun theWebsitesBackAndEscapeZoomOutToo() {
        // The website's popstate and Escape both reach `dismissTop`,
        // not `back`.
        val out = zoomedIn().dismissTop()!!
        assertTrue(Overlay.CARD_PEEK in out.overlays, "dismissing closed the carousel instead of zooming out")
        assertEquals(CardZoom(), out.peek.zoom)
    }

    @Test
    fun aZoomIsOneMoreThingBackHasToUndo() {
        // So the website pushes a history entry for it, and the
        // browser's back has something to pop that is not the
        // carousel itself.
        assertEquals(peeking().historyDepth + 1, zoomedIn().historyDepth)
    }

    @Test
    fun anotherCardOpensZoomedOut() {
        assertEquals(CardZoom(), zoomedIn().back()!!.back()!!.peekAt(0).peek.zoom)
    }
}
