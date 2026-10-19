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
 * The same, with the worker failing the first read: the person is told,
 * and opening Fetch asks again without restarting the app.
 */
@RunWith(AndroidJUnit4::class)
internal class FetchListsFailedJourneyTest : E2eTest() {

    override fun beforeLaunch() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("mtg", Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, fake.session).commit()
        fake.saveFetchListsElsewhere(
            """[{"id":"w1","title":"From the website","at":"2026-10-17T00:00:00Z","list":"1 Sol Ring","result":null,"ticked":[]}]""",
        )
        fake.fetchListsFail = true
    }

    @Test
    fun aFailedReadSaysSoAndOpeningFetchAsksAgain() {
        until("nothing said the lists did not load: ${state().toast}") { state().toastFailed }
        assertEquals("Your saved Fetch lists did not load: D1 is down", state().toast)
        assertTrue(state().fetch.saved.isEmpty(), "lists appeared from a read that failed")

        fake.fetchListsFail = false
        // Fetch is a tile under Tools.
        compose.onNodeWithContentDescription("Tools").performClick()
        until("the Tools tab never opened") { state().view == View.TOOLS }
        compose.waitForIdle()
        node("tool-tile-fetch").performClick()
        until("opening Fetch did not ask for the lists again") {
            state().fetch.saved.any { it.title == "From the website" }
        }
        compose.waitForIdle()
        node("fetch-saved:From the website").assertIsDisplayed()
    }
}
