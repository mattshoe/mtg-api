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

    /** Every write the app made, by path, so a second one is visible. */
    private val writes = mutableListOf<String>()

    @BeforeTest
    fun stubTheNetwork() {
        writes.clear()
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
            val json = when {
                body.contains("FROM decks d") && body.contains("art_id") ->
                    """{"cols":["slug","name","owner","commander","colors","bracket","art_id"],""" +
                        """"rows":[["alela","Fairy Deck","matt","Alela","UB",3,"abcdef12-3456"]],"n":1}"""

                body.contains("FROM deck_cards dc") ->
                    """{"cols":["name","name_norm","qty","role","owned","type_line","scryfall_id"],""" +
                        """"rows":[["Sol Ring","sol ring",1,null,1,"Artifact",null]],"n":1}"""

                request.url.encodedPath == "/cards/add" ->
                    """{"applied":true,"dry_run":false,"resolved":1,"failed":0,""" +
                        """"changes":[["Sol Ring","M3C","409","nonfoil",0,4]],"errors":[]}"""

                request.url.encodedPath.endsWith("/cards/autocomplete") ->
                    """{"object":"catalog","total_values":2,"data":["Vesuva","Vesuvan Mist"]}"""

                request.url.encodedPath == "/cards/validate" ->
                    """{"checked":1,"unknown":0,"ok":true,"cards":[],"bad":[],"suggestions":{}}"""

                request.url.encodedPath == "/decks/create" ->
                    """{"created":true,"applied":true,"slug":"new-deck","card_count":100,"rows":100,""" +
                        """"deck":{"slug":"new-deck","name":"New Deck","owner":"matt"},"added":[],"removed":[],""" +
                        """"changed":[],"acquired":[],"returned":[],"errors":[]}"""

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

    private fun hash() = window.location.hash

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
        view.all("div.card").first().click()
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
        view.all("div.card").first().click()
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
        view.all("div.card").first().click()
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
            view.all("div.card").first().click()
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
        view.all("div.deck-line, a.deck-line").first { it.textContent.orEmpty().contains("Sol Ring") }.click()
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

        view.all("div.card").first().click()
        waitFor("the card page") { cardPages() == 1 }
        settle()
        assertTrue(window.scrollY < 10, "the card opened at ${window.scrollY}")

        backButton().click()
        waitFor("the search again") { cardPages() == 0 }
        settle()
        assertTrue(window.scrollY > 1000, "← Back landed at ${window.scrollY}, not where the list was")
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
        val view = mount("#/decks", token = "t")
        waitFor("the deck list") { view.all("div.deck-card").isNotEmpty() }
        view.button("New deck").click()
        waitFor("the wizard") { palette() != null }

        // A step at a time. Each wait keys off something only the
        // next step has — the stepper down the side names every step
        // on every step, so waiting for the word "Whose" to appear
        // is waiting for something that never went away.
        suspend fun onward(nextStep: String, there: () -> Boolean) {
            waitFor("Continue to come alive") {
                paletteButtons().any { it.says() == "Continue →" && !it.disabled }
            }
            press("Continue →")
            waitFor("the $nextStep step", cond = there)
        }

        press("Commander")
        onward("owner") { option("Matt") != null }
        press("Matt")
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
        onward("review") { paletteButtons().any { it.says() == "All from bulk" } }
        press("All from bulk")
        waitFor("the create button to come alive") {
            paletteButtons().any { it.says() == "Create Test Deck" && !it.disabled }
        }
        press("Create Test Deck")

        waitFor("the deck to be made") { document.body!!.textContent.orEmpty().contains("Created Test Deck") }
        settle()
        val text = palette()?.textContent.orEmpty()
        assertTrue("Creating" !in text, "the panel is still saying it is creating: $text")
        assertTrue("Created" in text, "the panel never said it was done: $text")
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
        waitFor("the list to count") { !view.button("Continue →").disabled }
        view.button("Continue →").click()
        waitFor("the owner step") { view.has("Matt") }
        view.button("Matt").click()
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

        view.all("div.card").first().click()
        waitFor("the card page") { cardPages() == 1 }
        settle()
        assertTrue(window.scrollY < 10, "the card opened at ${window.scrollY}")
    }
}
