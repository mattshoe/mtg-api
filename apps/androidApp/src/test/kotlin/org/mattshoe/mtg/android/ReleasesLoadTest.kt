package org.mattshoe.mtg.android

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
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.GitHubReleases
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening Admin Settings on Android asks GitHub for the builds.
 *
 * Through the real `MainActivity`, for the reason `DeckTokensLoadTest`
 * gives: `ReleaseNotesParityTest` hands the screen a loaded `Releases`
 * and would stay green if nothing on the phone ever loaded one.
 * Sibling of the web's `AppDriverTest.openingAdminSettingsLoadsTheReleaseNotes`.
 */
@RunWith(AndroidJUnit4::class)
class ReleasesLoadTest {

    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun settle(activity: MainActivity) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(200) {
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
            val body = when {
                request.url.encodedPath.endsWith("/repos/mattshoe/mtg-api/releases") ->
                    """[{"tag_name":"android-v2.1.0-297","published_at":"2026-10-09T10:00:00Z",
                        "body":"Release notes in Admin Settings.\n\nBuilt from abc."}]"""
                request.url.encodedPath == "/admin/users" -> """{"users":[]}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun openingAdminSettingsLoadsTheReleaseNotes() {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", json()))
        activity.useGitHubForTesting(GitHubReleases.withEngine(json()))
        built.create().start().resume()
        val me = Account(slug = "matt", role = "admin")
        val s = activity.stateForTesting()
        activity.setStateForTesting(s.copy(admin = s.admin.signIn(me, "t")).navigate(Route(View.ADMIN)))
        activity.loadForTesting()
        settle(activity)

        val releases = activity.stateForTesting().releases
        assertEquals(
            listOf("2.1.0 (297)"),
            releases.rows.map { it.version },
            "nothing on Android loaded the release notes: $releases",
        )
        assertTrue(releases.rows.single().note == "Release notes in Admin Settings.")
    }
}
