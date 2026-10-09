package org.mattshoe.mtg.android

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening Admin Settings on Android asks the Worker for the builds, and
 * nobody else: it keeps GitHub's list so the phone never asks GitHub.
 *
 * Through the real `MainActivity`, for the reason `DeckTokensLoadTest`
 * gives: `ReleaseNotesParityTest` hands the screen a loaded `Releases`
 * and would stay green if nothing on the phone ever loaded one.
 * Sibling of the web's `AppDriverTest.openingAdminSettingsLoadsTheReleaseNotes`.
 */
@RunWith(AndroidJUnit4::class)
class ReleasesLoadTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun settle(activity: MainActivity) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(1000) {
            looper.idle()
            if (activity.releasesJob?.isCompleted == true) {
                looper.idle()
                return
            }
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun json() = HttpClient(
        MockEngine { request ->
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: " + request.url)
            val body = when {
                request.url.encodedPath == "/releases" ->
                    """[{"tag_name":"android-v2.1.0-297","published_at":"2026-10-09T10:00:00Z",
                        "body":"Release notes in Admin Settings.\n\nBuilt from abc."}]"""
                request.url.encodedPath == "/admin/users" -> """{"users":[]}"""
                request.url.encodedPath == "/auth/me" ->
                    """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    /**
     * Launched the way a person gets there: a session already held, the
     * server saying it is an admin's, and a link to `#/admin`.
     *
     * Not by setting the state after `onCreate`. That raced the
     * launch's own work and lost under the full suite's load, with the
     * release notes left exactly as they started.
     */
    @Test
    fun openingAdminSettingsLoadsTheReleaseNotes() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/admin"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", json()))
        built.create().start().resume()
        settle(activity)

        assertEquals(View.ADMIN, activity.stateForTesting().view, "the link did not land on Admin Settings")
        val releases = activity.stateForTesting().releases
        assertEquals(
            listOf("2.1.0 (297)"),
            releases.rows.map { it.version },
            "nothing on Android loaded the release notes: $releases",
        )
        assertTrue(releases.rows.single().note == "Release notes in Admin Settings.")
    }
}
