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
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.TaskStatus
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.mattshoe.mtg.core.openTaskKey

/**
 * A task's page, through the real `MainActivity`: an address naming a
 * task asks the Worker for it, and nobody else.
 *
 * `TaskDetailParityTest` covers the screen through `AppShell`; this is
 * the part it cannot see. Sibling of the web's `AppDriverTest` task
 * page journey.
 */
@RunWith(AndroidJUnit4::class)
class TaskDetailLoadTest {

    // The activity is built by hand here, so the rule has nothing to
    // launch — it only gives the tests a handle on the Compose tree
    // the activity puts up.
    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    private var controller: ActivityController<MainActivity>? = null

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
        error("gave up waiting for $what: ${activity.stateForTesting().taskDetail}")
    }

    /** What went up to `POST /tasks/status`. */
    private val statusWrites = mutableListOf<String>()

    private fun client() = HttpClient(
        MockEngine { request ->
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: ${request.url}")
            if (request.url.encodedPath == "/tasks/status") {
                statusWrites += (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            }
            val body = when (request.url.encodedPath) {
                "/tasks/status" -> """{"status":"cancelled"}"""
                "/tasks/ef56gh78" -> """{"key":"ef56gh78","name":"slow-decks","title":"Slow decks","status":"in progress",
                    "details":"The decks page is slow.","files":[]}"""
                "/auth/me" -> """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                "/admin/users" -> """{"users":[]}"""
                "/releases" -> "[]"
                "/tasks" -> """{"tasks":[{"key":"ab12cd34","name":"bigger-buttons","title":"Bigger buttons","status":"merged",
                    "pr":"https://github.com/mattshoe/mtg-api/pull/72","finished_at":"2026-10-08T12:00:00.000Z"}]}"""
                "/tasks/ab12cd34" -> """{"key":"ab12cd34","name":"bigger-buttons","title":"Bigger buttons","status":"merged",
                    "details":"Too small.","pr":"https://github.com/mattshoe/mtg-api/pull/72",
                    "files":[{"name":"shot.png","type":"image/png","data":"aGVsbG8="}]}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun anAddressNamingATaskLoadsItsRequestPullRequestAndFiles() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/admin/task/ab12cd34"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()

        until(activity, "the task's files") { it.taskDetail?.files?.isNotEmpty() == true }
        val d = activity.stateForTesting().taskDetail!!
        assertEquals("ab12cd34", d.key)
        assertEquals("Too small.", d.body, "the request was not loaded")
        assertEquals(72, d.pull, "the pull request was not loaded")
        assertEquals(listOf("shot.png"), d.files.map { it.name })
        assertEquals(false, d.busy, "the page still says it is loading")
    }

    /**
     * Matt: "i want the ability to cancel/abort tasks and any running
     * agent should be immediately killed". Through the real activity: the
     * cancel goes to the Worker as this task's row, cancelled, and the page
     * says so. `TaskDetailParityTest` presses the buttons through `AppShell`.
     */
    @Test
    fun cancellingATaskWritesCancelledToItsRowAndThePageSaysSo() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/admin/task/ef56gh78"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()
        until(activity, "the live task") { it.taskDetail?.task?.status == TaskStatus.IN_PROGRESS }

        activity.cancelTaskForTesting("ef56gh78")
        until(activity, "the page to say cancelled") { it.taskDetail?.task?.status == TaskStatus.CANCELLED }
        val up = statusWrites.single()
        assertTrue("\"key\":\"ef56gh78\"" in up && "\"status\":\"cancelled\"" in up, "what went up: $up")
        assertEquals(false, activity.stateForTesting().taskDetail!!.cancelling, "the page still says it is cancelling")
    }

    /**
     * Matt: "i can't tap done tasks to look at the details". The real
     * app: Admin Settings, Done opened, the done row pressed. Sibling
     * of the web's `AppDriverTest.tappingADoneTaskOpensItsPage`.
     */
    @Test
    fun tappingADoneTaskOpensItsPage() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/admin"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()

        until(activity, "the task list") { it.tasks.rows.isNotEmpty() }
        compose.onNodeWithTag("tasks-done-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("task-done").performScrollTo().performClick()
        compose.waitForIdle()
        until(activity, "the done task's page") { it.taskDetail?.body != null }
        val s = activity.stateForTesting()
        assertEquals("ab12cd34", s.openTaskKey, "tapping the done task did not open it: ${s.route}")
        assertTrue(
            compose.onAllNodes(hasTestTag("task-detail-body")).fetchSemanticsNodes().isNotEmpty(),
            "the done task's page is not on screen",
        )
    }
}
