package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where you can go, and which part of the chrome offers it.
 *
 * Matt, on the Android app: bottom navigation instead of a hamburger,
 * every item an icon and "a single short word"; drop the Query page,
 * "none of the apps need that"; a profile control in the top right
 * carrying admin status and the way in and out of it, with the server
 * log behind it; and Mass Entry in the bottom bar while unlocked,
 * "shorten the title to a single word".
 *
 * All of that is one question — which views exist, and where each one
 * is offered — so it is answered once here rather than twice in two
 * UIs. A bar that disagreed with a menu about what the app can do is
 * the bug this shape exists to prevent.
 */
class NavShapeTest {

    @Test
    fun thereIsNoQueryPage() {
        assertEquals(null, View.of("console"), "the Query page is still a view")
        assertTrue(
            View.entries.none { it.label.equals("Query", ignoreCase = true) },
            "something is still called Query: ${View.entries.map { it.label }}",
        )
    }

    @Test
    fun anOldQueryBookmarkLandsSomewhereUseful() {
        // Somebody has `#/console` in their history. A dropped page
        // must not be a blank screen — `Route.parse` already falls
        // back for an unknown view and this pins it.
        assertEquals(View.DEFAULT, Route.parse("#/console").view)
    }

    @Test
    fun everyItemInTheBarIsOneShortWord() {
        View.entries.filter { it.bar }.forEach { view ->
            assertFalse(
                view.label.trim().contains(' '),
                "\"${view.label}\" is not a single word, and it has to fit under an icon",
            )
            assertTrue(
                view.label.length <= 8,
                "\"${view.label}\" is ${view.label.length} characters; a bar label has room for 8",
            )
        }
    }

    @Test
    fun theBarIsTheThreeAnybodyCanReach() {
        assertEquals(
            listOf(View.LIBRARY, View.DECKS, View.STATS),
            Admin().bar,
            "a locked app's bottom bar is wrong",
        )
    }

    @Test
    fun unlockingAddsEntryToTheBarAndNothingElse() {
        val unlocked = Admin().signIn(Account(slug = "matt", role = "admin"), "t")
        assertEquals(
            listOf(View.LIBRARY, View.DECKS, View.STATS, View.ENTRY),
            unlocked.bar,
            "Mass Entry should join the bar when admin is on, and the log should not",
        )
    }

    @Test
    fun massEntryIsOneWordInTheBar() {
        assertEquals("Entry", View.ENTRY.label)
    }

    @Test
    fun theServerLogLivesBehindTheProfileAndNotInTheBar() {
        assertFalse(View.LOGS.bar, "the server log is in the bottom bar")
        val unlocked = Admin().signIn(Account(slug = "matt", role = "admin"), "t")
        assertEquals(listOf(View.LOGS), unlocked.behindProfile)
        assertEquals(emptyList(), Admin().behindProfile, "a locked app offers the log")
    }

    @Test
    fun aCardIsInNeitherBecauseItIsNotAPlaceYouGo() {
        assertFalse(View.CARD.bar)
        assertTrue(View.CARD !in Admin().signIn(Account(slug = "matt", role = "admin"), "t").behindProfile)
    }

    @Test
    fun everythingReachableIsOfferedSomewhere() {
        // The rule that keeps the two lists honest: if a view is
        // reachable and is a place you navigate to, some piece of
        // chrome has to offer it. Otherwise it is a screen with no
        // door.
        listOf(Admin(), Admin().signIn(Account(slug = "matt", role = "admin"), "t")).forEach { admin ->
            val offered = (admin.bar + admin.behindProfile).toSet()
            admin.visible.forEach { view ->
                assertTrue(
                    view in offered,
                    "${view.label} is reachable but nothing offers it (admin=${admin.unlocked})",
                )
            }
        }
    }

    @Test
    fun nothingIsOfferedTwice() {
        listOf(Admin(), Admin().signIn(Account(slug = "matt", role = "admin"), "t")).forEach { admin ->
            val both = admin.bar.filter { it in admin.behindProfile }
            assertTrue(both.isEmpty(), "offered in two places at once: $both")
        }
    }
}
