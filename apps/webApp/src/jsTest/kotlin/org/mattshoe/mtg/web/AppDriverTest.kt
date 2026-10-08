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
import org.mattshoe.mtg.core.GitHubReleases
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Scryfall
import org.w3c.dom.HTMLButtonElement
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

    /** The browser's own `fetch`, put back after each test. */
    private var realFetch: dynamic = null

    /** Every write the app made, by path, so a second one is visible. */
    private val writes = mutableListOf<String>()

    /** How many times the library asked the database for a page. */
    private var searches = 0

    /** Every request body the app sent, in order, so a test can read what it asked. */
    private val sent = mutableListOf<String>()

    /** What `/auth/me` says the session is. Admin Settings needs `admin`. */
    private var role = "user"

    /** How many times the app asked GitHub for the request pull requests. */
    private var tasksAsked = 0

    @BeforeTest
    fun stubTheNetwork() {
        writes.clear()
        searches = 0
        sent.clear()
        role = "user"
        tasksAsked = 0
        val engine = MockEngine { request ->
            if (request.method.value == "POST" && """"dry_run":false""" in
                (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            ) {
                writes += request.url.encodedPath
            }
            // Enough of an answer for every read the shell makes. The
            // shapes matter, the contents do not — except that a
            // deck query has to come back looking like a deck, or
            // there is nothing to click.
            // `.toString()` on an OutgoingContent is its class name,
            // not the payload — which quietly gave every query the
            // same answer and made a deck list of one nameless deck.
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            sent += body
            if ("MIN(c.id) AS id" in body) searches++
            val json = when {
                // The library page, answered according to what was
                // actually asked. A stub that returns the same rows
                // whatever the filter cannot tell a search that ran
                // from one that did not — which is how a search that
                // stopped running passed a test suite.
                "MIN(c.id) AS id" in body -> {
                    val cols = """"cols":["id","owner","name","name_norm","qty","printings"]"""
                    val bolt = """[1,"e7de0cb1","Lightning Bolt","lightning bolt",1,1]"""
                    val ring = """[2,"e7de0cb1","Sol Ring","sol ring",1,1]"""
                    if ("%bolt%" in body) """{$cols,"rows":[$bolt],"n":1}"""
                    else """{$cols,"rows":[$ring,$bolt],"n":2}"""
                }

                body.contains("FROM decks d") && body.contains("art_id") ->
                    """{"cols":["key","name","owner","owner_name","commander","colors","bracket","art_id"],""" +
                        """"rows":[["alela","Fairy Deck","e7de0cb1","Matt","Alela","UB",3,"abcdef12-3456"]],"n":1}"""

                body.contains("FROM deck_cards dc") ->
                    """{"cols":["name","name_norm","qty","role","owned","type_line","scryfall_id"],""" +
                        """"rows":[["Sol Ring","sol ring",1,null,1,"Artifact",null]],"n":1}"""

                request.url.encodedPath == "/cards/add" ->
                    """{"applied":true,"dry_run":false,"resolved":1,"failed":0,""" +
                        """"changes":[["Sol Ring","M3C","409","nonfoil",0,4]],"errors":[]}"""

                request.url.encodedPath.endsWith("/cards/autocomplete") ->
                    """{"object":"catalog","total_values":2,"data":["Vesuva","Vesuvan Mist"]}"""

                request.url.encodedPath.endsWith("/repos/mattshoe/mtg-api/releases") ->
                    """[{"tag_name":"android-v2.1.0-297","published_at":"2026-10-09T10:00:00Z",""" +
                        """"body":"Release notes in Admin Settings.\n\nBuilt from abc."}]"""

                request.url.encodedPath.endsWith("/repos/mattshoe/mtg-api/pulls") -> {
                    tasksAsked++
                    """[{"title":"Task status in the app","state":"open",""" +
                        """"head":{"ref":"request/task-status-in-the-app-22facff"},"merged_at":null,"closed_at":null}]"""
                }

                request.url.encodedPath.contains("/git/matching-refs/heads/request") ->
                    """[{"ref":"refs/heads/request/task-status-in-the-app-22facff"}]"""

                request.url.encodedPath.endsWith("/contents/requests/done") -> "[]"

                request.url.encodedPath == "/admin/users" -> """{"users":[]}"""

                request.url.encodedPath == "/cards/validate" ->
                    """{"checked":1,"unknown":0,"ok":true,"cards":[],"bad":[],"suggestions":{}}"""

                request.url.encodedPath == "/decks/create" ->
                    """{"created":true,"applied":true,"key":"q8ytka9m","card_count":100,"rows":100,""" +
                        """"deck":{"key":"q8ytka9m","name":"New Deck"},"added":[],"removed":[],""" +
                        """"changed":[],"acquired":[],"returned":[],"errors":[]}"""

                else ->
                    """{"cols":["id","owner","name","name_norm","qty","printings"],""" +
                        """"rows":[[1,"e7de0cb1","Sol Ring","sol ring",1,1]],"n":1}"""
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(
            MtgApi.withEngine("https://example.test", http),
            Scryfall.withEngine(http),
            GitHubReleases.withEngine(http),
        )
        stubWhoAmI()
    }

    /**
     * Who the session is, which the shell asks for with `window.fetch`
     * rather than through the client.
     *
     * It is a cookie request — `credentials: "include"`, because the
     * API is on another domain — and Ktor's JS engine has nowhere to
     * say that, so it is the one call the `MockEngine` above cannot
     * see. Signing in is what makes Entry reachable, so without this
     * every journey that writes a card stops at a gated screen.
     */
    private fun stubWhoAmI() {
        if (realFetch == null) realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { url: dynamic, init: dynamic ->
            if ("$url".contains("/auth/me")) {
                Promise.resolve(
                    Response(
                        """{"key":"e7de0cb1","name":"Matt","role":"$role"}""",
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
        // The two tests that need the real cascade pull it in
        // themselves. Left loaded it changes the height of the page
        // the scroll tests measure, which turns one of them red for a
        // reason that has nothing to do with scrolling.
        Stylesheet.unload()
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

    /** Real time, for the moment after something that is debounced. */
    private suspend fun rest(ms: Int) {
        var waited = 0
        while (waited < ms) { tick(); waited += 40 }
    }

    /** Long enough for anything already in flight to have landed. */
    private suspend fun settle() = repeat(12) { tick() }

    /** The page, mounted the way `index.html` mounts it. */
    private fun mount(hash: String, token: String = ""): HTMLElement {
        window.location.hash = hash
        val view = document.createElement("div") as HTMLElement
        val nav = document.createElement("div") as HTMLElement
        document.body!!.appendChild(view)
        document.body!!.appendChild(nav)
        roots += view
        roots += nav
        MtgApp.mount(view, null, token)
        MtgApp.mountNav(nav)
        return view
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /**
     * What a button reads as. An option row carries its help text in
     * the same element, so the label is the part that names it.
     */
    private fun HTMLButtonElement.reads(): String =
        ((querySelector(".opt-label")?.textContent ?: textContent).orEmpty().trim())

    /** Is there a button reading that right now? */
    private fun HTMLElement.has(label: String): Boolean =
        querySelectorAll("button").let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }
            .any { it.reads() == label }

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        querySelectorAll("button").let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }
            .firstOrNull { it.reads() == label }
            ?: error("no button labelled \"$label\"")

    private fun cardPages() = document.querySelectorAll("div.card-page").length

    /**
     * Where the page is, less whose collection it is.
     *
     * Signing in now moves you to your own collection with its key in
     * the address — `#/c/e7de0cb1/search` — which `homeRoute` always
     * meant to do and could not while the web read no key off
     * `/auth/me`. These tests are about where back and forward go, not
     * about that prefix; [addressSaysWhose] is the one that is.
     */
    private fun hash() = window.location.hash.replace(Regex("^#/c/[^/]+/"), "#/")

    @Test
    fun addressSaysWhose() = runTest {
        mount("#/search", token = "t")
        waitFor("your key in the address") { window.location.hash == "#/c/e7de0cb1/search" }
    }

    // ------------------------------------------------- opening a card

    /** The ← Back button on the card page, wherever it is. */
    private fun backButton(): HTMLButtonElement =
        document.querySelectorAll("button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }.firstOrNull { it.textContent?.trim() == "← Back" } ?: error("no back button on the card")

    @Test
    fun aCardOpensFromTheGridAndTheAddressIsTheCardAlone() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }
        assertEquals("#/card/sol+ring", hash())
    }

    @Test
    fun aCardOpenedFromALinkLoadsItself() = runTest {
        // Nothing clicked it, so the route is the only thing that
        // knows a card is wanted.
        mount("#/card/sol+ring")
        waitFor("the card page") { cardPages() == 1 }
        waitFor("the card") { document.body!!.textContent.orEmpty().contains("Sol Ring") }
    }

    @Test
    fun backLeavesTheCardWithoutLeavingTheSite() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }

        window.history.back()
        waitFor("the search again") { cardPages() == 0 }
        assertEquals("#/search", hash())
    }

    @Test
    fun theBackButtonGoesWhereTheBrowsersDoes() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }

        backButton().click()
        waitFor("the search again") { cardPages() == 0 }
        assertEquals("#/search", hash())
    }

    @Test
    fun aCardOpenedFromALinkStillHasSomewhereToGoBackTo() = runTest {
        // There is no page behind it, so ← Back cannot mean "the page
        // before". It means the library rather than nothing at all.
        mount("#/card/sol+ring")
        waitFor("the card page") { cardPages() == 1 }
        backButton().click()
        waitFor("the library") { cardPages() == 0 }
        assertEquals("#/search", hash())
    }

    @Test
    fun openingAndLeavingThreeTimesStillWorks() = runTest {
        val view = mount("#/search")
        settle()
        repeat(3) { round ->
            waitFor("round $round: the grid") { view.all("div.card").isNotEmpty() }
            openFromTheGrid(view)
            waitFor("round $round: the card page") { cardPages() == 1 }
            backButton().click()
            waitFor("round $round: back on the search") { cardPages() == 0 }
            settle()
            assertEquals(0, cardPages(), "round $round: the card came back on its own")
        }
    }

    @Test
    fun aCardFromADeckGoesBackToThatDeck() = runTest {
        val view = mount("#/decks/alela")
        settle()
        waitFor("the deck") { view.textContent.orEmpty().contains("Sol Ring") }
        // Through the carousel, which is what a row opens now. The
        // card's own page is a button on its sheet.
        view.all("div.deck-line, a.deck-line").first { it.textContent.orEmpty().contains("Sol Ring") }.click()
        waitFor("the carousel") { document.querySelector(".peek-scrim") != null }
        pressOnThePage("Full details")
        settle()
        waitFor("the card page") { cardPages() == 1 }
        assertEquals("#/card/sol+ring", hash(), "the link to the card carried the deck")

        backButton().click()
        waitFor("the deck again") { cardPages() == 0 }
        assertEquals("#/decks/alela", hash())
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
    fun aDeckOpensAtTheTopOfItself() = runTest {
        // It opened wherever the list happened to be scrolled to,
        // because a hash change is not a navigation and the browser
        // has nothing to restore.
        val view = mount("#/decks")
        waitFor("the deck list") { view.all("div.deck-card").isNotEmpty() }
        val filler = document.createElement("div") as HTMLElement
        filler.style.height = "4000px"
        document.body!!.appendChild(filler)
        roots += filler
        window.scrollTo(0.0, 1400.0)
        assertTrue(window.scrollY > 1000, "the page would not scroll, nothing to test")

        view.all("div.deck-card").first().click()
        waitFor("the deck") { hash() == "#/decks/alela" }
        settle()
        assertTrue(window.scrollY < 10, "the deck opened at ${window.scrollY}")
    }

    @Test
    fun andBackReturnsToWhereTheListWas() = runTest {
        val view = mount("#/decks")
        waitFor("the deck list") { view.all("div.deck-card").isNotEmpty() }
        val filler = document.createElement("div") as HTMLElement
        filler.style.height = "4000px"
        document.body!!.appendChild(filler)
        roots += filler
        window.scrollTo(0.0, 1200.0)

        view.all("div.deck-card").first().click()
        waitFor("the deck") { hash() == "#/decks/alela" }
        settle()
        window.history.back()
        waitFor("the list again") { hash() == "#/decks" }
        settle()
        assertTrue(window.scrollY > 1100, "came back to the top instead of to ${1200}")
    }

    @Test
    fun thePagesOwnBackButtonLandsWhereTheBrowsersDoes() = runTest {
        // Two ways back that leave you in two different places is the
        // same bug as no scroll handling at all.
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        val filler = document.createElement("div") as HTMLElement
        filler.style.height = "4000px"
        document.body!!.appendChild(filler)
        roots += filler
        window.scrollTo(0.0, 1100.0)

        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }
        settle()
        assertTrue(window.scrollY < 10, "the card opened at ${window.scrollY}")

        backButton().click()
        waitFor("the search again") { cardPages() == 0 }
        waitFor("the list to be back where it was", upTo = 4000) { window.scrollY > 1000 }
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

    // -------------------------------------------- making a deck

    private fun palette() = document.querySelector("div.palette") as? HTMLElement

    private fun paletteButtons() =
        palette()?.querySelectorAll("button")?.let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }.orEmpty()

    /** What a button reads as, the option label winning over the mark. */
    private fun HTMLButtonElement.says(): String =
        ((querySelector(".opt-label")?.textContent ?: textContent).orEmpty().trim())

    private fun option(label: String) =
        paletteButtons().firstOrNull { it.says() == label }

    private fun box(placeholder: String) =
        palette()?.querySelector("[placeholder='$placeholder']") as? HTMLElement

    private fun typeIntoArea(text: String) {
        val box = palette()?.querySelector("textarea") as org.w3c.dom.HTMLTextAreaElement
        box.value = text
        box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
    }

    private fun press(label: String) {
        val b = option(label) ?: error("no button '$label'; saw ${paletteButtons().map { it.textContent?.trim() }}")
        b.click()
    }

    /**
     * The same, anywhere on the page rather than inside the modal.
     *
     * `press` looks in `div.palette`, which is right for the wizards
     * and wrong for everything that is a page: the entry wizard's
     * first question and the carousel's sheet are both on the page
     * itself, so pressing their buttons through `press` found
     * nothing and said "saw []".
     */
    /**
     * Open the first card in the grid, through the carousel.
     *
     * A tile opens the carousel now rather than the card's page —
     * the Library uses the same one a deck's rows do — and the page
     * is "Full details" on its sheet.
     */
    private suspend fun openFromTheGrid(view: HTMLElement) {
        view.all("div.card").first().click()
        waitFor("the carousel") { document.querySelector(".peek-scrim") != null }
        pressOnThePage("Full details")
    }

    private fun pressOnThePage(label: String) {
        val all = document.querySelectorAll("button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }
        val b = all.firstOrNull { it.says() == label }
            ?: error("no button '$label' on the page; saw ${all.map { it.says() }}")
        b.click()
    }

    private fun typeInto(placeholder: String, text: String) {
        val box = palette()?.querySelector("[placeholder='$placeholder']") as? HTMLElement
            ?: error("no box '$placeholder'")
        when (box) {
            is org.w3c.dom.HTMLInputElement -> box.value = text
            is org.w3c.dom.HTMLTextAreaElement -> box.value = text
            else -> error("not a box")
        }
        box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
    }

    @Test
    fun aCreatedDeckStopsSayingItIsBeingCreated() = runTest {
        // The panel sat on "Creating…" after the deck already
        // existed. The reload that follows a create builds on
        // whatever `app` holds when it comes back, and the finished
        // state was handed to it as an argument it ignored — so the
        // toast appeared, the deck appeared, and the box never moved.
        // The decks page has no New deck button any more; a deck is
        // started from the entry wizard's first question.
        val view = mount("#/entry", token = "t")
        waitFor("the entry wizard") { view.textContent.orEmpty().contains("What are you doing?") }
        view.all("button.opt").first { it.textContent.orEmpty().contains("New deck") }.click()
        settle()
        pressOnThePage("Continue →")
        waitFor("the wizard") { palette() != null }

        // A step at a time. Each wait keys off something only the
        // next step has — the stepper down the side names every step
        // on every step, so waiting for a step's own name to appear
        // is waiting for something that never went away.
        suspend fun onward(nextStep: String, there: () -> Boolean) {
            waitFor("Continue to come alive") {
                paletteButtons().any { it.says() == "Continue →" && !it.disabled }
            }
            press("Continue →")
            waitFor("the $nextStep step", cond = there)
        }

        press("Commander")
        // Straight to the name. The step between them asked whose
        // deck it was, which the collection on screen already says.
        onward("name") { box("Deck name") != null }
        typeInto("Deck name", "Test Deck")
        onward("commander") { box("e.g. Alela, Artful Provocateur") != null }
        typeInto("e.g. Alela, Artful Provocateur", "Alela, Cunning Conqueror")
        onward("cards") { palette()?.querySelector("textarea") != null }
        typeIntoArea("1 Sol Ring")
        onward("check") { paletteButtons().any { it.says() == "Check the names" } }

        press("Check the names")
        waitFor("the names to come back") {
            paletteButtons().any { it.says() == "Continue →" && !it.disabled }
        }
        // Straight to Create. The review used to ask where every copy
        // should come from before it would offer this, and then not
        // send the answers anywhere.
        onward("review") {
            paletteButtons().any { it.says() == "Create Test Deck" && !it.disabled }
        }
        press("Create Test Deck")

        waitFor("the deck to be made") { document.body!!.textContent.orEmpty().contains("Created Test Deck") }
        settle()
        val text = palette()?.textContent.orEmpty()
        assertTrue("Creating" !in text, "the panel is still saying it is creating: $text")
        assertTrue("Created" in text, "the panel never said it was done: $text")
        assertTrue(
            "#/decks/q8ytka9m" in text,
            "the new deck's address is not the key the server gave it: $text",
        )
    }

    // ------------------------------------------- whose, by key and id

    @Test
    fun aDeckIsOpenedByItsKey() = runTest {
        // Matt: "WE'RE GOING TO HAVE FUCKING COLLISIONS IN URLS ALL
        // OVER THE FUCKING PLACE". The address is the deck's key, and
        // the key is what the app asks the database for.
        val view = mount("#/decks/alela")
        settle()
        waitFor("the deck") { view.textContent.orEmpty().contains("Sol Ring") }
        val asked = sent.firstOrNull { "FROM deck_cards dc" in it } ?: error("the deck's cards were never asked for")
        assertTrue("d.key = ?" in asked, "the deck was looked up by something other than its key: $asked")
        assertTrue("\"alela\"" in asked, "the key in the address was not what was bound: $asked")
    }

    @Test
    fun yourCollectionIsAskedForByYourKeyAndNeverAnId() = runTest {
        val view = mount("#/search", token = "t")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        waitFor("a scoped search") { sent.any { "MIN(c.id) AS id" in it && "e7de0cb1" in it } }
        val page = sent.last { "MIN(c.id) AS id" in it && "e7de0cb1" in it }
        assertTrue(
            "c.owner_id = (SELECT id FROM users WHERE key = ?)" in page,
            "the Library did not resolve the key to an owner in the database: $page",
        )
        assertTrue("c.owner = ?" !in page, "the retired owner column is still read: $page")
    }

    @Test
    fun aDoubleTappedApplyOnlyWritesOnce() = runTest {
        // The flag that says "this is already happening" was set in
        // the first line of the suspend function, which on this
        // dispatcher runs a frame after the press. Two presses inside
        // that frame both got through, and the API gives every call
        // its own idempotency key — so an add of 248 printings was an
        // add of 496 as far as the server was concerned.
        val view = mount("#/entry", token = "t")
        waitFor("the wizard") { view.all("button").isNotEmpty() }

        view.button("Add to the collection").click()
        waitFor("the direction to take") { !view.button("Continue →").disabled }
        view.button("Continue →").click()
        waitFor("the box") { view.all("textarea").isNotEmpty() }
        val box = view.all("textarea").first() as org.w3c.dom.HTMLTextAreaElement
        box.value = "4 Sol Ring"
        box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
        waitFor("preview to arm") { !view.button("Preview changes →").disabled }
        view.button("Preview changes →").click()
        waitFor("the preview") { view.has("Add 1 printings") }

        // Twice in a row with nothing in between, the way a thumb
        // does it.
        val apply = view.button("Add 1 printings")
        apply.click()
        apply.click()
        waitFor("the write") { writes.isNotEmpty() }
        settle()
        assertEquals(listOf("/cards/add"), writes, "it wrote more than once")

        // And the button is not sitting there live while it happens.
        // The refusal above is the backstop for the frame between the
        // press and the redraw; this is what you can see.
        assertTrue(
            view.all("button").none { b ->
                b as HTMLButtonElement
                b.reads() == "Add 1 printings" && !b.disabled
            },
            "the apply button is still live after being pressed",
        )
    }

    // ------------------------------------------- uploading a file

    /** Pick a file the way the browser's picker hands one over. */
    private fun pick(input: HTMLElement, name: String, text: String) {
        val dt = js("new DataTransfer()")
        dt.items.add(js("(function(t, n) { return new File([t], n, {type: 'text/plain'}) })")(text, name))
        input.asDynamic().files = dt.files
        input.dispatchEvent(org.w3c.dom.events.Event("change", js("({bubbles: true})")))
    }

    @Test
    fun aFilePickedOnTheEntryBoxLandsInTheBox() = runTest {
        // Matt: "uploading file DOES NOT WORK AT ALL FOR ANYTHING.
        // Even adding new cards from inside the app itself".
        val view = mount("#/entry", token = "t")
        waitFor("the wizard") { view.all("button").isNotEmpty() }
        view.button("Add to the collection").click()
        waitFor("the direction to take") { !view.button("Continue →").disabled }
        view.button("Continue →").click()
        waitFor("the box") { view.all("textarea").isNotEmpty() }

        val input = view.all("input.file-in").firstOrNull() ?: error("the list step has no file input")
        pick(input, "manabox.txt", "4 Sol Ring\n1 Arcane Signet")
        waitFor("the file to land in the box") {
            (view.all("textarea").first() as org.w3c.dom.HTMLTextAreaElement).value.contains("Arcane Signet")
        }
    }

    @Test
    fun aFilePickedOnTheNewDeckCardsStepLandsInTheDecksBox() = runTest {
        val view = mount("#/entry", token = "t")
        waitFor("the entry wizard") { view.textContent.orEmpty().contains("What are you doing?") }
        view.all("button.opt").first { it.textContent.orEmpty().contains("New deck") }.click()
        settle()
        pressOnThePage("Continue →")
        waitFor("the wizard") { palette() != null }
        suspend fun onward(there: () -> Boolean) {
            waitFor("Continue to come alive") {
                paletteButtons().any { it.says() == "Continue →" && !it.disabled }
            }
            press("Continue →")
            waitFor("the next step", cond = there)
        }
        press("Commander")
        onward { box("Deck name") != null }
        typeInto("Deck name", "Test Deck")
        onward { box("e.g. Alela, Artful Provocateur") != null }
        typeInto("e.g. Alela, Artful Provocateur", "Alela, Cunning Conqueror")
        onward { palette()?.querySelector("textarea") != null }

        val input = palette()!!.querySelector("input.file-in") as? HTMLElement
            ?: error("the cards step has no file input")
        pick(input, "deck.txt", "1 Sol Ring\n1 Arcane Signet")
        waitFor("the file to land in the deck's box") {
            (palette()!!.querySelector("textarea") as org.w3c.dom.HTMLTextAreaElement).value.contains("Arcane Signet")
        }
    }

    // ------------------------------------------- the suggestion list

    private fun acInput() = document.querySelector(".ac .field") as org.w3c.dom.HTMLInputElement
    private fun acItems(): List<HTMLElement> =
        document.querySelectorAll(".ac-list li").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLElement }
        }

    /** Type the way a person does, one event the field will believe. */
    private fun typeName(text: String) {
        val box = acInput()
        box.value = text
        box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
    }

    private suspend fun openSuggestions(): List<HTMLElement> {
        mount("#/search")
        waitFor("the search box") { document.querySelector(".ac .field") != null }
        typeName("Vesu")
        waitFor("the suggestions") { acItems().isNotEmpty() }
        return acItems()
    }

    @Test
    fun aSuggestionTakenWithATapLandsInTheBox() = runTest {
        // A phone sends pointer events and a click. It does not
        // promise a mousedown, and mousedown was the only thing the
        // list listened to — so every tap on a suggestion did
        // precisely nothing, on the one device this app is built for.
        val items = openSuggestions()
        assertEquals(listOf("Vesuva", "Vesuvan Mist"), items.map { it.textContent?.trim() })

        // The whole gesture, in the order a browser sends it: the
        // pointer arrives over the row, then the press. The enter was
        // the part that put the list back — it wrote a state derived
        // from a capture that the pick had already replaced.
        items[1].dispatchEvent(org.w3c.dom.events.MouseEvent("mouseenter", js("({bubbles: false})")))
        items[1].dispatchEvent(org.w3c.dom.events.MouseEvent("click", js("({bubbles: true, cancelable: true})")))
        items[1].dispatchEvent(org.w3c.dom.events.MouseEvent("mouseenter", js("({bubbles: false})")))
        waitFor("the pick to land") { acInput().value == "Vesuvan Mist" }
        settle()
        assertTrue(acItems().isEmpty(), "the list came back after a tap picked something")
    }

    @Test
    fun pressingSomewhereElseClosesTheSuggestions() = runTest {
        openSuggestions()
        document.body!!.dispatchEvent(
            org.w3c.dom.events.MouseEvent("pointerdown", js("({bubbles: true, cancelable: true})")),
        )
        waitFor("the list to close") { acItems().isEmpty() }
    }

    @Test
    fun theSuggestionListIsNotCroppedByThePanelAroundIt() = runTest {
        // The panel clips its children so its corners stay round. The
        // suggestions are absolutely positioned and hang below it, so
        // the clip cut the list down to whatever happened to fit —
        // two rows, with no way to scroll to the rest.
        val items = openSuggestions()
        Stylesheet.load()
        waitFor("the stylesheet") { Stylesheet.applied() }
        val panel = document.querySelector(".panel") as HTMLElement
        assertEquals(
            "visible",
            window.getComputedStyle(panel).getPropertyValue("overflow"),
            "the panel is still clipping the suggestions",
        )
        val last = items.last().getBoundingClientRect()
        val clip = panel.getBoundingClientRect()
        assertTrue(
            last.bottom > clip.bottom,
            "this no longer proves anything: the list now fits inside the panel",
        )
        assertTrue(last.height > 40, "a suggestion is ${last.height}px tall, too small to hit")
    }

    @Test
    fun aToastCanBePutAwayAndNeverSwallowsAPress() = runTest {
        // A real toast off a real action rather than a test-only
        // hook: Download says how many cards it wrote.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        view.button("Download").click()
        waitFor("the toast") { document.querySelector(".toast") != null }

        Stylesheet.load()
        waitFor("the stylesheet") { Stylesheet.applied() }
        val tray = document.querySelector(".toasts") as HTMLElement
        assertEquals(
            "none",
            window.getComputedStyle(tray).getPropertyValue("pointer-events"),
            "the toast tray is still taking presses meant for the page",
        )
        (document.querySelector(".toast") as HTMLElement).click()
        waitFor("the toast to go") { document.querySelector(".toast") == null }
    }

    @Test
    fun aToastGoesAwayOnItsOwn() = runTest {
        // It used to stay until something else replaced it. On a
        // phone that is a full-width bar parked over the screen's own
        // buttons for the rest of the session.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        view.button("Download").click()
        waitFor("the toast") { document.querySelector(".toast") != null }
        waitFor("the toast to fade", upTo = 9000) { document.querySelector(".toast") == null }
    }

    @Test
    fun puttingTheSuggestionListAwayNeverChangesTheSearch() = runTest {
        // Closing the list goes out on its own callback now, not as
        // a rebuilt `Completion` down the one that means "the name
        // changed" — a `Completion` carries the term, so that path
        // could hand the filter a name from whenever the field last
        // drew.
        //
        // Honest about what this proves: it holds the property, it
        // does not reproduce the failure. Making that path wrong
        // again leaves this green, because the stale value only
        // differs from the live one when the browser has stopped
        // painting between two edits, and a browser running tests
        // never stops painting. Kept because the property is the
        // thing worth defending.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        typeName("bolt")
        waitFor("the list to narrow", upTo = 4000) { view.all("div.card").size == 1 }
        typeName("")
        waitFor("the list to widen", upTo = 4000) { view.all("div.card").size == 2 }

        repeat(4) {
            document.body!!.dispatchEvent(
                org.w3c.dom.events.MouseEvent("pointerdown", js("({bubbles: true, cancelable: true})")),
            )
            settle()
        }
        rest(1200)
        assertEquals("", acInput().value, "a press put a name back in the box")
        assertEquals(
            2,
            view.all("div.card").size,
            "a press somewhere else narrowed the search again",
        )
    }

    @Test
    fun clearingTheNameSearchesAgain() = runTest {
        // Typing narrows the list; emptying the box widens it back.
        //
        // The reported failure was an emptied box leaving the
        // narrowed results on screen. This covers the behaviour but
        // not that failure: it stays green against the code that was
        // reported broken, because the comparison it got wrong is
        // only wrong when nothing has been painted between the two
        // edits, and the test browser always paints. `LibrarySweepTest`
        // does the same thing to every box on the page.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        waitFor("the first search") { searches >= 1 }

        assertEquals(2, view.all("div.card").size, "the stub is not answering with two cards")

        typeName("bolt")
        waitFor("the list to narrow", upTo = 4000) { view.all("div.card").size == 1 }

        typeName("")
        waitFor("the list to widen again", upTo = 4000) { view.all("div.card").size == 2 }
    }

    @Test
    fun andDoesSoWithNoFrameDrawnInBetween() = runTest {
        // The two edits with no `waitFor` between them. Closer to
        // the shape of the bug, still not a reproduction: the
        // debounce means the first search reads the state as it is
        // when it finally runs, which is after both edits, so the
        // right answer comes back anyway.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        waitFor("the first search") { searches >= 1 }
        val before = searches

        typeName("bolt")
        typeName("")
        rest(1500)
        assertEquals("", acInput().value, "the box is not actually empty")
        assertEquals(
            2,
            view.all("div.card").size,
            "the box is empty and the narrowed results are still on screen",
        )
        assertTrue(searches > before, "nothing was asked at all")
    }

    @Test
    fun comingBackFromACardDoesNotAskForTheSameHundredCardsAgain() = runTest {
        // The route changing is what asks for a load, so back from a
        // card re-ran the whole search. The grid was replaced by
        // "Searching…" while it did, the page lost every pixel of its
        // height, and the offset being restored into it had nothing
        // to hold — so you came back to the top of a list you were
        // two hundred cards down.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        waitFor("the first search to land") { searches >= 1 }
        val asked = searches

        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }
        window.history.back()
        waitFor("the list again") { view.all("div.card").isNotEmpty() && cardPages() == 0 }
        // Past the debounce and the round trip, so a search that was
        // going to happen has happened.
        rest(900)
        assertEquals(asked, searches, "it searched again for rows it already had")
    }

    @Test
    fun andTheGridStaysPutWhileANewSearchRuns() = runTest {
        // Even a real re-search keeps the page its own height. One
        // line of "Searching…" where a hundred tiles were is a
        // collapse, and a collapse loses wherever you were in it.
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        val before = view.all("div.card").size
        assertTrue(before > 0, "nothing on screen to keep")

        typeName("bolt")
        // One frame: enough to redraw, well inside the debounce, so
        // this is the moment the search is pending and nothing has
        // come back yet.
        tick()
        assertTrue(
            view.textContent.orEmpty().contains("Searching"),
            "no search is in flight; this proves nothing",
        )
        assertEquals(
            before,
            view.all("div.card").size,
            "the grid emptied itself while it waited for the next answer",
        )
    }

    @Test
    fun aDeckCanBeReadOneCardAtATimeWithoutGoingBack() = runTest {
        // Opening a card, pressing back and opening the next is three
        // gestures for every card in a hundred-card deck.
        val view = mount("#/decks/alela")
        waitFor("the deck") { view.all(".deck-line").isNotEmpty() }
        view.all(".deck-line").first().click()
        waitFor("the carousel") { document.querySelector(".peek-scrim") != null }
        pressOnThePage("Full details")
        waitFor("the card page") { cardPages() == 1 }
        settle()

        val place = document.querySelector(".step-place")?.textContent.orEmpty()
        assertTrue(Regex("""\d+ of \d+""").matches(place), "no position shown, got '$place'")

        val next = document.querySelector(".step-next") as? HTMLButtonElement
        assertTrue(next != null, "no next control on a card opened from a deck")
        assertTrue(
            (document.querySelector(".step-prev") as HTMLButtonElement).disabled,
            "the first card offered a previous",
        )
    }

    @Test
    fun aCardOpenedFromTheLibraryHasNoDeckToStepThrough() = runTest {
        val view = mount("#/search")
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }
        settle()
        assertTrue(
            document.querySelector(".card-steps") == null,
            "a card with no deck behind it offered to step through one",
        )
    }

    @Test
    fun aCardOpensAtTheTopOfItself() = runTest {
        val view = mount("#/search")
        settle()
        waitFor("the grid") { view.all("div.card").isNotEmpty() }
        val filler = document.createElement("div") as HTMLElement
        filler.style.height = "4000px"
        document.body!!.appendChild(filler)
        roots += filler
        window.scrollTo(0.0, 1300.0)
        assertTrue(window.scrollY > 1000, "the page would not scroll, nothing to test")

        openFromTheGrid(view)
        waitFor("the card page") { cardPages() == 1 }
        settle()
        assertTrue(window.scrollY < 10, "the card opened at ${window.scrollY}")
    }

    // ------------------------------------------------- release notes

    /**
     * Opening Admin Settings asks GitHub for the builds and lists them.
     *
     * The shell tests hand the page a loaded `Releases`; this is the
     * part they cannot see, that anything ever loads one.
     */
    @Test
    fun openingAdminSettingsLoadsTheReleaseNotes() = runTest {
        role = "admin"
        val root = mount("#/admin")
        waitFor("a release note on Admin Settings") {
            root.all("[data-release]").isNotEmpty()
        }
        val text = root.all("[data-release]").first().textContent.orEmpty()
        assertTrue("2.1.0 (297)" in text && "Release notes in Admin Settings." in text, text)
    }

    // ------------------------------------------------------------ tasks

    /**
     * Admin Settings asks GitHub for the tasks on every visit to the
     * list, and opening one person does not.
     *
     * `TasksPanelTest` hands the page a loaded `Tasks`; this is the part
     * it cannot see. Sibling of Android's `TasksLoadTest`.
     */
    @Test
    fun adminSettingsLoadsTheTasksOnEveryVisitToTheList() = runTest {
        role = "admin"
        val root = mount("#/admin")
        waitFor("a task on Admin Settings") { root.all("[data-task]").isNotEmpty() }
        val text = root.all("[data-task]").first().textContent.orEmpty()
        assertTrue("Task status in the app" in text && "in review" in text, text)
        assertEquals(1, tasksAsked, "the first visit should ask GitHub once")

        window.location.hash = "#/admin/t4pee71g"
        waitFor("one person's page") { window.location.hash.endsWith("/admin/t4pee71g") }
        settle()
        assertEquals(1, tasksAsked, "opening one person asked GitHub for the tasks again")

        window.location.hash = "#/admin"
        waitFor("the list to ask GitHub again") { tasksAsked == 2 }
    }
}
