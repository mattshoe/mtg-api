package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
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
 * nobody had fetched. The real client and the real page, with the
 * browser's `fetch` playing the Worker, as `SessionCookieTest` does —
 * so the session cookie has to go with the call for the log to come
 * back. Sibling of Android's `LogsLoadTest`.
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
        realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { input: dynamic, init: dynamic ->
            val url = if (jsTypeOf(input) == "string") input as String else input.url as String
            val withCookie = init?.credentials == "include"
            val path = url.substringAfter("://").substringAfter('/', "").let { "/" + it.substringBefore('?') }
            val (status, body) = when {
                path == "/auth/me" ->
                    if (withCookie) 200 to """{"key":"e7de0cb1","name":"Matt","role":"admin"}""" else 200 to "{}"
                path == "/logs" -> {
                    asked++
                    when {
                        broken -> 500 to """{"error":"D1 is unavailable"}"""
                        !withCookie -> 401 to """{"error":"admin mode required","admin_required":true}"""
                        else -> 200 to LOGS
                    }
                }
                path == "/releases" -> 200 to "[]"
                else -> 200 to """{"cols":[],"rows":[],"n":0}"""
            }
            Promise.resolve(
                Response(body, ResponseInit(status = status.toShort(), headers = js("({'Content-Type':'application/json'})"))),
            )
        }
        MtgApp.useForTesting(MtgApi(base), Scryfall())
    }

    @AfterTest
    fun cleanUp() {
        MtgApp.unmount()
        window.asDynamic().fetch = realFetch
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
        window.location.hash = ""
    }

    private fun mount(hash: String, into: HTMLElement? = null): HTMLElement {
        window.location.hash = hash
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
        root.querySelectorAll("tbody tr").let { n -> (0 until n.length).map { (n[it] as HTMLElement).textContent.orEmpty() } }

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
        window.location.hash = "#/search"
        tick()
        window.location.hash = "#/logs"
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
