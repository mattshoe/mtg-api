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
import org.mattshoe.mtg.core.Route

/**
 * Admin Settings asks the Worker for the tasks on every visit to the list,
 * and nobody else: GitHub was asked from the phone at sixty an hour.
 *
 * Through the real `MainActivity`, for the reason `ReleasesLoadTest`
 * gives. Unlike release notes, a task's status changes minute to
 * minute, so coming back to the list asks again; opening one person,
 * which is the same view, does not.
 * Sibling of the web's `AppDriverTest` tasks cases.
 */
@RunWith(AndroidJUnit4::class)
class TasksLoadTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null
    private var asked = 0

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun settle(activity: MainActivity) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(1000) {
            looper.idle()
            if (activity.tasksJob?.isCompleted == true) {
                looper.idle()
                return
            }
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun json() = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: ${request.url}")
            val body = when {
                // Created two hours and a half minute ago by the real clock, started an hour after.
                path == "/tasks" -> {
                    asked++
                    """{"tasks":[{"key":"ab12cd34","name":"task-status-in-the-app","title":"Task status in the app",
                        "status":"in review","pr":null,"created_at":"${java.time.Instant.now().minusSeconds(2 * 60 * 60 + 30)}",
                        "started_at":"${java.time.Instant.now().minusSeconds(60 * 60 + 30)}","finished_at":null}]}"""
                }
                path == "/releases" -> "[]"
                path == "/admin/users" -> """{"users":[{"key":"t4pee71g","name":"Test","role":"user"}]}"""
                path == "/auth/me" -> """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun openingAdminSettingsLoadsTheTasksAndComingBackAsksAgain() {
        // `Retry` runs this again on the same instance; a count carried
        // over from a failed attempt would fail every one after it.
        asked = 0
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
        val tasks = activity.stateForTesting().tasks
        assertEquals(
            listOf("Task status in the app" to "in review"),
            tasks.rows.map { it.title to it.status.word },
            "nothing on Android loaded the tasks: $tasks",
        )
        assertEquals(1, asked, "the first visit should ask the Worker once")

        activity.setStateForTesting(activity.stateForTesting().navigate(Route(View.ADMIN, "t4pee71g")))
        activity.loadForTesting()
        settle(activity)
        assertEquals(1, asked, "opening one person asked for the tasks again")

        activity.setStateForTesting(activity.stateForTesting().navigate(Route(View.ADMIN)))
        activity.loadForTesting()
        settle(activity)
        assertEquals(2, asked, "coming back to the list did not ask for the tasks again")
    }

    /**
     * The running task counts from when it was created to the phone's
     * own clock. `TasksParityTest` hands the screen a `now`;
     * this is the app finding one for itself.
     */
    @Test
    fun aRunningTaskCountsFromItsCreationToNow() {
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

        val tasks = activity.stateForTesting().tasks
        val task = tasks.rows.singleOrNull() ?: error("nothing loaded the tasks: $tasks")
        assertEquals("2h 00m", task.elapsed(tasks.now), "the running task does not count from its creation to now: $tasks")
    }
}
