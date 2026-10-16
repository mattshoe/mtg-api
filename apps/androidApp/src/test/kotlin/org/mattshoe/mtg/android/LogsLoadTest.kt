package org.mattshoe.mtg.android

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "the server logs page shows no logs".
 *
 * Nothing on the phone ever asked the Worker for them: `loadFor` had
 * no branch for the log, so the screen drew "Nothing logged." over a
 * list nobody had fetched. Through the real `MainActivity`, as
 * `TasksLoadTest` does, because `LogsStatsParityTest` hands the
 * screen its lines and so could never see it. Sibling of the web's
 * `LogsLoadTest`.
 */
@RunWith(AndroidJUnit4::class)
class LogsLoadTest {

    // The activity is built by hand, so the rule launches nothing; it
    // is the handle on the Compose tree the activity puts up.
    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    private var controller: ActivityController<MainActivity>? = null
    private var asked = 0
    private var broken = false

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun until(activity: MainActivity, what: String, cond: (AppState) -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (cond(activity.stateForTesting())) return
            Thread.sleep(10)
        }
        error("gave up waiting for $what: ${activity.stateForTesting().logs}")
    }

    private fun client() = HttpClient(
        MockEngine { request ->
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: ${request.url}")
            val path = request.url.encodedPath
            if (path == "/logs") asked++
            val (status, body) = when {
                path == "/logs" && broken -> HttpStatusCode.InternalServerError to """{"error":"D1 is unavailable"}"""
                path == "/logs" -> HttpStatusCode.OK to LOGS
                path == "/auth/me" -> HttpStatusCode.OK to """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                path == "/releases" -> HttpStatusCode.OK to "[]"
                else -> HttpStatusCode.OK to """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun open(): MainActivity {
        asked = 0
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/logs"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()
        return activity
    }

    private fun showsTheLog() {
        broken = false
        val activity = open()
        until(activity, "the log") { it.view == View.LOGS && !it.logs.busy && (it.logs.lines.isNotEmpty() || it.logs.error != null) }
        val logs = activity.stateForTesting().logs
        assertEquals(
            listOf("/cards/add" to 500, "/decks" to 200),
            logs.lines.map { it.path to it.status },
            "the server log screen shows no logs: $logs",
        )
        assertNull(logs.error, "the log came back with an error: $logs")
        assertTrue(asked >= 1, "nothing asked the Worker for the log")

        // Drawn, not only held: the grid is on screen with the failed row first.
        compose.waitForIdle()
        compose.onNodeWithTag("log-grid").assertExists("the log has no grid")
        compose.onNodeWithTag("log-cell-0-3").assertExists("the first row has no path")
        val grid = compose.onNodeWithTag("log-grid").getUnclippedBoundsInRoot()
        assertTrue(grid.width > 0.dp && grid.height > 0.dp, "the log grid is not drawn: $grid")
        compose.onNodeWithTag("logs-empty").assertDoesNotExist()
    }

    @Test
    fun theServerLogShowsWhatTheWorkerLogged() = showsTheLog()

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theServerLogShowsOnAFoldingPhoneOpenedOut() = showsTheLog()

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theServerLogShowsOnATabletHeldSideways() = showsTheLog()

    @Test
    fun aFailedLogSaysWhyStopsSpinningAndAPullAsksAgain() {
        broken = true
        val activity = open()
        until(activity, "the failure") { it.logs.error != null }
        val failed = activity.stateForTesting().logs
        assertTrue("D1 is unavailable" in failed.error.orEmpty(), "the failure does not say what happened: $failed")
        assertFalse(failed.busy, "the log is still spinning over a failure")
        compose.waitForIdle()
        compose.onNodeWithTag("err").assertExists("the failure is not on screen")
        compose.onNodeWithTag("logs-busy").assertDoesNotExist()

        broken = false
        activity.refreshForTesting()
        until(activity, "the log after a pull") { it.logs.lines.size == 2 }
        assertNull(activity.stateForTesting().logs.error, "the old failure is still on screen")
        assertEquals(View.LOGS, activity.stateForTesting().view)
    }

    private companion object {
        val LOGS = """{"rows":[
            {"id":9,"ts":"2026-10-16T16:45:10.949Z","level":"error","event":"cards.add","method":"POST",
             "path":"/cards/add","status":500,"ms":812,"message":"boom","detail":null},
            {"id":8,"ts":"2026-10-16T16:44:00.000Z","level":"info","event":"decks","method":"POST",
             "path":"/decks","status":200,"ms":14,"message":null,"detail":null}
        ],"total":2,"limit":200,"offset":0}"""
    }
}
