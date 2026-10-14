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
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MtgApi
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Guide's rules page, through the real `MainActivity`: opening it
 * asks the Worker for the rules text once, and a refusal leaves no
 * spinner, says why, and Try again fetches it again.
 *
 * `RulesParityTest` covers the screen through `AppShell`; this is the
 * part it cannot see. Sibling of the web's `RulesPageTest`.
 */
@RunWith(AndroidJUnit4::class)
class RulesLoadTest {

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
        error("gave up waiting for $what: ${activity.stateForTesting().rules}")
    }

    /** How many times the app asked for the rules. */
    private var asked = 0

    /** Answer `/rules` with a 502 this many times before the text. */
    private var refusals = 0

    private val text = """
        |Magic: The Gathering Comprehensive Rules
        |
        |These rules are effective as of September 25, 2026.
        |
        |Credits
        |
        |7. Additional Rules
        |
        |702. Keyword Abilities
        |
        |702.19. Trample
        |
        |702.19a Trample is a static ability that modifies the rules for assigning an attacking creature’s combat damage.
        |
        |Glossary
    """.trimMargin()

    private fun client() = HttpClient(
        MockEngine { request ->
            if (request.url.host != "example.invalid") error("the app asked somebody other than the Worker: ${request.url}")
            if (request.url.encodedPath == "/rules") {
                asked++
                return@MockEngine if (asked <= refusals) {
                    respond(
                        """{"error":"Wizards answered 503 for the rules text"}""",
                        HttpStatusCode.BadGateway,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            }
            val body = when (request.url.encodedPath) {
                "/auth/me" -> """{"key":"e7de0cb1","name":"Matt","role":"user"}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun open(): MainActivity {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/guide/rules"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()
        return activity
    }

    @Test
    fun openingTheRulesPageFetchesTheRulesOnceAndParsesThemOnThePhone() {
        val activity = open()
        until(activity, "the rules to load") { it.rules.book != null }
        val rules = activity.stateForTesting().rules
        assertEquals(false, rules.busy, "the page still says it is loading")
        assertEquals(listOf("702.19.", "702.19a"), rules.book!!.rules.map { it.number })
        assertEquals(1, asked)
    }

    @Test
    fun aRefusalClearsTheSpinnerSaysWhyAndTryAgainFetchesThemAgain() {
        refusals = 1
        val activity = open()
        until(activity, "the load to fail") { it.rules.error != null }
        val failed = activity.stateForTesting().rules
        assertEquals(false, failed.busy, "the spinner is still up after the load failed")
        assertEquals("Wizards answered 503 for the rules text", failed.error)

        activity.loadRulesForTesting()
        until(activity, "the rules to load on the second try") { it.rules.book != null }
        assertNull(activity.stateForTesting().rules.error, "the old error is still on the page")
        assertEquals(2, asked)
        assertTrue(activity.stateForTesting().rules.book!!.rules.isNotEmpty())
    }
}
