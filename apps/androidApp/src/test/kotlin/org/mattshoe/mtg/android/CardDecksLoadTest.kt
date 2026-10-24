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
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page's "In decks" lists your decks, not everybody's.
 *
 * Matt: "the 'in decks' section keeps showing decks from other peoples
 * fucking collection". Through the real `MainActivity`, signed in, opened
 * on Sol Ring's link, over a Worker that answers the decks query the way
 * the SQL does: scoped to the key it was sent, or every deck when it was
 * sent none. `test/card-decks.test.js` runs the real SQL.
 *
 * Sibling of the web's `CardDecksTest`.
 */
@RunWith(AndroidJUnit4::class)
class CardDecksLoadTest {

    private var controller: ActivityController<MainActivity>? = null

    private val prefs get() =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mtg", Context.MODE_PRIVATE)

    @Before
    fun signedIn() {
        prefs.edit().clear().putString(AdminToken.KEY, "t").commit()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        prefs.edit().clear().commit()
    }

    private val mine = """["m1n3d3ck","Fairy Deck","e7de0cb1","Matt",0,1,"spell"]"""
    private val kaylas = """["k4yd3ck0","Valgavoth","k4yy0003","Kayla M",0,1,"spell"]"""

    private fun worker() = HttpClient(
        MockEngine { request ->
            val sql = (request.body as? TextContent)?.text.orEmpty()
            val body = when {
                request.url.encodedPath.endsWith("/auth/me") -> """{"key":"e7de0cb1","name":"Matt","role":"user"}"""
                "d.is_proxy, dc.qty" in sql -> {
                    val rows = when {
                        "1=0" in sql -> ""
                        "e7de0cb1" in sql -> mine
                        "k4yy0003" in sql -> kaylas
                        else -> "$mine,$kaylas"
                    }
                    """{"cols":["key","name","owner","owner_name","is_proxy","qty","role"],"rows":[$rows],"n":0}"""
                }
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun owners(activity: MainActivity) = activity.stateForTesting().card?.usedIn.orEmpty().map { it.owner }

    @Test
    fun yourCardPageListsYourDecksAndNoneOfKaylas() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/card/sol+ring"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", worker()))
        built.create().start().resume()

        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            val s = activity.stateForTesting()
            if (s.admin.signedIn && owners(activity).isNotEmpty() && s.card?.busy == false) return@repeat
            Thread.sleep(10)
        }
        repeat(20) { looper.idle(); Thread.sleep(10) }

        assertEquals(View.CARD, activity.stateForTesting().view, "the link did not land on the card")
        assertTrue(activity.stateForTesting().admin.signedIn, "the session never came back signed in")
        assertEquals(
            listOf("e7de0cb1"), owners(activity),
            "In decks lists somebody else's deck on your own card page, or none of yours",
        )
    }
}
