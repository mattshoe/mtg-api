package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pulling a page down to refresh it.
 *
 * Matt: "Every page should be able to pull to refresh". Both shells
 * hand the gesture to `refreshed`, so what a pull means and when its
 * spinner goes away is one answer rather than two.
 */
class PullToRefreshTest {

    private fun library() = AppState(
        library = Library().loaded(emptyList(), 0),
    )

    @Test
    fun aPulledLibraryAsksAgainEvenThoughItsRowsAnswerTheFilters() {
        val s = library()
        assertTrue(s.library.fresh, "the fixture's rows should already answer its filters")
        assertFalse(
            s.refreshed().library.fresh,
            "a pull left the Library fresh, so the web's loadFor asks nothing",
        )
    }

    @Test
    fun aPullSpinsUntilThePagesFetchLands() {
        val pulled = library().refreshed()
        assertTrue(pulled.refreshing, "a pull on the Library is not refreshing")

        val landed = pulled.copy(library = pulled.library.loaded(emptyList(), 0)).settled()
        assertFalse(landed.refreshing, "the rows landed and the spinner is still up")
        assertNull(landed.pulled, "the pull outlived the fetch it was waiting on")
    }

    @Test
    fun aFailedFetchStopsTheSpinnerToo() {
        val pulled = library().refreshed()
        val failed = pulled.copy(library = pulled.library.failed("offline")).settled()
        assertFalse(failed.refreshing, "a failed refresh left the spinner up forever")
    }

    @Test
    fun openingAPageIsNotAPull() {
        // Every navigation fetches. A spinner at the top of every page
        // you open would be a pull nobody made.
        val opened = AppState().navigate(View.DECKS).fetching()
        assertFalse(opened.refreshing, "a page loading because you went to it shows the pull spinner")
    }

    @Test
    fun aPullThatIsStillWaitingDoesNotFollowYouToTheNextPage() {
        val pulled = library().refreshed()
        val moved = pulled.navigate(View.STATS).fetching()
        assertFalse(moved.refreshing, "the Library's pull spun on the Stats page")
    }

    @Test
    fun everyPageThatFetchesCanBePulled() {
        val pages = mapOf(
            View.LIBRARY to library(),
            View.DECKS to AppState(route = Route(View.DECKS), decks = DecksState().loaded(emptyList())),
            View.STATS to AppState(route = Route(View.STATS)),
            View.ADMIN to AppState(route = Route(View.ADMIN)),
            View.CARD to AppState(
                route = Route(View.CARD, "sol+ring"),
                card = CardDetail(name = "Sol Ring", nameNorm = "sol ring"),
            ),
        )
        pages.forEach { (view, s) ->
            assertEquals(view, s.view, "fixture for $view is on the wrong page")
            assertTrue(s.refreshed().refreshing, "a pull on $view is not refreshing")
        }
    }

    @Test
    fun aPullOnAPageWithNothingToFetchEndsAtOnce() {
        // Entry is a form. Nothing on it came from the server, so a
        // pull has nothing to wait for and must not leave a spinner.
        val entry = AppState(route = Route(View.ENTRY)).refreshed().settled()
        assertFalse(entry.refreshing, "a pull on Entry spins with nothing coming")
        assertNull(entry.pulled)
    }
}
