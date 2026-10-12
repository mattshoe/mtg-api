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
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
 * The Fetch list, through `MtgApp` itself: paste a list, press Find,
 * read the binder sections off the page, save it, and find it again
 * after the page is mounted fresh.
 *
 * Matt: "my cards are all sorted in order of: 1. Type 2. Color 3.
 * Rarity", an "Also check" where the rarity might be wrong, a section
 * for what he does not own, and "it's very important that a pull list
 * can be saved and referenced later".
 */
class FetchListJourneyTest {

    private val roots = mutableListOf<HTMLElement>()
    private var realFetch: dynamic = null
    private val asked = mutableListOf<String>()

    @BeforeTest
    fun stubTheNetwork() {
        localStorage.removeItem("mtg.fetch")
        asked.clear()
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val q = request.url.parameters["q"].orEmpty()
            asked += if (body.isNotEmpty()) body else request.url.toString()
            val json = when {
                // The owned printings, the way the query asks for them.
                "json_each" in body ->
                    """{"cols":["name_norm","name","face1","types","colors","rarity","qty"],"rows":[""" +
                        """["counterspell","Counterspell","Counterspell","Instant","U","common",2],""" +
                        """["arcane signet","Arcane Signet","Arcane Signet","Artifact",null,"common",1],""" +
                        """["brazen borrower // petty theft","Brazen Borrower // Petty Theft","Brazen Borrower","Creature","U","mythic",1]""" +
                        """],"n":3}"""
                // Scryfall: Arcane Signet has an uncommon printing too.
                request.url.encodedPath.endsWith("/cards/search") && "r:uncommon" in q ->
                    """{"data":[{"name":"Arcane Signet"}]}"""
                request.url.encodedPath.endsWith("/cards/search") && "r:common" in q ->
                    """{"data":[{"name":"Arcane Signet"},{"name":"Counterspell"}]}"""
                request.url.encodedPath.endsWith("/cards/search") && "r:mythic" in q ->
                    """{"data":[{"name":"Brazen Borrower // Petty Theft"}]}"""
                request.url.encodedPath.endsWith("/cards/search") ->
                    """{"object":"error","code":"not_found","status":404}"""
                else -> """{"cols":["id"],"rows":[],"n":0}"""
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
        localStorage.removeItem("mtg.fetch")
        window.location.hash = ""
    }

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

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        all("button").filterIsInstance<HTMLButtonElement>().firstOrNull { it.textContent?.trim() == label }
            ?: error("no button labelled \"$label\"")

    private fun type(el: HTMLElement, text: String) {
        when (el) {
            is HTMLTextAreaElement -> el.value = text
            is HTMLInputElement -> el.value = text
        }
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
    }

    private suspend fun found(root: HTMLElement) {
        waitFor("the Fetch page") { root.all("#fetch-list").isNotEmpty() }
        type(root.all("#fetch-list").single(), "1 Counterspell\n1 Arcane Signet\n1 Brazen Borrower\n1 Black Lotus")
        tick()
        root.button("Find cards").click()
        waitFor("the sections") { root.all("[data-fetch-section]").isNotEmpty() }
    }

    @Test
    fun aListComesBackFiledByTypeThenColourThenRarity() = runTest {
        val root = mount("#/fetch")
        found(root)
        assertEquals(
            listOf(
                "Creatures · Blue · Mythic",
                "Instants · Blue · Common",
                "Artifacts · Colorless · Common",
                "Missing",
            ),
            root.all("[data-fetch-section]").map { it.getAttribute("data-fetch-section") },
        )
        // In that order down the page, not only in the DOM.
        val tops = root.all("[data-fetch-section]").map { it.getBoundingClientRect().top }
        assertEquals(tops.sorted(), tops, "the sections are not drawn top to bottom in binder order")
    }

    @Test
    fun aCardPrintedAtAnotherRaritySaysWhereElseToLook() = runTest {
        val root = mount("#/fetch")
        found(root)
        val signet = root.all("[data-fetch-card]").single { it.getAttribute("data-fetch-card") == "Arcane Signet" }
        assertEquals("Also check: Uncommon", signet.querySelector("[data-fetch-also]")?.textContent?.trim())
        assertTrue(
            asked.any { "cards/search" in it && "Arcane" in it },
            "Scryfall was never asked about the card: $asked",
        )
    }

    @Test
    fun aCardHeDoesNotOwnIsInTheMissingSection() = runTest {
        val root = mount("#/fetch")
        found(root)
        assertEquals(
            listOf("Black Lotus"),
            root.all("[data-fetch-missing]").map { it.getAttribute("data-fetch-missing") },
        )
    }

    @Test
    fun savingNeedsATitleAndTheListIsThereAfterAReload() = runTest {
        val root = mount("#/fetch")
        found(root)
        root.button("Save").click()
        waitFor("the title being asked for") { root.textContent.orEmpty().contains("Give it a title first") }
        assertTrue(root.all("[data-fetch-saved]").isEmpty(), "it saved without a title")

        type(root.all("#fetch-title").single(), "Alela")
        tick()
        root.button("Save").click()
        waitFor("the saved list") { root.all("[data-fetch-saved]").isNotEmpty() }

        // A fresh page, as after closing the tab.
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        val again = mount("#/fetch")
        waitFor("the saved list after a reload") { again.all("[data-fetch-saved]").isNotEmpty() }
        again.button("Alela").click()
        waitFor("the saved sections") { again.all("[data-fetch-section]").isNotEmpty() }
        assertTrue(
            again.all("[data-fetch-section]").any { it.getAttribute("data-fetch-section") == "Instants · Blue · Common" },
            "the saved list did not come back as it was",
        )
    }

    @Test
    fun statsIsBehindTheProfileAndStillOpens() = runTest {
        val root = mount("#/search")
        tick()
        val profile = document.querySelector(".profile-menu") as HTMLElement
        val stats = profile.all("button").filterIsInstance<HTMLButtonElement>()
            .firstOrNull { it.textContent?.trim() == "Stats" }
            ?: error("Stats is not behind the profile")
        stats.click()
        waitFor("the stats page") { window.location.hash.endsWith("stats") }
        assertTrue(root.textContent.orEmpty().contains("Printings"), "the stats page did not draw")
    }
}
