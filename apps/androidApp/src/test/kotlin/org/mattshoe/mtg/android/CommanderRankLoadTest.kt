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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals

/**
 * A commander's page says where EDHREC ranks it among commanders.
 *
 * Matt: "I'm literally looking at teysa karlov here and she's 53 ...
 * but you have her WAY THE FUCK OFF IN THE THOUSANDS". The thousands
 * were her place among every card. Through the real `MainActivity`,
 * opened on her link, over a Worker that answers both questions.
 *
 * Sibling of the web's `CommanderRankTest`.
 */
@RunWith(AndroidJUnit4::class)
class CommanderRankLoadTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null
    private val asked = mutableListOf<String>()

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun worker() = HttpClient(
        MockEngine { request ->
            val sql = (request.body as? TextContent)?.text.orEmpty()
            val body = when {
                request.url.encodedPath == "/edhrec/commander" -> {
                    asked += request.url.parameters["name"].orEmpty()
                    """{"name":"Teysa Karlov","slug":"teysa-karlov","rank":58,"decks":21728}"""
                }
                "FROM card_faces cf" in sql ->
                    """{"cols":["face_index","name","mana_cost","type_line","oracle_text"],
                        "rows":[[99,"Teysa Karlov","{2}{W}{B}","Legendary Creature — Human Advisor",
                        "Creatures you control with tokens dying trigger twice."]],"n":1}"""
                "pick.edhrec_rank" in sql -> """{"cols":["edhrec_rank","rarity"],"rows":[[1330,"rare"]],"n":1}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun said(activity: MainActivity, label: String) =
        activity.stateForTesting().card?.facts?.lines?.firstOrNull { it.label == label }?.value

    @Test
    fun teysasPageSaysHerCommanderRankAndWhatTheOtherNumberIs() {
        asked.clear()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/card/teysa+karlov"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", worker()))
        built.create().start().resume()

        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (said(activity, "Commander rank") != null) return@repeat
            Thread.sleep(10)
        }
        looper.idle()

        assertEquals(View.CARD, activity.stateForTesting().view, "the link did not land on the card")
        assertEquals("#1,330 of all cards", said(activity, "EDHREC rank"), "the card page never loaded its details")
        assertEquals(
            "#58 on EDHREC", said(activity, "Commander rank"),
            "the page never said the commander rank; it asked EDHREC for $asked",
        )
        assertEquals(listOf("Teysa Karlov"), asked, "the Worker was asked more than once, or for the wrong card")
    }
}
