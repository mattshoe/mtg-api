package org.mattshoe.mtg.android.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saved Fetch lists are the account's: a list the website saved is on
 * the phone, read over a real socket from the real `fetch_lists` table.
 *
 * Matt: "making a fetch list on web does not show on android and vice
 * versa". Signed in from the start, with a session the fake worker
 * knows, the way a phone that has signed in before opens.
 */
@RunWith(AndroidJUnit4::class)
internal class FetchListsJourneyTest : E2eTest() {

    private val fromTheWebsite =
        """[{"id":"w1","title":"From the website","at":"2026-10-17T00:00:00Z","list":"1 Sol Ring","result":null,"ticked":[]}]"""

    override fun beforeLaunch() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("mtg", Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, fake.session).commit()
        fake.saveFetchListsElsewhere(fromTheWebsite)
    }

    @Test
    fun aListSavedOnTheWebsiteIsOnTheFetchScreen() {
        until("the website's list never reached the phone: ${state().fetch.saved.map { it.title }}") {
            state().fetch.saved.any { it.title == "From the website" }
        }
        compose.onNodeWithContentDescription("Fetch").performClick()
        until("the Fetch screen never opened") { state().view == View.FETCH }
        compose.waitForIdle()
        node("fetch-saved:From the website").assertIsDisplayed()
    }
}
