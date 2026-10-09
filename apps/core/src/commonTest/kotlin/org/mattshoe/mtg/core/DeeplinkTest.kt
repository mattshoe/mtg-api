package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An `mtg.mattshoe.org` link, opened on the phone.
 *
 * Matt: "I want the Android app to support deeplinks for
 * mtg.mattshoe.org".
 *
 * The site is hash-routed, so every address the app has to understand
 * already has a parser — `Route.parse`, which the website has used
 * since it was written, plus `FilterUrl.fromHash` for the Library's
 * filters. The deep link must land on exactly what the browser would
 * land on, so the answer is to share that code rather than write a
 * second reading of the same URL. `Share.link` builds these addresses
 * and this reads them; a round trip between the two is the test that
 * matters most.
 *
 * It lives in `:core` because it is a fact about a URL, not about
 * Android.
 */
class DeeplinkTest {

    @Test
    fun theSiteItselfOpensTheDefaultView() {
        val landing = Deeplink.parse("https://mtg.mattshoe.org/")
        assertEquals(View.DEFAULT, landing?.view)
    }

    @Test
    fun aDeckLinkOpensThatDeck() {
        val landing = Deeplink.parse("https://mtg.mattshoe.org/#/decks/alela")
        assertEquals(View.DECKS, landing?.view)
        assertEquals("alela", landing?.rest)
    }

    @Test
    fun aCardLinkOpensThatCard() {
        val landing = Deeplink.parse("https://mtg.mattshoe.org/#/card/matt:sol+ring")
        assertEquals(View.CARD, landing?.view)
        assertEquals("matt:sol+ring", landing?.rest)
    }

    @Test
    fun aSearchLinkKeepsItsFilters() {
        // The Library's filters live in the query string, so a link to
        // a search that arrives without them is a link to the Library.
        val landing = Deeplink.parse("https://mtg.mattshoe.org/#/search?q=bolt&colors=R")
        assertEquals(View.LIBRARY, landing?.view)
        assertEquals("q=bolt&colors=R", landing?.query)
    }

    @Test
    fun aSharedLinkOpensWhatItNames() {
        // What the share button hands out: the route in the path of the
        // Worker's preview page, so a pasted link previews as the deck.
        val landing = Deeplink.parse("https://mtg-api.mattshoe81.workers.dev/s/c/k4yy0003/decks/d0000020")
        assertEquals(View.DECKS, landing?.view)
        assertEquals("d0000020", landing?.rest)
        assertEquals("k4yy0003", landing?.collection)
    }

    @Test
    fun aSharedSearchKeepsItsFilters() {
        val landing = Deeplink.parse("https://mtg-api.mattshoe81.workers.dev/s/search?q=bolt&colors=R")
        assertEquals(View.LIBRARY, landing?.view)
        assertEquals("q=bolt&colors=R", landing?.query)
    }

    @Test
    fun theRestOfTheApiIsNotALink() {
        listOf(
            "https://mtg-api.mattshoe81.workers.dev/query",
            "https://mtg-api.mattshoe81.workers.dev/",
            "https://mtg-api.mattshoe81.workers.dev/sx/decks/alela",
            "https://evil.workers.dev/s/decks/alela",
        ).forEach { url -> assertNull(Deeplink.parse(url), url) }
    }

    @Test
    fun everyLinkTheAppItselfBuildsCanBeReadBackAgain() {
        // The round trip. `Share.link` is what the share button hands
        // out; if this app cannot open its own links then the deep
        // link is decorative.
        val states = listOf(
            AppState().navigate(Route(View.DECKS, "alela")),
            AppState().navigate(Route(View.CARD, "matt:sol+ring")),
            AppState().navigate(View.STATS),
            AppState().navigate(View.LOGS),
            AppState(),
        )
        states.forEach { state ->
            val link = Share.link(state)
            val back = Deeplink.parse(link)
            assertEquals(state.view, back?.view, link)
            assertEquals(state.route.rest, back?.rest.orEmpty(), link)
        }
    }

    @Test
    fun httpAndATrailingSlashAndNoFragmentAreAllFine() {
        // Links get rewritten by every messaging app there is.
        listOf(
            "http://mtg.mattshoe.org/#/decks/alela",
            "https://mtg.mattshoe.org#/decks/alela",
            "https://www.mtg.mattshoe.org/#/decks/alela",
            "https://MTG.MattShoe.org/#/decks/alela",
        ).forEach { url ->
            val landing = Deeplink.parse(url)
            assertEquals(View.DECKS, landing?.view, url)
            assertEquals("alela", landing?.rest, url)
        }
    }

    @Test
    fun somebodyElsesLinkIsNotOurs() {
        // The manifest filter is the first line of this and the
        // parser is the second. A host that merely ends in the right
        // letters is the classic way past a naive check.
        listOf(
            "https://example.com/#/decks/alela",
            "https://notmtg.mattshoe.org/#/decks/alela",
            "https://mtg.mattshoe.org.evil.com/#/decks/alela",
            "https://mattshoe.org/#/decks/alela",
        ).forEach { url -> assertNull(Deeplink.parse(url), url) }
    }

    @Test
    fun rubbishIsNotAnOpenInvitation() {
        listOf("", "   ", "not a url", "mailto:matt@example.com").forEach { url ->
            assertNull(Deeplink.parse(url), url)
        }
    }

    @Test
    fun aLinkWithNoFragmentDoesNotLandOnACard() {
        // `#/card` with nothing after it names no card, and opening a
        // card view with no reference is a blank screen with a back
        // button.
        val landing = Deeplink.parse("https://mtg.mattshoe.org/#/card")
        assertFalse(landing?.namesACard ?: true, "an empty card route was treated as a card")
    }

    @Test
    fun theLandingIsAStateTheAppCanAdopt() {
        // Not just a route: the Library's filters have to be restored
        // too, the same way `MtgApp.mount` does it on the web, or a
        // shared search opens showing everything.
        val next = Deeplink.landing(
            AppState(),
            "https://mtg.mattshoe.org/#/search?q=bolt",
        )
        assertEquals(View.LIBRARY, next.view)
        assertEquals("bolt", next.library.filters.q)
    }

    @Test
    fun aLandingLeavesStateItDoesNotOwnAlone() {
        // Arriving from a link must not sign you out or bin an
        // unsaved list.
        val before = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
            .let { it.copy(entry = it.entry.type("4 Lightning Bolt")) }
        val after = Deeplink.landing(before, "https://mtg.mattshoe.org/#/decks/alela")
        assertTrue(after.admin.unlocked, "a deep link locked the app")
        assertTrue(after.entry.list.contains("Lightning Bolt"), "a deep link binned the entry list")
    }
}
