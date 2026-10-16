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
import kotlinx.coroutines.CompletableDeferred
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

    /** Scryfall holds every answer until this completes, when set. */
    private var scryfallGate: CompletableDeferred<Unit>? = null

    /** The owned printings are held until this completes, when set. */
    private var ownedGate: CompletableDeferred<Unit>? = null

    /** The Worker answers the owned printings with a 500 while this is true. */
    private var ownedFails = false

    @BeforeTest
    fun stubTheNetwork() {
        localStorage.removeItem("mtg.fetch")
        localStorage.removeItem("mtg.fetch.open")
        asked.clear()
        scryfallGate = null
        ownedGate = null
        ownedFails = false
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val q = request.url.parameters["q"].orEmpty()
            asked += if (body.isNotEmpty()) body else request.url.toString()
            if (request.url.encodedPath.endsWith("/cards/search")) scryfallGate?.await()
            if ("json_each" in body) {
                ownedGate?.await()
                if (ownedFails) {
                    return@MockEngine respond(
                        """{"error":"D1 is down"}""",
                        HttpStatusCode.InternalServerError,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            val json = when {
                // The owned printings, the way the query asks for them.
                "json_each" in body ->
                    """{"cols":["name_norm","name","face1","types","colors","color_identity","rarity","qty"],"rows":[""" +
                        """["counterspell","Counterspell","Counterspell","Instant","U","U","common",2],""" +
                        """["arcane signet","Arcane Signet","Arcane Signet","Artifact",null,null,"common",1],""" +
                        """["brazen borrower // petty theft","Brazen Borrower // Petty Theft","Brazen Borrower","Creature","U","U","mythic",1],""" +
                        // Stored RU; the card prints {U}{R}.
                        """["electrolyze","Electrolyze","Electrolyze","Instant","RU","RU","uncommon",1]""" +
                        """],"n":4}"""
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
        localStorage.removeItem("mtg.fetch")
        localStorage.removeItem("mtg.fetch.open")
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
        all("button").filterIsInstance<HTMLButtonElement>().firstOrNull { (it.textContent?.trim() == label || it.getAttribute("aria-label") == label) }
            ?: error("no button labelled \"$label\"")

    private fun type(el: HTMLElement, text: String) {
        when (el) {
            is HTMLTextAreaElement -> el.value = text
            is HTMLInputElement -> el.value = text
        }
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
    }

    private suspend fun typedAndFound(root: HTMLElement) {
        waitFor("the Fetch page") { root.all("#fetch-list").isNotEmpty() }
        type(root.all("#fetch-list").single(), "1 Counterspell\n1 Arcane Signet\n1 Brazen Borrower\n1 Electrolyze\n1 Black Lotus")
        tick()
        root.button("Find cards").click()
    }

    private suspend fun found(root: HTMLElement) {
        typedAndFound(root)
        waitFor("the sections") { root.all("[data-fetch-section]").isNotEmpty() }
    }

    private fun box(root: HTMLElement, card: String): HTMLInputElement =
        root.all("[data-fetch-tick]").filterIsInstance<HTMLInputElement>()
            .firstOrNull { it.getAttribute("data-fetch-tick") == card }
            ?: error("no box to tick for $card")

    private fun remount(hash: String): HTMLElement {
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        return mount(hash)
    }

    @Test
    fun findGoesToANewPageThatShowsTheLoadingViewAndThenTheList() = runTest {
        val gate = CompletableDeferred<Unit>()
        ownedGate = gate
        try {
            val root = mount("#/fetch")
            typedAndFound(root)
            waitFor("the address to name the list page") { window.location.hash.endsWith("/fetch/list") }
            waitFor("the loading view") { root.all("[data-fetch-loading]").isNotEmpty() }
            val loading = root.all("[data-fetch-loading]").single()
            assertTrue(loading.getBoundingClientRect().height > 80, "the loading view is not drawn")
            assertTrue(root.all("#fetch-list").isEmpty(), "the entry box is still on the list page")
            assertEquals(5, loading.all("img.mana-sym").size, "the loading view does not have the five colours")
            assertEquals(
                "running",
                window.getComputedStyle(loading.all(".fetch-orbit").single()).getPropertyValue("animation-play-state"),
                "the loading view does not move",
            )
            gate.complete(Unit)
            waitFor("the sections after the loading view") { root.all("[data-fetch-section]").isNotEmpty() }
            assertTrue(root.all("[data-fetch-loading]").isEmpty(), "the loading view stayed over the list")
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun aTickedCardIsStruckThroughAndStillTickedAfterAReload() = runTest {
        val root = mount("#/fetch")
        found(root)
        box(root, "Counterspell").click()
        // The box flips itself on a click; the progress line is the
        // app having heard it.
        waitFor("the tick") { root.all("[data-fetch-progress]").singleOrNull()?.textContent.orEmpty().contains("1 of 4 found") }
        val name = root.all("[data-fetch-card]").single { it.getAttribute("data-fetch-card") == "Counterspell" }
            .all(".fetch-name").single()
        assertTrue(
            window.getComputedStyle(name).getPropertyValue("text-decoration-line").contains("line-through"),
            "a ticked card is not struck through",
        )
        assertTrue(root.all("[data-fetch-progress]").single().textContent.orEmpty().contains("1 of 4 found"))

        // A fresh page, as after closing the tab, straight onto the list.
        val again = remount("#/fetch/list")
        waitFor("the list after a reload") { again.all("[data-fetch-section]").isNotEmpty() }
        assertTrue(box(again, "Counterspell").checked, "the tick did not survive a reload")
        assertTrue(!box(again, "Arcane Signet").checked, "a card nobody ticked came back ticked")
    }

    @Test
    fun editingTheListKeepsTheTicks() = runTest {
        val root = mount("#/fetch")
        found(root)
        box(root, "Counterspell").click()
        // The box flips itself on a click; the progress line is the
        // app having heard it.
        waitFor("the tick") { root.all("[data-fetch-progress]").singleOrNull()?.textContent.orEmpty().contains("1 of 4 found") }
        root.button("Edit").click()
        waitFor("the list to edit") { root.all("#fetch-edit").isNotEmpty() }
        val edit = root.all("#fetch-edit").single() as HTMLTextAreaElement
        assertTrue(edit.value.contains("1 Counterspell"), "Edit did not start from the list")
        type(edit, edit.value + "\n1 Sol Ring")
        tick()
        val asks = asked.count { "json_each" in it }
        root.button("Find cards").click()
        waitFor("the list to be filed again") { asked.count { "json_each" in it } > asks && root.all("[data-fetch-section]").isNotEmpty() }
        assertTrue(window.location.hash.endsWith("/fetch/list"), "an edit left the list page")
        assertTrue(box(root, "Counterspell").checked, "editing removed a tick")
        assertTrue(asked.last { "json_each" in it }.contains("sol ring"), "the edited list was not the one asked about")
    }

    @Test
    fun aFailedFindSaysWhatHappenedAndCanBeTriedAgain() = runTest {
        ownedFails = true
        val root = mount("#/fetch")
        typedAndFound(root)
        waitFor("the error") { root.all("[data-fetch-err]").isNotEmpty() }
        assertTrue(root.all("[data-fetch-loading]").isEmpty(), "the loading view is still up after a failure")
        assertTrue(root.all("[data-fetch-err]").single().textContent.orEmpty().isNotBlank(), "the error says nothing")
        ownedFails = false
        root.button("Try again").click()
        waitFor("the sections after trying again") { root.all("[data-fetch-section]").isNotEmpty() }
        assertTrue(root.all("[data-fetch-err]").isEmpty(), "the error stayed after it worked")
    }

    @Test
    fun backFromTheListIsTheEntryBoxWithTheListStillReachable() = runTest {
        val root = mount("#/fetch")
        found(root)
        root.all("[data-fetch-back]").single().click()
        waitFor("the root") { root.all("#fetch-list").isNotEmpty() }
        assertTrue(window.location.hash.endsWith("/fetch"), "back did not go to the root: " + window.location.hash)
        assertEquals("", (root.all("#fetch-list").single() as HTMLTextAreaElement).value, "the entry box still holds the list")
        assertTrue(root.all("[data-fetch-section]").isEmpty(), "the list is drawn on the root")
        root.button(org.mattshoe.mtg.core.FetchListState.UNSAVED).click()
        waitFor("the list again") { root.all("[data-fetch-section]").isNotEmpty() }
    }

    @Test
    fun aListComesBackFiledByTypeThenColourThenRarity() = runTest {
        val root = mount("#/fetch")
        found(root)
        assertEquals(
            listOf(
                "Creatures · U · Mythic",
                "Instants · U · Common",
                "Instants · UR · Uncommon",
                "Artifacts · C · Common",
                "Missing",
            ),
            root.all("[data-fetch-section]").map { it.getAttribute("data-fetch-section") },
        )
        // In that order down the page, not only in the DOM.
        val tops = root.all("[data-fetch-section]").map { it.getBoundingClientRect().top }
        assertEquals(tops.sorted(), tops, "the sections are not drawn top to bottom in binder order")
    }

    @Test
    fun aTwoColourSectionIsHeadedByItsManaSymbolsInPrintedOrderAndNotByAWord() = runTest {
        val root = mount("#/fetch")
        found(root)
        assertTrue(!root.textContent.orEmpty().contains("Multicolor"), "a section still says Multicolor")
        val section = root.all("[data-fetch-section]").single { it.getAttribute("data-fetch-section") == "Instants · UR · Uncommon" }
        val head = section.querySelector("h2") as HTMLElement
        val symbols = head.all("img.mana-sym")
        assertEquals(listOf("{U}", "{R}"), symbols.map { it.getAttribute("alt") }, "the head's symbols are not {U}{R}")
        val lefts = symbols.map { it.getBoundingClientRect().left }
        assertTrue(lefts[0] < lefts[1], "{U} is not drawn before {R}: $lefts")
        assertEquals("InstantsUncommon", head.textContent?.replace(" ", ""), "the head says more than type, symbols and rarity")
    }

    @Test
    fun aColourlessSectionIsHeadedByTheColourlessSymbol() = runTest {
        val root = mount("#/fetch")
        found(root)
        val section = root.all("[data-fetch-section]").single { it.getAttribute("data-fetch-section") == "Artifacts · C · Common" }
        assertEquals(listOf("{C}"), (section.querySelector("h2") as HTMLElement).all("img.mana-sym").map { it.getAttribute("alt") })
    }

    @Test
    fun theFilingIsOnScreenWhileScryfallIsStillThinking() = runTest {
        // Matt: "it takes several full minutes to run". Those minutes
        // were Scryfall's, and the whole list waited for them.
        val gate = CompletableDeferred<Unit>()
        scryfallGate = gate
        try {
            val root = mount("#/fetch")
            found(root)
            assertTrue(root.all("[data-fetch-checking]").isNotEmpty(), "nothing says the Also check is still coming")
            assertTrue(root.all("[data-fetch-also]").isEmpty(), "an Also check arrived without Scryfall")
            gate.complete(Unit)
            waitFor("the Also check to follow") { root.all("[data-fetch-also]").isNotEmpty() }
            waitFor("the checking line to go") { root.all("[data-fetch-checking]").isEmpty() }
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun aCardPrintedAtAnotherRaritySaysWhereElseToLook() = runTest {
        val root = mount("#/fetch")
        found(root)
        waitFor("the Also check") { root.all("[data-fetch-also]").isNotEmpty() }
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
        tick()
        // The saved lists are on the root, with the entry box.
        root.all("[data-fetch-back]").single().click()
        waitFor("the saved list") { root.all("[data-fetch-saved]").isNotEmpty() }

        // A fresh page, as after closing the tab.
        val again = remount("#/fetch")
        waitFor("the saved list after a reload") { again.all("[data-fetch-saved]").isNotEmpty() }
        again.button("Alela").click()
        waitFor("the saved sections") { again.all("[data-fetch-section]").isNotEmpty() }
        assertTrue(
            again.all("[data-fetch-section]").any { it.getAttribute("data-fetch-section") == "Instants · U · Common" },
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
        waitFor("the address to say stats") { window.location.hash.endsWith("stats") }
        // The totals load after the address changes, so wait for them
        // rather than reading a page still saying Loading.
        waitFor("the stats page to draw its totals") { root.textContent.orEmpty().contains("Printings") }
    }
}
