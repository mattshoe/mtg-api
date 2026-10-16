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
        val back = realFetch
        if (back != null) window.asDynamic().fetch = back
        Stylesheet.unload()
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
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

    private fun mount(hash: String): HTMLElement {
        window.location.hash = hash
        val view = document.createElement("div") as HTMLElement
        view.style.width = "400px"
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

    @Test
    fun aSearchWithNoMatchSaysSo() = runTest {
        val view = mount("#/guide/rules")
        waitFor("the rules to load") { view.all(".rules-hint").isNotEmpty() }
        view.search("zzzz")
        waitFor("the page to say so") { view.textContent.orEmpty().contains("No rule mentions \"zzzz\".") }
        assertTrue(view.all(".rule-card").isEmpty())
    }

    private companion object {
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
