package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One press of back closes the carousel.
 *
 * Matt: "i always have to hit back twice to get out of the carousel".
 *
 * The Library's card-name suggestions are `complete.open`, and
 * `back()` answers for that list before anything else, because while
 * it is up it floats over the screen. But nothing ever put it away
 * except a pick, a press of back or a tap the list's own window heard
 * — so it outlived everything around it. Typed "sol", the list came
 * up, a tile was tapped: the tap opened the carousel and the list
 * was still open under it. A trip to Decks did not close it either,
 * and the box is only drawn in the Library, so there it was open and
 * nowhere on screen. Either way the first back closed a list nobody
 * was looking at, and the second closed the carousel.
 *
 * The list belongs to the screen it is drawn on. Something opening
 * over that screen, or leaving it, puts the list away.
 */
class CarouselOneBackTest {

    private fun row(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = null, scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = null, rarity = "uncommon", setCode = "m3c",
        setName = "Modern Horizons 3 Commander", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null, artist = null,
        qty = 1, printings = 1, free = 1, price = 1.75, value = 1.75,
    )

    private val rows = listOf(row("Sol Ring"), row("Solemn Simulacrum"))

    /** Typed "sol" in the Library and the suggestions came up. */
    private fun suggestingInTheLibrary() = AppState()
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows, rows.size)) }
        .typedCardName(Completion().typed("sol").suggested(listOf("Sol Ring", "Solemn Simulacrum")))

    private fun onADeck(from: AppState) = from
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring"))),
            )
        }

    @Test
    fun oneBackClosesACarouselOpenedFromTheLibraryWithSuggestionsUp() {
        val s = suggestingInTheLibrary()
        assertTrue(s.complete.open, "the fixture never opened the suggestion list")

        val peeking = s.peekRow(rows[0])
        assertTrue(Overlay.CARD_PEEK in peeking.overlays, "the tap did not open the carousel")

        val once = peeking.back() ?: error("back wanted to leave the app from the carousel")
        assertFalse(
            Overlay.CARD_PEEK in once.overlays,
            "the first back closed the suggestion list behind the carousel and left the carousel up",
        )
    }

    @Test
    fun oneBackClosesADecksCarouselAfterSuggestionsWereLeftUpInTheLibrary() {
        val peeking = onADeck(suggestingInTheLibrary()).peekAt(0)
        assertTrue(Overlay.CARD_PEEK in peeking.overlays, "the fixture never opened the carousel")

        val once = peeking.back() ?: error("back wanted to leave the app from the carousel")
        assertFalse(
            Overlay.CARD_PEEK in once.overlays,
            "the first back closed a suggestion list from the Library, which is not on screen, and left the carousel up",
        )
    }

    @Test
    fun puttingTheListAwayLeavesWhatWasTypedInTheBox() {
        val peeking = suggestingInTheLibrary().peekRow(rows[0])
        assertFalse(peeking.complete.open, "the list is still open under the carousel")
        assertTrue(peeking.complete.term == "sol", "opening the carousel emptied the name box")
        assertTrue(peeking.library.filters.q == "sol", "opening the carousel changed the search")
    }
}
