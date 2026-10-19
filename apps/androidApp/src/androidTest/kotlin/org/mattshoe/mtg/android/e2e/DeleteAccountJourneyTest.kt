package org.mattshoe.mtg.android.e2e

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting Kayla's account from Admin Settings, through the real
 * `MainActivity` and the real `MtgApi` over a socket.
 *
 * Matt: "i want to be able to delete accounts via admin settings". The
 * journey a person takes: profile, Admin Settings, Kayla, the bin,
 * Delete. And the same with the server failing first, because a catch
 * block nothing ran is how the share flow broke three times: the
 * spinner has to stop, the page has to say why, and the bin has to work
 * again without restarting the app.
 */
@RunWith(AndroidJUnit4::class)
internal class DeleteAccountJourneyTest : E2eTest() {

    override fun beforeLaunch() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("mtg", Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, fake.session).commit()
        fake.role = "admin"
    }

    private fun kaylaIsThere() = fake.rows("SELECT COUNT(*) FROM users WHERE key = 'k4yy0003'").single().single() == "1"

    private fun copies(key: String) = fake.rows(
        "SELECT COALESCE(SUM(qty), 0) FROM cards WHERE owner_id = (SELECT id FROM users WHERE key = '$key')",
    ).single().single()

    private fun toKaylasPage() {
        until("the session never came back as an admin") { state().admin.account?.role == "admin" }
        compose.waitForIdle()
        node("profile").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Admin Settings")).performClick()
        until("Admin Settings never listed Kayla") { state().people.rows.any { it.key == "k4yy0003" } }
        compose.waitForIdle()
        node("person-k4yy0003").performScrollTo().performClick()
        until("Kayla's page never opened") { state().route == Route(View.ADMIN, "k4yy0003") }
        compose.waitForIdle()
    }

    private fun pressDelete() {
        compose.onNodeWithContentDescription(People.DELETE).performScrollTo().performClick()
        compose.waitForIdle()
        node("delete-account-confirm").performScrollTo().performClick()
    }

    @Test
    fun anAdminDeletesKaylaAndMattsCardsAreUntouched() {
        val mattsBefore = copies("m4tt0001")
        toKaylasPage()
        pressDelete()
        until("Kayla's account was never deleted") { !kaylaIsThere() }
        until("the page never went back to the list") { state().route == Route(View.ADMIN) }
        assertTrue(state().people.rows.none { it.key == "k4yy0003" }, "Kayla is still on the list")
        assertEquals("Deleted Kayla M's account", state().toast)
        assertEquals(mattsBefore, copies("m4tt0001"), "deleting Kayla changed Matt's cards")
    }

    @Test
    fun aDeleteTheServerFailsStopsTheSpinnerSaysWhyAndCanBeTriedAgain() {
        fake.deleteAccountFail = true
        toKaylasPage()
        pressDelete()
        until("the failure never reached the page") { state().people.error != null }
        assertEquals(null, state().people.deleting, "the spinner outlived the failure")
        assertTrue("D1 is down" in state().people.error.orEmpty(), "the page does not say what happened: ${state().people.error}")
        assertTrue(state().toastFailed, "the failure was toasted as a success")
        assertTrue(kaylaIsThere(), "a failed delete deleted Kayla")
        assertEquals(Route(View.ADMIN, "k4yy0003"), state().route, "a failure left Kayla's page")

        fake.deleteAccountFail = false
        pressDelete()
        until("trying again never deleted Kayla") { !kaylaIsThere() }
        until("the page never went back to the list") { state().route == Route(View.ADMIN) }
    }
}
