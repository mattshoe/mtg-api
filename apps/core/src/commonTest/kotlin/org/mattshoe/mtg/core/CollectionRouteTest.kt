package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A collection's address.
 *
 * Matt: "I need these to be scoped in the url. If you go to the root
 * domain then you get redirected to your own collection with a slug
 * in the url if you are logged in. It's important that you can share
 * your own collection with other people. Otherwise if i copy the url
 * then they'll just go to their own fucking collection."
 *
 * Which is the whole argument: an address that means "whoever is
 * reading this" is not an address. `#/c/<key>/decks` names one
 * collection and names the same one for everybody, and the key is
 * random rather than a name so it neither collides nor gives anything
 * away.
 */
class CollectionRouteTest {

    private val key = "a1b2c3d4"

    @Test
    fun anAddressCanNameACollection() {
        val r = Route.parse("#/c/$key/decks")
        assertEquals(key, r.collection)
        assertEquals(View.DECKS, r.view)
    }

    @Test
    fun andEverythingUnderIt() {
        val r = Route.parse("#/c/$key/decks/alela")
        assertEquals(key, r.collection)
        assertEquals(View.DECKS, r.view)
        assertEquals("alela", r.rest)
    }

    @Test
    fun theQueryStringSurvivesIt() {
        val r = Route.parse("#/c/$key/search?q=bolt")
        assertEquals(key, r.collection)
        assertEquals(View.LIBRARY, r.view)
        assertEquals("q=bolt", r.query)
    }

    @Test
    fun aCollectionOnItsOwnIsThatCollectionsLibrary() {
        val r = Route.parse("#/c/$key")
        assertEquals(key, r.collection)
        assertEquals(View.DEFAULT, r.view)
    }

    @Test
    fun anAddressWithNoCollectionStillWorks() {
        // Every link that exists today, and every bookmark anybody
        // has.
        val r = Route.parse("#/decks/alela")
        assertEquals("", r.collection)
        assertEquals(View.DECKS, r.view)
        assertEquals("alela", r.rest)
    }

    @Test
    fun itSurvivesTheRoundTrip() {
        listOf(
            "#/c/$key/search",
            "#/c/$key/decks/alela",
            "#/c/$key/stats",
            "#/decks",
        ).forEach { assertEquals(it, Route.parse(it).toHash(), "$it did not survive") }
    }

    @Test
    fun theQuerySurvivesTheRoundTripToo() {
        assertEquals("#/c/$key/search?q=bolt", Route.parse("#/c/$key/search?q=bolt").toHash())
    }

    // ------------------------------------------- what the shell does with it

    @Test
    fun anAddressNamingACollectionIsTheCollectionYouAreLookingAt() {
        val me = Account(key = "mykey123", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t")).navigate(Route.parse("#/c/$key/decks"))
        assertEquals(key, s.route.collection)
    }

    @Test
    fun theRootSendsSomebodySignedInToTheirOwnCollection() {
        // "If you go to the root domain then you get redirected to
        // your own collection with a slug in the url."
        val me = Account(key = "mykey123", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t")).navigate(Route.parse("#/"))
        val home = assertNotNull(s.homeRoute())
        assertEquals("mykey123", home.collection)
        assertEquals("#/c/mykey123/search", home.toHash())
    }

    @Test
    fun aStrangerAtTheRootIsLeftWhereTheyAre() {
        // Nobody to redirect them to.
        val s = AppState().navigate(Route.parse("#/"))
        assertEquals(null, s.homeRoute())
    }

    @Test
    fun somebodyAlreadyOnACollectionIsNotRedirectedOffIt() {
        // The bug this prevents: following a shared link and being
        // bounced to your own collection, which is exactly what makes
        // a link worthless.
        val me = Account(key = "mykey123", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t")).navigate(Route.parse("#/c/$key/decks"))
        assertEquals(null, s.homeRoute(), "a shared link bounced to the reader's own collection")
    }

    @Test
    fun signedInAndNamingNothingYouSeeTheCollectionAtYourKey() {
        // The app holds the owner's key and never the id behind it;
        // the database turns one into the other. So signed in and
        // naming nothing, what is on screen is the collection at your
        // own key, and it is yours to edit.
        val me = Account(key = "mykey123", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t"))
        assertEquals("mykey123", s.viewing, "your own collection is not the one at your key")
        assertTrue(s.canEdit)
    }
}

/**
 * A shared link, opened on a phone.
 *
 * `Deeplink` is what turns an `mtg.mattshoe.org` URL into a route,
 * and it is shared with the website's own address parsing precisely
 * so a link cannot open one place in a browser and another in the
 * app. A collection in the address has to survive that trip or a
 * link somebody sent opens the reader's own collection, which is the
 * one thing a link is for not doing.
 */
class SharedLinkTest {

    private val key = "a1b2c3d4"

    @Test
    fun aSharedCollectionSurvivesTheTripThroughADeepLink() {
        val landed = Deeplink.parse("https://mtg.mattshoe.org/#/c/$key/decks/alela")
        assertEquals(key, landed?.collection)
        assertEquals(View.DECKS, landed?.view)
        assertEquals("alela", landed?.rest)
    }

    @Test
    fun anAddressWithNoCollectionStillLands() {
        assertEquals(View.DECKS, Deeplink.parse("https://mtg.mattshoe.org/#/decks")?.view)
        assertEquals("", Deeplink.parse("https://mtg.mattshoe.org/#/decks")?.collection)
    }

    @Test
    fun somebodyElsesLinkIsNotPermissionToEditIt() {
        // The property the whole key design rests on: following a
        // link makes you a reader of that collection and nothing
        // more, whoever you are signed in as.
        val me = Account(key = "mykey123", name = "Matt")
        val s = AppState(admin = Admin().signIn(me, "t"))
            .navigate(Deeplink.parse("https://mtg.mattshoe.org/#/c/$key/decks")!!)
            .browsing(key)
        assertEquals(key, s.viewing)
        assertTrue(!s.canEdit, "a shared link handed out edit rights")
    }
}
