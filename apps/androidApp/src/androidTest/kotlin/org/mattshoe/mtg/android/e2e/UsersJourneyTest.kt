package org.mattshoe.mtg.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Browsing somebody else's cards, against SQL that actually ran.
 *
 * Matt: "i should have a bottom nav option to browse users, then i can
 * select any user, and browse their cards". The seed has two accounts,
 * Matt (m4tt0001) and Kayla (k4yy0003), each with cards of their own.
 */
@RunWith(AndroidJUnit4::class)
internal class UsersJourneyTest : E2eTest() {

    private fun copiesOf(key: String): Int =
        fake.rows(
            "SELECT COALESCE(SUM(qty), 0) FROM cards WHERE owner_id = (SELECT id FROM users WHERE key = '$key')",
        ).single().single()!!.toDouble().toInt()

    @Test
    fun theUsersTabListsEverybodyAndATileShowsOnlyTheirCards() {
        compose.onNodeWithContentDescription("Users").performClick()
        until("the Users tab never filled") { state().users.rows.size == 2 }
        val kayla = state().users.find("k4yy0003") ?: error("Kayla is not in the list: ${state().users.rows}")
        assertEquals("Kayla M", kayla.shownName)
        assertEquals(copiesOf("k4yy0003"), kayla.cards, "Kayla's tile miscounts her cards")
        assertEquals("m4tt0001", state().users.rows.first().key, "the biggest collection is not first")
        compose.waitForIdle()
        node("user-tile-k4yy0003").assertIsDisplayed()

        node("user-tile-k4yy0003").performClick()
        until("Kayla's Library never filled") { state().library.rows.isNotEmpty() && !state().library.busy }
        assertEquals(Route(View.USERS, "k4yy0003"), state().route)
        assertTrue(
            state().library.rows.all { it.owner == "k4yy0003" },
            "Kayla's Library shows somebody else's cards: ${state().library.rows.map { it.owner }.toSet()}",
        )

        // The Library tab is not Kayla's because you looked at hers. For
        // a stranger, as this journey is, it is every collection.
        compose.onNodeWithContentDescription("Library").performClick()
        until("the Library tab never searched again") {
            !state().library.busy && state().library.rows.any { it.owner != "k4yy0003" }
        }
    }

    @Test
    fun aListThatFailsSaysWhyAndTryAgainGetsIt() {
        fake.usersFail = true
        compose.onNodeWithContentDescription("Users").performClick()
        until("the failure never reached the page") { state().users.error != null }
        assertTrue(!state().users.busy, "the spinner outlived the failure")
        compose.waitForIdle()
        node("users-error").assertIsDisplayed()
        assertTrue("D1 is down" in state().users.error.orEmpty(), "the page does not say what happened: ${state().users.error}")

        fake.usersFail = false
        node("users-retry").performClick()
        until("Try again never got the list") { state().users.rows.size == 2 }
        compose.waitForIdle()
        node("user-tile-m4tt0001").assertIsDisplayed()
    }
}
