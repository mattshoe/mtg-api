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
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "the server logs page shows no logs".
 *
 * Nothing on the website ever asked the Worker for them: `loadFor` had
 * no branch for the log, so the page drew its empty state over a list
 * nobody had fetched. The real page, over a stubbed Worker.
 * Sibling of Android's `LogsLoadTest`.
 */
class LogsLoadTest {

    private val base = "https://worker.test"

    private var realFetch: dynamic = null
    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null

    /** How many times the log was asked for. */
    private var asked = 0

    /** The Worker is down: the log answers 500. */
    private var broken = false

    @BeforeTest
    fun playTheWorker() {
        asked = 0
        broken = false
        // The Worker, behind a stubbed engine as `AppDriverTest` has it.
        // This played the Worker through the browser's `fetch` with the
        // real client, as `SessionCookieTest` does, and every MtgApp test
        // after it in the suite then stalled: RulesPageTest and
        // SessionCookieTest failed and the web suite ran 19 minutes.
        // The cookie is `SessionCookieTest`'s to prove.
        val engine = MockEngine { request ->
            val (status, body) = when (request.url.encodedPath) {
                "/logs" -> {
                    asked++
                    if (broken) HttpStatusCode.InternalServerError to """{"error":"D1 is unavailable"}"""
                    else HttpStatusCode.OK to LOGS
                }
                "/releases" -> HttpStatusCode.OK to "[]"
                else -> HttpStatusCode.OK to """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(MtgApi.withEngine(base, http), Scryfall.withEngine(http))
        // Who the session is goes by `window.fetch`; see `AppDriverTest`.
        if (realFetch == null) realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { url: dynamic, init: dynamic ->
            if ("$url".contains("/auth/me")) {
                Promise.resolve(
                    Response(
                        """{"key":"e7de0cb1","name":"Matt","role":"admin"}""",
                        ResponseInit(status = 200, headers = js("({'Content-Type':'application/json'})")),
                    ),
                )
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
        screen?.frame?.remove()
        screen = null
        // `replace` rather than setting the hash, here and below, so
        // these visits leave no steps in the history for a later test's
        // back to walk into. And the page put back at the top, where a
        // tablet test's frame expects to be seen.
        window.location.replace("#")
        window.scrollTo(0.0, 0.0)
    }

    private fun mount(hash: String, into: HTMLElement? = null): HTMLElement {
        window.location.replace(hash)
        val view = into ?: (document.createElement("div") as HTMLElement).also {
            document.body!!.appendChild(it)
            roots += it
        }
        MtgApp.mount(view, null, "")
        return view
    }

    private suspend fun tick() {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 40) }.await()
    }

    private suspend fun waitFor(what: String, root: HTMLElement, cond: () -> Boolean) {
        repeat(125) { if (cond()) return; tick() }
        error("gave up waiting for $what: ${root.textContent}")
    }

    private fun rows(root: HTMLElement): List<String> =
        root.querySelectorAll("tbody tr").let { n -> (0 until n.length).map { n.item(it)!!.unsafeCast<HTMLElement>().textContent.orEmpty() } }

    @Test
    fun theServerLogShowsWhatTheWorkerLogged() = runTest {
        val root = mount("#/logs")
        waitFor("the log", root) { rows(root).isNotEmpty() || "Nothing logged." in root.textContent.orEmpty() }
        val shown = rows(root)
        assertEquals(2, shown.size, "the server log page shows no logs: ${root.textContent}")
        assertTrue("/cards/add" in shown[0] && "500" in shown[0], shown[0])
        assertTrue("/decks" in shown[1], shown[1])
        assertTrue("Errors only (1)" in root.textContent.orEmpty(), root.textContent.orEmpty())
        assertEquals(1, asked, "the log should be asked for once")
    }

    @Test
    fun aFailedLogSaysWhyStopsSpinningAndTheNextVisitAsksAgain() = runTest {
        broken = true
        val root = mount("#/logs")
        waitFor("the refusal on the server log", root) { "D1 is unavailable" in root.textContent.orEmpty() }
        val text = root.textContent.orEmpty()
        assertFalse("Loading…" in text, "the log is still spinning over a failure: $text")
        assertFalse("Nothing logged." in text, "a failure reads as an empty log: $text")

        broken = false
        window.location.replace("#/search")
        tick()
        window.location.replace("#/logs")
        waitFor("the log on the second visit", root) { rows(root).size == 2 }
        assertFalse("D1 is unavailable" in root.textContent.orEmpty(), "the old failure is still on screen")
    }

    private suspend fun onATablet(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val root = mount("#/logs", into = s.body)
        val on = "on a ${size.first} by ${size.second} screen"
        waitFor("the log $on", root) { rows(root).size == 2 }
        val doc = s.frame.contentDocument!!
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val table = root.querySelector("table")!!.unsafeCast<HTMLElement>().getBoundingClientRect()
        assertTrue(table.width > 0.0 && table.height > 0.0, "$on the log table is not drawn")
        assertTrue(table.top < size.second, "$on the log starts below the screen at ${table.top}px")
    }

    @Test
    fun theServerLogShowsOnAFoldingPhoneOpenedOut() = runTest { onATablet(Tablet.UNFOLDED) }

    @Test
    fun theServerLogShowsOnATabletHeldSideways() = runTest { onATablet(Tablet.LANDSCAPE) }

    private companion object {
        val LOGS = """{"rows":[
            {"id":9,"ts":"2026-10-16T16:45:10.949Z","level":"error","event":"cards.add","method":"POST",
             "path":"/cards/add","status":500,"ms":812,"message":"boom","detail":null},
            {"id":8,"ts":"2026-10-16T16:44:00.000Z","level":"info","event":"decks","method":"POST",
             "path":"/decks","status":200,"ms":14,"message":null,"detail":null}
        ],"total":2,"limit":200,"offset":0}"""
    }
}
