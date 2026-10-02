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
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every box in the filter panel, filled in and emptied again.
 *
 * One bug, found by the person who uses this: type a name, delete it,
 * and the results stayed narrowed. It was in the field everybody
 * reaches for first and it shipped, because the suite only ever
 * checked that typing narrows — never that clearing widens. The same
 * omission applied to every other box on the page.
 *
 * So this sweeps them. For each box: put in something that matches
 * one card, prove the grid narrowed, empty it, prove the grid came
 * back. A box that stops applying and a box that stops un-applying
 * both fail here, and adding a filter to the panel without adding it
 * to this list fails `everyTextFilterIsSwept` below.
 */
class LibrarySweepTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun stubTheNetwork() {
        val engine = MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val sql = FakeServer.sqlOf(body)
            val params = FakeServer.paramsOf(body)
            val json = when {
                "MIN(c.id) AS id" in sql -> FakeServer.libraryPage(sql, params)
                "SELECT COUNT(*)" in sql -> FakeServer.libraryCount(params)
                else -> """{"cols":["x"],"rows":[],"n":0}"""
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

    private suspend fun tick(ms: Int = 40) {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, ms) }.await()
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private suspend fun waitFor(what: String, upTo: Int = 6000, cond: () -> Boolean) {
        var waited = 0
        while (waited < upTo) {
            if (cond()) return
            tick()
            waited += 40
        }
        error("gave up waiting for $what")
    }

    private fun mount(): HTMLElement {
        window.location.hash = "#/search"
        val view = document.createElement("div") as HTMLElement
        document.body!!.appendChild(view)
        roots += view
        MtgApp.mount(view, null, "")
        return view
    }

    private fun cards() = document.querySelectorAll("div.card").length

    /** Every box on the page, by the placeholder the panel gives it. */
    private fun boxes(): List<HTMLInputElement> =
        document.querySelectorAll("input.field").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLInputElement }
        }

    private fun openEveryGroup() {
        document.querySelectorAll("details.fgroup summary").let { n ->
            (0 until n.length).forEach { i ->
                val d = (n[i] as HTMLElement).parentElement as HTMLElement
                if (!d.hasAttribute("open")) (n[i] as HTMLElement).click()
            }
        }
    }

    private fun type(box: HTMLInputElement, text: String) {
        box.value = text
        box.dispatchEvent(org.w3c.dom.events.Event("input", js("({bubbles: true})")))
    }

    /**
     * Which box, and a term that matches exactly one card in the
     * fake collection — so narrowing is visible rather than a guess.
     */
    private val sweep = listOf(
        Field("the name box at the top", "Card name", "vesuva", 2),
        Field("Name contains", "sol ring", "vesuva", 2),
        Field("Rules text", "draw card", "shroud", 1),
        Field("Flavour text", "", "kweh", 1),
        Field("Artist", "Rebecca Guay", "avon", 1),
        Field("Watermark", "", "boros", 1),
    )

    private data class Field(
        val what: String,
        val placeholder: String,
        val term: String,
        val expect: Int,
    )

    /**
     * The five boxes in the TEXT group, in the order the panel draws
     * them, plus the one at the top of the page. Two of them have no
     * placeholder, so position inside the group is the only handle
     * there is — which is itself worth asserting: if the panel grows
     * a box, the count below changes and this says so.
     */
    private fun textBoxes(): List<HTMLInputElement> {
        val group = document.querySelector("details[data-facet=text]") as? HTMLElement
            ?: error("the TEXT group is not on the page")
        return group.querySelectorAll("input.field").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLInputElement }
        }
    }

    private fun boxFor(f: Field): HTMLInputElement =
        if (f.what == "the name box at the top") {
            document.querySelector(".ac .field") as HTMLInputElement
        } else {
            val group = textBoxes()
            val i = sweep.indexOfFirst { it.what == f.what } - 1
            group.getOrNull(i) ?: error("no box ${f.what} in the TEXT group")
        }

    @Test
    fun everyBoxNarrowsAndThenWidensAgain() = runTest {
        val view = mount()
        waitFor("the grid") { cards() > 0 }
        val all = FakeServer.total
        assertEquals(all, cards(), "the fake collection is not all on screen to start")
        openEveryGroup()
        tick()

        sweep.forEach { f ->
            val box = boxFor(f)
            type(box, f.term)
            waitFor("${f.what} to narrow to ${f.expect}") { cards() == f.expect }

            type(box, "")
            waitFor("${f.what} to widen back to $all") { cards() == all }
        }
    }

    @Test
    fun andDoesSoWithNoFrameBetweenFillingAndEmptying() = runTest {
        // The bug's real shape: two edits inside one frame. Anything
        // deciding from a state captured by the last draw gets the
        // comparison wrong and skips the search.
        val view = mount()
        waitFor("the grid") { cards() > 0 }
        val all = FakeServer.total
        openEveryGroup()
        tick()

        sweep.forEach { f ->
            val box = boxFor(f)
            type(box, f.term)
            type(box, "")
            waitFor("${f.what} to end up wide again") { cards() == all }
            assertEquals("", box.value, "${f.what} is not empty")
        }
    }

    @Test
    fun everyTextFilterIsSwept() = runTest {
        // A box added to the panel and not to the sweep above is a
        // box nobody checks. The two empty placeholders are flavour
        // text and watermark, which the fake has no column for.
        val view = mount()
        waitFor("the grid") { cards() > 0 }
        openEveryGroup()
        tick()
        assertEquals(
            sweep.size - 1,
            textBoxes().size,
            "the TEXT group has a box the sweep above does not know about",
        )
    }
}
