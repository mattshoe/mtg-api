package org.mattshoe.mtg.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A link to somebody else's deck opens that deck.
 *
 * Matt: "i shared a link to my Liliana deck with Kayla, and the deck
 * doesn't load for her in the Android app". She sat on "Loading the
 * deck…" forever: the deck's own row was looked for in the shelf, and
 * the shelf is the collection on screen — hers — which has no deck of
 * Matt's in it.
 *
 * Through the real `MainActivity`, signed in as Kayla, opened on the
 * Worker's `/s/decks/<key>` link, the one Share copies. The fake
 * Worker scopes the shelf to the key it was sent the way the SQL does.
 */
@RunWith(AndroidJUnit4::class)
class SharedDeckLinkLoadTest {

    private var controller: ActivityController<MainActivity>? = null

    private val prefs get() =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mtg", Context.MODE_PRIVATE)

    @Before
    fun signedInAsKayla() {
        prefs.edit().clear().putString(AdminToken.KEY, "t").commit()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        prefs.edit().clear().commit()
    }

    private val deckCols = """["key","name","owner","owner_name","commander","colors","bracket","art_id"]"""
    private val kaylas = """["k4yd3ck0","Valgavoth","k4yy0003","Kayla M","Valgavoth","BR",3,null]"""
    private val matts = """["x5aqbxtw","Faultless Victory","e7de0cb1","Matt","Liliana the Faultless","B",3,null]"""

    /** Makes every deck query answer 500, the way a dropped database does. */
    private var deckQueryFails = false

    private fun worker() = HttpClient(
        MockEngine { request ->
            val sql = (request.body as? TextContent)?.text.orEmpty()
            if (deckQueryFails && "decks d" in sql) {
                return@MockEngine respond(
                    """{"error":"the database is down"}""",
                    HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val body = when {
                request.url.encodedPath.endsWith("/auth/me") -> """{"key":"k4yy0003","name":"Kayla M","role":"user"}"""
                "FROM deck_cards dc" in sql ->
                    """{"cols":["name","name_norm","qty","role","owned","type_line","scryfall_id"],""" +
                        """"rows":[["Sol Ring","sol ring",1,null,1,"Artifact",null]],"n":1}"""
                "FROM decks d" in sql -> {
                    // A deck by its key answers that deck whoever owns
                    // it; the shelf answers the collection it was asked
                    // about and nothing else.
                    val rows = when {
                        "x5aqbxtw" in sql -> matts
                        "n0d3ck00" in sql -> ""
                        "1=0" in sql -> ""
                        "k4yy0003" in sql -> kaylas
                        "e7de0cb1" in sql -> matts
                        else -> "$kaylas,$matts"
                    }
                    """{"cols":$deckCols,"rows":[$rows],"n":0}"""
                }
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun follow(url: String, done: (AppState) -> Boolean): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", worker()))
        built.create().start().resume()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (done(activity.stateForTesting())) return activity
            Thread.sleep(10)
        }
        repeat(20) { looper.idle(); Thread.sleep(10) }
        return activity
    }

    @Test
    fun kaylaOpensMattsSharedDeck() {
        val activity = follow("https://mtg-api.mattshoe81.workers.dev/s/decks/x5aqbxtw") {
            it.admin.signedIn && it.decks.open != null
        }
        val s = activity.stateForTesting()
        assertEquals(View.DECKS, s.view, "the link did not land on the decks view")
        assertEquals(
            "Faultless Victory", s.decks.open?.name,
            "the deck never opened: its row was looked for on Kayla's own shelf, so it sat on Loading the deck…",
        )
        assertEquals("e7de0cb1", s.decks.open?.owner, "the deck opened as somebody else's than Matt's")
        assertEquals(listOf("Sol Ring"), s.decks.cards.map { it.name }, "the deck opened without its cards")
        assertEquals(listOf("k4yd3ck0"), s.decks.decks.map { it.key }, "Matt's deck was put on Kayla's shelf")
    }

    @Test
    fun aLinkToADeckThatIsGoneSaysSoRatherThanLoadingForever() {
        val activity = follow("https://mtg-api.mattshoe81.workers.dev/s/decks/n0d3ck00") {
            it.admin.signedIn && it.decks.error != null
        }
        val s = activity.stateForTesting()
        assertNull(s.decks.open, "a deck that does not exist opened")
        assertNotNull(s.decks.error, "a deck that does not exist left the page on Loading the deck… with no error")
        assertEquals(false, s.decks.busy, "the spinner is still up over a deck that does not exist")
    }

    @Test
    fun aDeckQueryThatFailsSaysWhyAndCanBeTriedAgain() {
        deckQueryFails = true
        val activity = follow("https://mtg-api.mattshoe81.workers.dev/s/decks/x5aqbxtw") {
            it.admin.signedIn && it.decks.error != null
        }
        assertNotNull(activity.stateForTesting().decks.error, "a failed deck load left no error on the page")
        assertEquals(false, activity.stateForTesting().decks.busy, "the spinner is still up after the deck failed to load")

        // Pull to refresh, the way the page is tried again.
        deckQueryFails = false
        activity.setStateForTesting(activity.stateForTesting().refreshed())
        activity.loadForTesting()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(300) {
            looper.idle()
            if (activity.stateForTesting().decks.open != null) return@repeat
            Thread.sleep(10)
        }
        assertEquals(
            "Faultless Victory", activity.stateForTesting().decks.open?.name,
            "trying again after a failure did not open the deck",
        )
    }
}
