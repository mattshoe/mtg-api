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
 * The website's session is a cookie, and the client has to send it.
 *
 * Matt: "admin settings does not work on web. 'Could not load tasks:
 * that needs the admin or agent role'". The site holds no token — the
 * session is an HttpOnly cookie on the API's domain — and the browser
 * only sends a cookie to another domain when the fetch says
 * `credentials: 'include'`. `/auth/me` said it, by hand, so the page
 * knew Matt was an admin and drew Admin Settings. Every call through
 * `MtgApi` went out through Ktor's fetch, which says nothing, so the
 * Worker saw a stranger and refused the tasks.
 *
 * `AppDriverTest` answers through a `MockEngine`, which never reaches
 * a browser fetch at all, so it could not see this. Here the client is
 * the real one and the browser's `fetch` plays the Worker: it lets a
 * call in only when the cookie would have gone with it.
 */
class SessionCookieTest {

    private val base = "https://worker.test"

    private var realFetch: dynamic = null
    private val roots = mutableListOf<HTMLElement>()

    /** The credentials mode of every fetch, by URL, as the browser was asked. */
    private val asked = mutableListOf<Pair<String, String?>>()

    /** The session has expired: refused even with the cookie. */
    private var expired = false

    @BeforeTest
    fun playTheWorker() {
        asked.clear()
        expired = false
        realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { input: dynamic, init: dynamic ->
            val url = if (jsTypeOf(input) == "string") input as String else input.url as String
            val mode = init?.credentials as? String
            asked += url to mode
            val signedIn = mode == "include" && !expired
            val path = url.substringAfter("://").substringAfter('/', "").let { "/" + it.substringBefore('?') }
            val (status, body) = when {
                path == "/auth/me" ->
                    if (mode == "include") 200 to """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                    else 200 to "{}"
                path == "/tasks" || path == "/admin/users" ->
                    if (signedIn) 200 to (if (path == "/tasks") TASKS else """{"users":[]}""")
                    else 403 to """{"error":"that needs the admin or agent role"}"""
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
        window.location.hash = ""
    }

    private fun mount(hash: String): HTMLElement {
        window.location.hash = hash
        val view = document.createElement("div") as HTMLElement
        val nav = document.createElement("div") as HTMLElement
        document.body!!.appendChild(view)
        document.body!!.appendChild(nav)
        roots += view
        roots += nav
        MtgApp.mount(view, null, "")
        MtgApp.mountNav(nav)
        return view
    }

    private suspend fun tick() {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 40) }.await()
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        repeat(125) { if (cond()) return; tick() }
        error("gave up waiting for $what")
    }

    @Test
    fun adminSettingsOnTheWebLoadsTheTasks() = runTest {
        val root = mount("#/admin")
        waitFor("Admin Settings to say something about the tasks") {
            "Task status in the app" in root.textContent.orEmpty() ||
                "Could not load tasks" in root.textContent.orEmpty()
        }
        val text = root.textContent.orEmpty()
        assertFalse("Could not load tasks" in text, "the tasks call went without the session cookie: $text")
        assertTrue("Task status in the app" in text, text)
    }

    @Test
    fun theWorkersCallsCarryTheCookieAndNobodyElsesDo() = runTest {
        val api = MtgApi(base)
        api.tasks("")
        window.fetch("https://api.scryfall.com/cards/autocomplete?q=sol").await()
        val worker = asked.filter { it.first.startsWith(base) }
        assertTrue(worker.isNotEmpty(), "the client never reached the browser's fetch")
        assertTrue(worker.all { it.second == "include" }, "a call to the Worker went without the cookie: $worker")
        val elsewhere = asked.filter { it.first.startsWith("https://api.scryfall.com") }
        assertEquals(listOf<String?>(null), elsewhere.map { it.second }, "the cookie was offered to Scryfall")
    }

    /**
     * The session ran out. The person is told why, nothing spins, and
     * coming back to the list asks again rather than keeping the refusal.
     */
    @Test
    fun anExpiredSessionSaysSoAndTheNextVisitAsksAgain() = runTest {
        expired = true
        val root = mount("#/admin")
        waitFor("the refusal on Admin Settings") { "Could not load tasks" in root.textContent.orEmpty() }
        val text = root.textContent.orEmpty()
        assertTrue("that needs the admin or agent role" in text, text)
        assertFalse("Loading…" in text, "the tasks panel is still spinning over a refusal: $text")

        expired = false
        window.location.hash = "#/search"
        tick()
        window.location.hash = "#/admin"
        waitFor("the tasks on the second visit") { "Task status in the app" in root.textContent.orEmpty() }
    }

    private companion object {
        const val TASKS = """{"tasks":[{"key":"ab12cd34","name":"task-status-in-the-app","title":"Task status in the app",""" +
            """"status":"in review","pr":null,"created_at":"2026-10-16T09:00:00.000Z",""" +
            """"started_at":"2026-10-16T09:05:00.000Z","finished_at":null}]}"""
    }
}
