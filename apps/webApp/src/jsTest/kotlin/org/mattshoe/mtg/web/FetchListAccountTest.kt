package org.mattshoe.mtg.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Scryfall
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
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
 * Saved Fetch lists are the account's, through `MtgApp` itself.
 *
 * Matt: "making a fetch list on web does not show on android and vice
 * versa". The Worker here is a stub that keeps what it is sent, so a
 * list saved on one "device" — a page mounted with its own storage —
 * is read back by the next one with storage wiped.
 *
 * Sibling of Android's `FetchListAccountTest`.
 */
class FetchListAccountTest {

    private val roots = mutableListOf<HTMLElement>()
    private var realFetch: dynamic = null

    /** What the stub Worker holds for the account: the JSON array. */
    private var account = "[]"
    private var loadFails = false
    private var saveFails = false
    private val sent = mutableListOf<String>()

    private val keys = listOf("mtg.fetch", "mtg.fetch.open", "mtg.fetch.synced")

    @BeforeTest
    fun stubTheNetwork() {
        keys.forEach { localStorage.removeItem(it) }
        account = "[]"
        loadFails = false
        saveFails = false
        sent.clear()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            val ok = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath.endsWith("/fetch-lists")) {
                if (request.method == HttpMethod.Get) {
                    return@MockEngine if (loadFails) respond("""{"error":"D1 is down"}""", HttpStatusCode.InternalServerError, ok)
                    else respond("""{"lists":$account}""", HttpStatusCode.OK, ok)
                }
                sent += body
                if (saveFails) return@MockEngine respond("""{"error":"D1 is down"}""", HttpStatusCode.InternalServerError, ok)
                account = Json.parseToJsonElement(body).jsonObject["lists"].toString()
                return@MockEngine respond("""{"ok":true}""", HttpStatusCode.OK, ok)
            }
            val json = when {
                "json_each" in body ->
                    """{"cols":["name_norm","name","face1","types","colors","color_identity","rarity","qty"],"rows":[""" +
                        """["counterspell","Counterspell","Counterspell","Instant","U","U","common",2]""" +
                        """],"n":1}"""
                request.url.encodedPath.endsWith("/cards/search") ->
                    """{"object":"error","code":"not_found","status":404}"""
                else -> """{"cols":["id"],"rows":[],"n":0}"""
            }
            respond(json, HttpStatusCode.OK, ok)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(MtgApi.withEngine("https://example.test", http), Scryfall.withEngine(http))
        if (realFetch == null) realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { url: dynamic, init: dynamic ->
            if ("$url".contains("/auth/me")) {
                Promise<Response> { ok, _ ->
                    ok(
                        Response(
                            """{"key":"e7de0cb1","name":"Matt","role":"user"}""",
                            ResponseInit(status = 200, headers = js("({'Content-Type':'application/json'})")),
                        ),
                    )
                }
            } else if ("$url".contains("/c/")) {
                Promise.resolve(collectionAt("$url"))
            } else {
                realFetch(url, init)
            }
        }
    }

    @AfterTest
    fun cleanUp() {
        if (realFetch != null) window.asDynamic().fetch = realFetch
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        keys.forEach { localStorage.removeItem(it) }
        window.location.hash = ""
    }

    private fun saved(id: String, title: String) =
        """{"id":"$id","title":"$title","at":"2026-10-17T00:00:00Z","list":"1 Counterspell","result":null,"ticked":[]}"""

    private suspend fun tick(ms: Int = 40) {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, ms) }.await()
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun waitFor(what: String, upTo: Int = 5000, cond: () -> Boolean) {
        var waited = 0
        while (waited < upTo) {
            if (cond()) return
            tick()
            waited += 40
        }
        error("gave up waiting for $what")
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

    /** Another device: nothing in its storage, the same account. */
    private fun anotherDevice(hash: String): HTMLElement {
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        keys.forEach { localStorage.removeItem(it) }
        return mount(hash)
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        all("button").filterIsInstance<HTMLButtonElement>().firstOrNull { (it.textContent?.trim() == label || it.getAttribute("aria-label") == label) }
            ?: error("no button labelled \"$label\"")

    private fun savedTitles(root: HTMLElement) = root.all("[data-fetch-saved]").map { it.getAttribute("data-fetch-saved") }

    private fun type(el: HTMLElement, text: String) {
        when (el) {
            is HTMLTextAreaElement -> el.value = text
            is HTMLInputElement -> el.value = text
        }
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
    }

    private fun badToast(): String? =
        document.querySelector(".toast.bad")?.textContent

    private suspend fun findAndSave(root: HTMLElement, title: String) {
        waitFor("the Fetch page") { root.all("#fetch-list").isNotEmpty() }
        type(root.all("#fetch-list").single(), "1 Counterspell")
        tick()
        root.button("Find cards").click()
        waitFor("the sections") { root.all("[data-fetch-section]").isNotEmpty() }
        type(root.all("#fetch-title").single(), title)
        tick()
        root.button("Save").click()
        tick()
    }

    @Test
    fun aListSavedOnThePhoneIsOnTheWebsite() = runTest {
        account = "[" + saved("p1", "From the phone") + "]"
        val root = mount("#/fetch")
        waitFor("the phone's list on the website") { "From the phone" in savedTitles(root) }
    }

    @Test
    fun aListSavedOnTheWebsiteIsOnTheNextDevice() = runTest {
        val root = mount("#/fetch")
        findAndSave(root, "Alela")
        waitFor("the list to be sent to the account") { sent.any { "Alela" in it } }

        val phone = anotherDevice("#/fetch")
        waitFor("the website's list on another device") { "Alela" in savedTitles(phone) }
    }

    @Test
    fun aListSavedHereBeforeSyncingIsKeptAndSentUp() = runTest {
        localStorage.setItem("mtg.fetch", "[" + saved("h1", "Only here") + "]")
        account = "[" + saved("p1", "From the phone") + "]"
        val root = mount("#/fetch")
        waitFor("both lists") { savedTitles(root).containsAll(listOf("From the phone", "Only here")) }
        waitFor("this device's list to be sent up") { sent.any { "Only here" in it && "From the phone" in it } }
    }

    @Test
    fun whenTheListsCannotLoadItSaysSoKeepsTheseAndTheFetchPageAsksAgain() = runTest {
        localStorage.setItem("mtg.fetch", "[" + saved("h1", "Only here") + "]")
        localStorage.setItem("mtg.fetch.synced", "e7de0cb1")
        loadFails = true
        account = "[" + saved("p1", "From the phone") + "]"
        val root = mount("#/fetch")
        waitFor("a failure toast") { badToast() != null }
        assertEquals("Your saved Fetch lists did not load: D1 is down", badToast()?.removeSuffix("×")?.trim())
        assertEquals(listOf("Only here"), savedTitles(root), "this device's lists went with the failed load")
        tick(FETCH_SEND_WAIT)
        assertTrue(sent.isEmpty(), "this device's copy was written over the account's before it had been read: $sent")

        // Try again, without restarting: leave the page and come back.
        loadFails = false
        window.location.hash = "#/library"
        tick()
        window.location.hash = "#/fetch"
        waitFor("the account's lists on a second visit") { "From the phone" in savedTitles(root) }
        assertEquals(listOf("From the phone"), savedTitles(root))
    }

    @Test
    fun whenASaveCannotReachTheAccountItSaysSoAndTheListIsStillSavedHere() = runTest {
        saveFails = true
        val root = mount("#/fetch")
        findAndSave(root, "Alela")
        waitFor("a failure toast") { badToast()?.contains("did not save to your account") == true }
        assertEquals("Your Fetch lists did not save to your account: D1 is down", badToast()?.removeSuffix("×")?.trim())
        assertTrue(!account.contains("Alela"), "the stub kept a save it refused")

        // The next change — a tick — sends them all again.
        saveFails = false
        root.all("[data-fetch-tick]").first().click()
        waitFor("the lists sent again after the failure") { account.contains("Alela") }

        root.all("[data-fetch-back]").single().click()
        waitFor("the list saved on this device") { "Alela" in savedTitles(root) }
    }

    private companion object {
        const val FETCH_SEND_WAIT = 600
    }
}
