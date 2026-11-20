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
import org.w3c.dom.HTMLInputElement
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
 * The Guide's rules page: the real `MtgApp`, mounted at its address,
 * over a stubbed Worker, in a real browser.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... searching trample should show me all rules that mention trample
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default ... Ideally, this would use the live rule set,
 * then parse it out on-device". Sibling of `RulesParityTest` and
 * `RulesLoadTest` on the phone.
 */
class RulesPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var realFetch: dynamic = null
    private var screen: Tablet.Screen? = null

    /** How many times the app asked the Worker for the rules. */
    private var asked = 0

    /** Answer `/rules` with a 502 this many times before the text. */
    private var refusals = 0

    @BeforeTest
    fun stubTheNetwork() {
        Stylesheet.load()
        asked = 0
        refusals = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/rules") {
                asked++
                return@MockEngine if (asked <= refusals) {
                    respond(
                        """{"error":"Wizards answered 503 for the rules text"}""",
                        HttpStatusCode.BadGateway,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(TEXT, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            }
            respond(
                """{"cols":[],"rows":[],"n":0}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
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
        shownIn = document
        val back = realFetch
        if (back != null) window.asDynamic().fetch = back
        Stylesheet.unload()
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
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

    private fun mount(hash: String, into: HTMLElement? = null): HTMLElement {
        window.location.hash = hash
        val view = into ?: (document.createElement("div") as HTMLElement).also {
            it.style.width = "400px"
            document.body!!.appendChild(it)
            roots += it
        }
        val nav = document.createElement("div") as HTMLElement
        document.body!!.appendChild(nav)
        roots += nav
        MtgApp.mount(view, null, "")
        MtgApp.mountNav(nav)
        return view
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private suspend fun HTMLElement.search(words: String) {
        val box = all("input.rules-search").singleOrNull() as? HTMLInputElement
            ?: error("the rules page has no search bar: ${textContent}")
        box.value = words
        box.dispatchEvent(Event("input"))
        tick()
    }

    private fun HTMLElement.numbers() = all(".rule-card .rule-number").map { it.textContent.orEmpty().trim() }

    @Test
    fun theGuideHasARulesTile() = runTest {
        val view = mount("#/guide")
        waitFor("the Guide's tiles") { view.all("button.guide-tile").isNotEmpty() }
        val titles = view.all("button.guide-tile .guide-tile-title").map { it.textContent.orEmpty() }
        assertTrue("Rules" in titles, "the Guide has no rules tile: $titles")
        view.all("button.guide-tile").first { it.textContent.orEmpty().contains("Rules") }.click()
        waitFor("the rules page") { view.all("input.rules-search").isNotEmpty() }
        assertEquals("#/guide/rules", window.location.hash.replace(Regex("^#/c/[^/]+/"), "#/"))
    }

    @Test
    fun searchingTrampleShowsACardForEveryRuleThatMentionsIt() = runTest {
        val view = mount("#/guide/rules")
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        assertEquals(1, asked, "the rules were asked for $asked times")
        view.search("trample")
        waitFor("the trample rules") { view.numbers().isNotEmpty() }
        assertEquals(listOf("702.19", "702.19a", "702.19b", "702.19c"), view.numbers())

        val bar = view.all("input.rules-search").single().getBoundingClientRect()
        val first = view.all(".rule-card").first().getBoundingClientRect()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the results: ${bar.bottom} vs ${first.top}")
    }

    @Test
    fun eachRuleIsACardOfItsOwn() = runTest {
        val view = mount("#/guide/rules")
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("trample")
        waitFor("the trample rules") { view.numbers().size == 4 }
        val cards = view.all(".rule-card")
        val look = window.getComputedStyle(cards[0])
        assertTrue(look.borderTopLeftRadius != "0px", "the card has square corners, so it is not styled")
        assertTrue(look.boxShadow != "none", "the card has no shadow, so it sits flat on the page")
        val a = cards[0].getBoundingClientRect()
        val b = cards[1].getBoundingClientRect()
        assertTrue(b.top > a.bottom, "the cards touch, so it reads as one block of text")
        val number = window.getComputedStyle(cards[1].all(".rule-number").single()).fontWeight.toInt()
        val text = window.getComputedStyle(cards[1].all(".rule-text").single()).fontWeight.toInt()
        assertTrue(number > text, "the number ($number) is no heavier than the text ($text), so nothing marks where a rule begins")
    }

    @Test
    fun everyRuleStartsExpandedAndATapOnItsHeadingFoldsIt() = runTest {
        val view = mount("#/guide/rules")
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("trample")
        waitFor("the trample rules") { view.numbers().size == 4 }
        val bodies = view.all(".rule-card .rule-body")
        assertEquals(4, bodies.size, "a rule started collapsed")
        bodies.forEach { assertTrue(it.getBoundingClientRect().height > 0, "an open rule's text has no height") }
        assertTrue(
            view.all(".rule-example").any { it.textContent.orEmpty().startsWith("A 6/6 green creature") },
            "702.19b's examples are not shown",
        )

        (view.all(".rule-card")[2].all("button.rule-head").single() as HTMLButtonElement).click()
        waitFor("702.19b to fold") { view.all(".rule-card .rule-body").size == 3 }
        assertEquals(4, view.numbers().size, "a folded rule left the list rather than folding")
        assertTrue(view.all(".rule-example").none { it.textContent.orEmpty().startsWith("A 6/6 green creature") })

        (view.all(".rule-card")[2].all("button.rule-head").single() as HTMLButtonElement).click()
        waitFor("702.19b to open again") { view.all(".rule-card .rule-body").size == 4 }
    }

    @Test
    fun aRefusalLeavesNoSpinnerSaysWhyAndTryAgainLoadsThem() = runTest {
        // Refused until Try again is pressed. The page may ask more than
        // once on its own — it loads again when the address's collection
        // resolves — and a single refusal was used up by that second load
        // the moment `/c/` answered without a network round trip.
        refusals = Int.MAX_VALUE
        val view = mount("#/guide/rules")
        waitFor("the load to fail") { view.all(".rules-failed").isNotEmpty() }
        assertTrue(view.all(".rules-loading").isEmpty(), "the spinner is still up after the load failed")
        val said = view.all(".rules-failed .err").single().textContent.orEmpty()
        assertEquals("Could not load the rules: Wizards answered 503 for the rules text", said)

        refusals = asked
        (view.all("button.rules-retry").singleOrNull() ?: error("there is no Try again")).click()
        waitFor("the rules to load on the second try") { view.all(".rules-hint").isNotEmpty() }
        assertTrue(view.all(".rules-failed").isEmpty(), "the old error is still on the page")
        assertEquals(refusals + 1, asked, "Try again should ask exactly once more")
        view.search("trample")
        waitFor("the trample rules") { view.numbers().size == 4 }
    }

    /**
     * Matt: "i want rules searches to support "quoted" text that searches
     * anything in the quotes as a single string instead of tokenized
     * words". 702.19a has "assigning", "combat" and "damage" apart; only
     * 702.19c has them side by side. The phone's `RulesParityTest` makes
     * the same claim.
     */
    private suspend fun aQuotedPhraseFindsOnlyTheRuleWithItSideBySide(into: HTMLElement? = null) {
        val view = mount("#/guide/rules", into)
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("assigning combat damage")
        waitFor("the loose words") { view.numbers().isNotEmpty() }
        assertEquals(listOf("702.19a", "702.19c"), view.numbers(), "loose words should still be found apart")
        view.search("\"assigning combat damage\"")
        waitFor("the quoted phrase") { view.numbers().size != 2 }
        assertEquals(listOf("702.19c"), view.numbers(), "the quoted phrase was searched as separate words")
    }

    @Test
    fun aQuotedPhraseIsSearchedAsOneString() = runTest { aQuotedPhraseFindsOnlyTheRuleWithItSideBySide() }

    @Test
    fun aQuotedPhraseIsSearchedAsOneStringOnAFoldingPhoneOpenedOut() = runTest {
        aQuotedPhraseFindsOnlyTheRuleWithItSideBySide(Tablet.open(Tablet.UNFOLDED).also { screen = it }.body)
    }

    @Test
    fun aQuotedPhraseIsSearchedAsOneStringOnATabletHeldSideways() = runTest {
        aQuotedPhraseFindsOnlyTheRuleWithItSideBySide(Tablet.open(Tablet.LANDSCAPE).also { screen = it }.body)
    }

    /**
     * Matt: "rules searches should support - for exclusion ... -trample
     * should exclude results with the word trample, and -"when a
     * creature" should exclude that whole string". The phone's
     * `RulesParityTest` makes the same claim.
     */
    private suspend fun aMinusLeavesOutRulesWithTheWordOrTheWholePhrase(into: HTMLElement? = null) {
        val view = mount("#/guide/rules", into)
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("trample")
        waitFor("the trample rules") { view.numbers().size == 4 }
        view.search("trample -planeswalkers")
        waitFor("-planeswalkers") { view.numbers().size != 4 }
        assertEquals(listOf("702.19", "702.19a", "702.19b"), view.numbers(), "-planeswalkers did not leave 702.19c out")
        view.search("trample -\"combat damage\"")
        waitFor("-\"combat damage\"") { view.numbers().size != 3 }
        assertEquals(listOf("702.19", "702.19b"), view.numbers(), "-\"combat damage\" did not leave out the rules saying it")
    }

    @Test
    fun aMinusLeavesOutWhatFollowsIt() = runTest { aMinusLeavesOutRulesWithTheWordOrTheWholePhrase() }

    @Test
    fun aMinusLeavesOutWhatFollowsItOnAFoldingPhoneOpenedOut() = runTest {
        aMinusLeavesOutRulesWithTheWordOrTheWholePhrase(Tablet.open(Tablet.UNFOLDED).also { screen = it }.body)
    }

    @Test
    fun aMinusLeavesOutWhatFollowsItOnATabletHeldSideways() = runTest {
        aMinusLeavesOutRulesWithTheWordOrTheWholePhrase(Tablet.open(Tablet.LANDSCAPE).also { screen = it }.body)
    }

    /** What the stubbed clipboard was handed, and how many writes it refuses first. */
    private val copied = mutableListOf<String>()
    private var clipRefusals = 0

    private fun stubTheClipboard() {
        copied.clear()
        val stub = js("({})")
        stub.writeText = { text: String ->
            if (clipRefusals-- > 0) Promise.reject(Throwable("NotAllowedError: Write permission denied."))
            else { copied += text; Promise.resolve(Unit) }
        }
        val descriptor = js("({configurable: true})")
        descriptor.value = stub
        js("Object").defineProperty(window.navigator, "clipboard", descriptor)
    }

    private fun pointer(type: String): Event {
        val init = js("({bubbles: true, cancelable: true, pointerId: 1, pointerType: 'touch', isPrimary: true})")
        return js("function(t, i) { return new PointerEvent(t, i); }")(type, init).unsafeCast<Event>()
    }

    /**
     * A finger held down for most of a second and lifted, as a phone
     * browser sends it: down, then the context menu the browser would
     * open, then up, then the click a lift-off makes. Says whether the
     * page kept the browser's own menu from opening over it.
     */
    private suspend fun HTMLElement.holdDown(): Boolean {
        dispatchEvent(pointer("pointerdown"))
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 700) }.await()
        val menu = Event("contextmenu", js("({bubbles: true, cancelable: true})"))
        dispatchEvent(menu)
        dispatchEvent(pointer("pointerup"))
        click()
        tick()
        return menu.defaultPrevented
    }

    /** The toast, in whichever document the page was mounted into: a tablet frame has its own. */
    private var shownIn: org.w3c.dom.Document = document
    private fun toast(): HTMLElement? = shownIn.querySelector(".toast") as? HTMLElement

    /**
     * Matt: "i want to be able to copy rules in the rules page. long
     * press should copy the whole rule (number included)". A press held
     * on the heading or on the text copies the rule, number first, then
     * its examples; it does not fold the card. The phone's
     * `RulesParityTest` makes the same claim.
     */
    private suspend fun aLongPressCopiesTheWholeRuleNumberIncluded(into: HTMLElement? = null) {
        stubTheClipboard()
        try {
            val view = mount("#/guide/rules", into)
            shownIn = view.ownerDocument ?: document
            waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
            view.search("trample")
            waitFor("the trample rules") { view.numbers().size == 4 }

            val kept = view.all(".rule-card")[2].all("button.rule-head").single().holdDown()
            waitFor("702.19b on the clipboard") { copied.isNotEmpty() }
            assertEquals(RULE_702_19B, copied.single(), "the long press did not copy the whole of 702.19b")
            assertTrue(kept, "the browser's own menu would open over the long press")
            waitFor("the toast") { toast() != null }
            assertEquals("Rule 702.19b copied", toast()!!.textContent.orEmpty().removeSuffix("×").trim())
            assertTrue(!toast()!!.classList.contains("bad"), "a copy that landed was styled as a failure")
            assertEquals(4, view.all(".rule-card .rule-body").size, "the long press folded the card as well as copying it")

            view.all(".rule-card")[1].all(".rule-text").single().holdDown()
            waitFor("702.19a on the clipboard") { copied.size == 2 }
            assertEquals(
                "702.19a Trample is a static ability that modifies the rules for assigning an attacking creature’s combat damage.",
                copied[1],
                "a long press on a rule's text did not copy that rule",
            )

            // A tap is still a tap: it folds and copies nothing.
            (view.all(".rule-card")[2].all("button.rule-head").single() as HTMLButtonElement).click()
            waitFor("702.19b to fold on a tap") { view.all(".rule-card .rule-body").size == 3 }
            assertEquals(2, copied.size, "a plain tap copied the rule")
        } finally {
            js("delete navigator.clipboard")
        }
    }

    @Test
    fun aLongPressCopiesTheWholeRule() = runTest { aLongPressCopiesTheWholeRuleNumberIncluded() }

    @Test
    fun aLongPressCopiesTheWholeRuleOnAFoldingPhoneOpenedOut() = runTest {
        aLongPressCopiesTheWholeRuleNumberIncluded(Tablet.open(Tablet.UNFOLDED).also { screen = it }.body)
    }

    @Test
    fun aLongPressCopiesTheWholeRuleOnATabletHeldSideways() = runTest {
        aLongPressCopiesTheWholeRuleNumberIncluded(Tablet.open(Tablet.LANDSCAPE).also { screen = it }.body)
    }

    @Test
    fun aClipboardThatRefusesSaysSoAndALongPressCanTryAgain() = runTest {
        clipRefusals = 1
        stubTheClipboard()
        try {
            val view = mount("#/guide/rules")
            waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
            view.search("trample")
            waitFor("the trample rules") { view.numbers().size == 4 }
            view.all(".rule-card")[2].all("button.rule-head").single().holdDown()
            waitFor("the refusal to be said") { toast() != null }
            assertEquals(
                "Could not copy rule 702.19b: the clipboard refused it",
                toast()!!.textContent.orEmpty().removeSuffix("×").trim(),
            )
            assertTrue(toast()!!.classList.contains("bad"), "a refused copy was styled as a success")
            assertEquals(4, view.all(".rule-card .rule-body").size, "the rules page did not survive the refused copy")

            view.all(".rule-card")[2].all("button.rule-head").single().holdDown()
            waitFor("the second try to land") { copied.isNotEmpty() }
            assertEquals(RULE_702_19B, copied.single(), "a second long press did not copy it after the first was refused")
            waitFor("the toast to say so") { toast()?.textContent.orEmpty().contains("Rule 702.19b copied") }
        } finally {
            clipRefusals = 0
            js("delete navigator.clipboard")
        }
    }

    @Test
    fun aSearchWithNoMatchSaysSo() = runTest {
        val view = mount("#/guide/rules")
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("zzzz")
        waitFor("the page to say so") { view.textContent.orEmpty().contains("No rule mentions \"zzzz\".") }
        assertTrue(view.all(".rule-card").isEmpty())
    }

    private companion object {
        const val RULE_702_19B =
            "702.19b The controller of an attacking creature with trample first assigns damage to the creature(s) blocking it.\n" +
                "Example: A 2/2 creature that can block an additional creature blocks two attackers: a 1/1 with no abilities and a 3/3 with trample.\n" +
                "Example: A 6/6 green creature with trample is blocked by a 2/2 creature with protection from green."

        val TEXT = """
            |Magic: The Gathering Comprehensive Rules
            |
            |These rules are effective as of September 25, 2026.
            |
            |Credits
            |
            |7. Additional Rules
            |
            |702. Keyword Abilities
            |
            |702.19. Trample
            |
            |702.19a Trample is a static ability that modifies the rules for assigning an attacking creature’s combat damage.
            |
            |702.19b The controller of an attacking creature with trample first assigns damage to the creature(s) blocking it.
            |Example: A 2/2 creature that can block an additional creature blocks two attackers: a 1/1 with no abilities and a 3/3 with trample.
            |Example: A 6/6 green creature with trample is blocked by a 2/2 creature with protection from green.
            |
            |702.19c Trample over planeswalkers is a variant of trample that modifies the rules for assigning combat damage to planeswalkers.
            |
            |702.20. Vigilance
            |
            |702.20a Vigilance is a static ability that modifies the rules for the declare attackers step.
            |
            |Glossary
        """.trimMargin()
    }
}
