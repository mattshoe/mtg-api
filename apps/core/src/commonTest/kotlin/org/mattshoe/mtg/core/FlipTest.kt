package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Matt: "[All] double faced cards should have a toggle layover on the
 * image to flip it".
 *
 * Only a card with a picture on the back flips. Split, adventure and
 * Kamigawa flip cards are one image with two names on it.
 */
class FlipTest {

    /** Every layout Scryfall publishes. */
    private val layouts = listOf(
        "normal", "split", "flip", "transform", "modal_dfc", "meld", "leveler", "class",
        "case", "saga", "adventure", "mutate", "prototype", "battle", "planar", "scheme",
        "vanguard", "token", "double_faced_token", "emblem", "augment", "host",
        "art_series", "reversible_card",
    )

    @Test
    fun onlyCardsWithAPictureOnTheBackFlip() {
        assertEquals(
            listOf("transform", "modal_dfc", "double_faced_token", "reversible_card"),
            layouts.filter { Flip.flips(it) },
        )
        assertFalse(Flip.flips(null))
    }

    @Test
    fun isDfcAndTheToggleAgreeAboutEveryLayout() {
        val where = parseQueryBox("is:dfc").sql
        layouts.forEach { l ->
            assertEquals(Flip.flips(l), "'$l'" in where, "is:dfc and the toggle disagree about $l: $where")
        }
    }

    @Test
    fun theBackIsTheSamePictureFromTheOtherSide() {
        val id = "dad34ae5-56b4-4394-be02-e043dc1cc23d"
        assertEquals("https://cards.scryfall.io/normal/back/d/a/$id.jpg", CardQueries.art(id, back = true))
        assertEquals("https://cards.scryfall.io/normal/front/d/a/$id.jpg", CardQueries.art(id))
    }

    private val norm = "aetherblade agent // gitaxian mindstinger"
    private val id = "dad34ae5-56b4-4394-be02-e043dc1cc23d"

    @Test
    fun aTapShowsTheBackAndASecondShowsTheFront() {
        val once = AppState().flip(norm)
        assertTrue(once.showsBack(norm), "one tap did not turn it over")
        assertTrue("/back/" in assertNotNull(once.artFor(id, norm, "transform")))
        val twice = once.flip(norm)
        assertFalse(twice.showsBack(norm))
        assertTrue("/front/" in assertNotNull(twice.artFor(id, norm, "transform")))
    }

    @Test
    fun aSingleFacedCardNeverShowsABack() {
        val s = AppState().flip("sol ring")
        assertTrue("/front/" in assertNotNull(s.artFor(id, "sol ring", "normal")))
    }

    @Test
    fun flippingOneCardLeavesTheOthersAlone() {
        val s = AppState().flip(norm)
        assertFalse(s.showsBack("hagra mauling // hagra broodpit"))
    }

    @Test
    fun theSideSurvivesGoingSomewhereElse() {
        val s = AppState().flip(norm).navigate(View.DECKS).navigate(View.LIBRARY)
            .openCard(CardRef(norm), "Aetherblade Agent")
        assertTrue(s.showsBack(norm), "navigating turned the card back over")
    }
}
