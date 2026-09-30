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
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real app, mounted, clicked.
 *
 * Every other suite here mounts a composable and hands it a state.
 * That is blind to the whole layer the last four bugs lived in: the
 * address bar, the history stack, and what `MtgApp` does to its own
 * state between a click and the next render. Close on the card drawer
 * was fixed twice against tests that passed both times, because the
 * component was never the thing that was broken.
 *
 * So this mounts `MtgApp` — the actual object the page mounts — over a
 * stubbed network, and presses the buttons.
 */
class AppDriverTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun stubTheNetwork() {
        val engine = MockEngine { request ->
            // Enough of an answer for every read the shell makes. The
            // shapes matter, the contents do not — except that a
            // deck query has to come back looking like a deck, or
            // there is nothing to click.
            // `.toString()` on an OutgoingContent is its class name,
            // not the payload — which quietly gave every query the
            // same answer and made a deck list of one nameless deck.
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val json = when {
                body.contains("FROM decks d") && body.contains("art_id") ->
                    """{"cols":["slug","name","owner","commander","colors","bracket","art_id"],""" +
                        """"rows":[["alela","Fairy Deck","matt","Alela","UB",3,"abcdef12-3456"]],"n":1}"""

                body.contains("FROM deck_cards dc") ->
                    """{"cols":["name","name_norm","qty","role","owned","type_line","scryfall_id"],""" +
                        """"rows":[["Sol Ring","sol ring",1,null,1,"Artifact",null]],"n":1}"""

                else ->
                    """{"cols":["id","owner","name","name_norm","qty","printings"],""" +
                        """"rows":[[1,"matt","Sol Ring","sol ring",1,1]],"n":1}"""
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

    /** One frame, and a moment of real time with it. */
    private suspend fun tick(ms: Int = 40) {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, ms) }.await()
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    /**
     * Wait for something to be true, rather than for a length of time.
     *
     * The search is debounced by 250ms and answered by a promise, so a
     * fixed sleep is either a flake or a slow suite.
     */
    private suspend fun waitFor(what: String, upTo: Int = 5000, cond: () -> Boolean) {
        var waited = 0
        while (waited < upTo) {
            if (cond()) return
            tick()
            waited += 40
        }
        error("gave up waiting for $what")
    }

    /** Long enough for anything already in flight to have landed. */
    private suspend fun settle() = repeat(12) { tick() }

    /** The page, mounted the way `index.html` mounts it. */
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
        querySelectorAll("button").let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }
            .firstOrNull { it.textContent?.trim() == label }
            ?: error("no button labelled \"$label\"")

    private fun drawers() = document.querySelectorAll("div.drawer").length

    private fun hash() = window.location.hash

    // ------------------------------------------------- opening a card

    @Test
    fun closingACardDoesNotMoveThroughHistory() = runTest {
        // The mechanism, not the symptom. Close rewrites the address
        // where it stands; it must not pop an entry, because the entry
        // underneath still names the card and the hashchange that
        // follows a pop reopens it. That is what "the Close button
        // does nothing" was, twice.
        mount("#/search?card=matt:sol+ring")
        waitFor("the drawer") { drawers() == 1 }
        js(
            "window.__backs = 0;" +
                "var h = window.history; var orig = h.back.bind(h);" +
                "h.back = function () { window.__backs++; return orig(); };",
        )
        (document.querySelectorAll("div.drawer button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }.first { it.textContent?.trim() == "Close" }).click()
        settle()
        assertEquals(0, drawers(), "Close left the drawer open")
        assertEquals(0, js("window.__backs") as Int, "closing the card walked back through history")
    }

    @Test
    fun aCardOpensFromTheGridAndTheAddressSaysSo() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        view.all("div.card").first().click()
        settle()
        assertEquals(1, drawers(), "the drawer did not open")
        assertTrue(hash().contains("card=matt:sol+ring"), hash())
    }

    // ------------------------------------------------- and closing it

    @Test
    fun closeShutsTheDrawerAndLeavesItShut() = runTest {
        // The one that was "fixed" twice. Settling well past the
        // click is the point: the failure was never the click, it was
        // what the history did a frame or two later.
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        view.all("div.card").first().click()
        waitFor("the drawer") { drawers() == 1 }

        (document.querySelectorAll("div.drawer button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }.first { it.textContent?.trim() == "Close" }).click()
        settle()
        assertEquals(0, drawers(), "Close left the drawer open")

        // And it stays shut. A history pop landing on an address that
        // still named the card is what put it back last time.
        settle()
        settle()
        assertEquals(0, drawers(), "the drawer came back on its own")
        assertTrue(!hash().contains("card="), "the address still names a card: ${hash()}")
    }

    @Test
    fun theScrimShutsItToo() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        view.all("div.card").first().click()
        waitFor("the drawer") { drawers() == 1 }
        (document.querySelector("div.drawer-scrim") as HTMLElement).click()
        settle()
        settle()
        assertEquals(0, drawers(), "a press outside left the drawer open")
    }

    @Test
    fun aCardOpenedFromALinkAlsoCloses() = runTest {
        // No history behind it at all, which is the case a back-based
        // close cannot handle.
        mount("#/search?card=matt:sol+ring")
        settle()
        waitFor("the drawer") { drawers() == 1 }
        (document.querySelectorAll("div.drawer button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }.first { it.textContent?.trim() == "Close" }).click()
        settle()
        settle()
        assertEquals(0, drawers(), "Close did nothing on a card opened from a link")
    }

    @Test
    fun openingAndClosingTwiceStillWorks() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        repeat(3) { round ->
            view.all("div.card").first().click()
            waitFor("round $round: the drawer") { drawers() == 1 }
            (document.querySelectorAll("div.drawer button").let { n ->
                (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
            }.first { it.textContent?.trim() == "Close" }).click()
            settle()
            settle()
            assertEquals(0, drawers(), "round $round: the drawer did not close")
        }
    }

    // ----------------------------------------------- opening a deck

    @Test
    fun openingADeckIsAStepBackComesBackFrom() = runTest {
        // It was not. The push lived in the one callback the
        // composition owns and `openDeck` writes the state directly,
        // so the deck replaced the list in the address bar and back
        // skipped straight past it to whatever came before Decks.
        val view = mount("#/decks")
        waitFor("the deck list") { view.all("div.deck-card").isNotEmpty() }
        assertEquals("#/decks", hash())

        view.all("div.deck-card").first().click()
        waitFor("the deck") { hash() == "#/decks/alela" }

        window.history.back()
        waitFor("the list again") { hash() == "#/decks" }
        waitFor("the tiles") { view.all("div.deck-card").isNotEmpty() }
    }

    @Test
    fun andForwardGoesBackIntoIt() = runTest {
        val view = mount("#/decks")
        waitFor("the deck list") { view.all("div.deck-card").isNotEmpty() }
        view.all("div.deck-card").first().click()
        waitFor("the deck") { hash() == "#/decks/alela" }
        window.history.back()
        waitFor("the list") { hash() == "#/decks" }
        window.history.forward()
        waitFor("the deck again") { hash() == "#/decks/alela" }
    }

    @Test
    fun aDeckOpenedFromALinkShowsItsCards() = runTest {
        val view = mount("#/decks/alela")
        settle()
        waitFor("the deck") { view.textContent.orEmpty().contains("Sol Ring") }
        assertEquals("#/decks/alela", hash())
    }

    @Test
    fun changingAFilterIsNotAStepBackHasToUndo() = runTest {
        // Every keystroke would otherwise be a history entry and the
        // back button would take a hundred presses to leave a search.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        val before = js("window.history.length") as Int
        view.all("input[placeholder='Card name']").first().let { box ->
            (box as org.w3c.dom.HTMLInputElement).value = "bolt"
            box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
        }
        settle()
        assertTrue(hash().contains("q=bolt"), hash())
        assertEquals(before, js("window.history.length") as Int, "typing pushed history entries")
    }

    @Test
    fun backClosesTheCardRatherThanLeavingThePage() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        val before = hash()
        view.all("div.card").first().click()
        waitFor("the drawer") { drawers() == 1 }

        window.history.back()
        settle()
        assertEquals(0, drawers(), "back did not close the card")
        assertEquals(before, hash(), "back left the page as well")
    }
}
