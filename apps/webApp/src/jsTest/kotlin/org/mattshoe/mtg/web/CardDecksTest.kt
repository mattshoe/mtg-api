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
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The card page's "In decks" lists your decks, not everybody's.
 *
 * Matt: "the 'in decks' section keeps showing decks from other peoples
 * fucking collection". The real app, signed in, mounted at Sol Ring's
 * address, over a Worker that answers the decks query the way the SQL
 * does: scoped to the key it was sent, or every deck when it was sent
 * none. `test/card-decks.test.js` runs the real SQL.
 *
 * Sibling of `CardDecksLoadTest` on the phone.
 */
class CardDecksTest {

    private val roots = mutableListOf<HTMLElement>()
    private var realFetch: dynamic = null

    private val mine = """["m1n3d3ck","Fairy Deck","e7de0cb1","Matt",0,1,"spell"]"""
    private val kaylas = """["k4yd3ck0","Valgavoth","k4yy0003","Kayla M",0,1,"spell"]"""

    @BeforeTest
    fun stubTheNetwork() {
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val json = when {
                "d.is_proxy, dc.qty" in body -> {
                    val rows = when {
                        "1=0" in body -> ""
                        "e7de0cb1" in body -> mine
                        "k4yy0003" in body -> kaylas
                        else -> "$mine,$kaylas"
                    }
                    """{"cols":["key","name","owner","owner_name","is_proxy","qty","role"],"rows":[$rows],"n":0}"""
                }
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(MtgApi.withEngine("https://example.test", http), Scryfall.withEngine(http))
        // Who the session is goes by `window.fetch`; see `AppDriverTest`.
        if (realFetch == null) realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { url: dynamic, init: dynamic ->
            if ("$url".contains("/auth/me")) {
                Promise.resolve(
                    Response(
                        """{"key":"e7de0cb1","name":"Matt","role":"user"}""",
                        ResponseInit(status = 200, headers = js("({'Content-Type':'application/json'})")),
                    ),
                )
            } else if ("$url".contains("/c/")) {
                Promise.resolve(collectionAt("$url"))
            } else {
                realFetch(url, init)
            }
        }
    }

    @AfterTest
    fun cleanUp() {
        val back = realFetch
        if (back != null) window.asDynamic().fetch = back
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        window.location.replace("#")
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
        window.location.replace(hash)
        val view = document.createElement("div") as HTMLElement
        document.body!!.appendChild(view)
        roots += view
        MtgApp.mount(view, null, "t")
        return view
    }

    /** The deck names drawn under the "In decks" heading, up to the next heading. */
    private fun HTMLElement.inDecks(): List<String> {
        val heads = querySelectorAll("h3").let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }
        var at: Element? = heads.firstOrNull { it.textContent?.trim() == "In decks" } ?: return emptyList()
        val names = mutableListOf<String>()
        while (true) {
            at = at?.nextElementSibling ?: break
            if (at.tagName.equals("H3", ignoreCase = true)) break
            val n = at.querySelectorAll(".t-name")
            (0 until n.length).forEach { i -> names += n[i]?.textContent.orEmpty() }
        }
        return names
    }

    @Test
    fun yourCardPageListsYourDecksAndNoneOfKaylas() = runTest {
        val page = mount("#/card/sol+ring")
        waitFor("the In decks section never listed a deck; it says ${page.inDecks()}") { page.inDecks().isNotEmpty() }
        repeat(10) { tick() }
        assertEquals(
            listOf("Fairy Deck"), page.inDecks(),
            "In decks lists somebody else's deck on your own card page, or none of yours",
        )
    }
}
