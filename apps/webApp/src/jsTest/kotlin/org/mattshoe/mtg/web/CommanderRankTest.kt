package org.mattshoe.mtg.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Scryfall
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A commander's page says where EDHREC ranks it among commanders.
 *
 * Matt: "I'm literally looking at teysa karlov here and she's 53 ...
 * but you have her WAY THE FUCK OFF IN THE THOUSANDS". The thousands
 * were her place among every card; EDHREC's commander page says her
 * place among commanders. The real app, mounted at her address, over a
 * Worker that answers both questions.
 *
 * Sibling of `CommanderRankParityTest` on the phone.
 */
class CommanderRankTest {

    private val roots = mutableListOf<HTMLElement>()

    /** Every name the app asked the Worker to rank. */
    private val asked = mutableListOf<String>()

    @BeforeTest
    fun stubTheNetwork() {
        asked.clear()
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val json = when {
                request.url.encodedPath == "/edhrec/commander" -> {
                    asked += request.url.parameters["name"].orEmpty()
                    """{"name":"Teysa Karlov","slug":"teysa-karlov","rank":58,"decks":21728}"""
                }

                "FROM card_faces cf" in body ->
                    """{"cols":["face_index","name","mana_cost","type_line","oracle_text"],""" +
                        """"rows":[[99,"Teysa Karlov","{2}{W}{B}","Legendary Creature — Human Advisor",""" +
                        """"Creatures you control with tokens dying trigger twice."]],"n":1}"""

                "pick.edhrec_rank" in body ->
                    """{"cols":["edhrec_rank","rarity"],"rows":[[1330,"rare"]],"n":1}"""

                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(MtgApi.withEngine("https://example.test", http), Scryfall.withEngine(http))
    }

    @AfterTest
    fun cleanUp() {
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        window.location.hash = ""
    }

    private suspend fun tick() {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 40) }.await()
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        repeat(125) { if (cond()) return; tick() }
        error(what)
    }

    private fun mount(hash: String): HTMLElement {
        window.location.hash = hash
        val view = document.createElement("div") as HTMLElement
        document.body!!.appendChild(view)
        roots += view
        MtgApp.mount(view, null, "")
        return view
    }

    /** The label beside each value in the Details section, as drawn. */
    private fun HTMLElement.facts(): Map<String, String> =
        querySelectorAll(".fact").let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }
            .associate { row ->
                (row.querySelector(".fact-label") as HTMLElement).textContent.orEmpty() to
                    (row.querySelector(".fact-value") as HTMLElement).textContent.orEmpty()
            }

    @Test
    fun teysasPageSaysHerCommanderRankAndWhatTheOtherNumberIs() = runTest {
        val page = mount("#/card/teysa+karlov")
        waitFor("the card page never drew its details") { "EDHREC rank" in page.facts() }
        waitFor("the page never said the commander rank; it asked EDHREC for $asked, and says ${page.facts()}") {
            "Commander rank" in page.facts()
        }
        assertEquals("#58 on EDHREC", page.facts()["Commander rank"])
        assertEquals("#1,330 of all cards", page.facts()["EDHREC rank"], "the all-cards rank reads like a commander rank")
        assertEquals(listOf("Teysa Karlov"), asked, "the Worker was asked more than once, or for the wrong card")
    }
}
