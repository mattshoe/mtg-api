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
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "In a deck", from the real `MtgApp` over a stubbed Worker: the rows the
 * load actually decodes, and what is left when the deck read fails.
 *
 * `DeckPickerScopeTest` hands the shell a state. This is the half that
 * proves the app can produce that state. The phone's half is in
 * `MainActivityFacetsTest`.
 */
class DeckPickerLoadTest {

    private val roots = mutableListOf<HTMLElement>()
    private var realFetch: dynamic = null

    /** Makes the deck read answer a 500, the way an overloaded D1 does. */
    private var decksFail = false

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    fun stubTheNetwork() {
        decksFail = false
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            when {
                "FROM decks" in body && "owner" in body && "ORDER BY owner_id" in body ->
                    if (decksFail) respond("""{"error":"D1 is overloaded"}""", HttpStatusCode.InternalServerError, json)
                    else respond(
                        """{"cols":["key","name","owner","owner_key"],"rows":[
                            ["d1","Alela","Matt","e7de0cb1"],
                            ["d2","Korvold","Kayla","k4yy0003"]
                        ],"n":2}""",
                        HttpStatusCode.OK,
                        json,
                    )
                "AS kind" in body -> respond(
                    """{"cols":["kind","value"],"rows":[["types","Creature"],["layouts","normal"]],"n":2}""",
                    HttpStatusCode.OK,
                    json,
                )
                else -> respond("""{"cols":[],"rows":[],"n":0}""", HttpStatusCode.OK, json)
            }
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
                realFetch!!(url, init)
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
        window.location.hash = ""
    }

    private suspend fun tick() {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 40) }.await()
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        repeat(125) {
            if (cond()) return
            tick()
        }
        error("gave up waiting for $what")
    }

    private fun mount(): HTMLElement {
        window.location.hash = "#/c/e7de0cb1"
        val view = (document.createElement("div") as HTMLElement).also {
            it.style.width = "400px"
            document.body!!.appendChild(it)
            roots += it
        }
        MtgApp.mount(view, null, "")
        return view
    }

    private fun HTMLElement.deckOptions(): List<String>? {
        val selects = querySelectorAll("details[data-facet=collection] select")
        for (i in 0 until selects.length) {
            val select = selects[i].unsafeCast<HTMLElement>()
            if (select.textContent.orEmpty().contains("in any deck")) {
                val opts = select.querySelectorAll("option")
                return (0 until opts.length).map { opts[it].unsafeCast<HTMLElement>().textContent.orEmpty() }
            }
        }
        return null
    }

    private suspend fun HTMLElement.openCollection() {
        waitFor("the Collection group") { querySelector("details[data-facet=collection] summary") != null }
        querySelector("details[data-facet=collection] summary").unsafeCast<HTMLElement>().click()
        waitFor("the In a deck dropdown") { deckOptions() != null }
    }

    @Test
    fun theLoadedDeckListIsNarrowedToTheCollectionOnScreen() = runTest {
        val view = mount()
        view.openCollection()
        waitFor("the decks to arrive, and it still says ${view.deckOptions()}") {
            view.deckOptions().orEmpty().any { it.startsWith("Alela") }
        }
        assertEquals(listOf("any", "— in any deck —", "— in no deck —", "Alela"), view.deckOptions())
    }

    @Test
    fun aFailedDeckReadLeavesThePickerUsableWithNoDecksInIt() = runTest {
        decksFail = true
        val view = mount()
        view.openCollection()
        // Long enough for the failed read to have landed.
        repeat(10) { tick() }
        assertEquals(listOf("any", "— in any deck —", "— in no deck —"), view.deckOptions())
        assertTrue(
            !view.textContent.orEmpty().contains("Searching…"),
            "the Library is still searching after the panel's lists failed",
        )
    }
}
