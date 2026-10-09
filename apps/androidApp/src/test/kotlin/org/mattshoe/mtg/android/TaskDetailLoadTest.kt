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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals

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

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

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

    private fun client() = HttpClient(
        MockEngine { request ->
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: ${request.url}")
            val body = when (request.url.encodedPath) {
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
}
