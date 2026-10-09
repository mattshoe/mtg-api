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
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new task, through the real `MainActivity`: a file picked is read
 * whole onto the page, and Send puts the task and the file on the wire
 * to `/tasks` and comes back to Admin Settings.
 *
 * `NewTaskParityTest` covers the screen through `AppShell`; this is the
 * part it cannot see — the picker's answer being read, and the send.
 * Sibling of the web's `AppDriverTest` New task journey.
 */
@RunWith(AndroidJUnit4::class)
class NewTaskSendTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null
    private val sent = mutableListOf<String>()

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
        error("gave up waiting for $what: ${activity.stateForTesting().newTask}")
    }

    private fun client() = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path == "/tasks" && request.method.value == "GET" -> """{"tasks":[]}"""
                path == "/tasks" -> {
                    sent += (request.body as io.ktor.http.content.TextContent).text
                    """{"key":"ab12cd34","files":1}"""
                }
                path == "/auth/me" -> """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                path == "/admin/users" -> """{"users":[]}"""
                path == "/releases" -> "[]"
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun aPickedFileIsReadOntoThePageAndSendPutsItOnTheWire() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/admin/new-task"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()
        until(activity, "the New task page") { it.writingTask && it.admin.account != null }

        val shot = File.createTempFile("shot", ".png").apply { writeText("hello") }
        activity.readFiles(listOf(Uri.fromFile(shot)))
        until(activity, "the picked file on the page") { it.newTask.files.isNotEmpty() }
        val f = activity.stateForTesting().newTask.files.single()
        assertEquals(shot.name, f.name)
        assertEquals(5L, f.bytes)
        assertEquals("aGVsbG8=", f.data, "the file was not read whole as base64")

        activity.setStateForTesting(
            activity.stateForTesting().let {
                it.copy(newTask = it.newTask.titled("Bigger buttons").described("They are too small to hit."))
            },
        )
        activity.sendTask()
        until(activity, "Send to land back on Admin Settings") { it.route == Route(View.ADMIN) }

        val body = sent.single()
        assertTrue("Bigger buttons" in body && "They are too small to hit." in body, body)
        assertTrue(""""data":"aGVsbG8="""" in body, "the file did not go up: $body")
        assertEquals(
            "Task sent: Bigger buttons. It is a request within five minutes.",
            activity.stateForTesting().toast,
        )
    }
}
