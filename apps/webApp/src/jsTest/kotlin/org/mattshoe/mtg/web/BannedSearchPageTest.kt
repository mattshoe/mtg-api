package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Banned
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the banned cards by name, and the carousel saying where a
 * card is banned, in a real browser, through the real menu and shell,
 * on a phone and on a tablet both ways.
 *
 * Matt: "the banned cards should have a search field and the carousel
 * should show which formats for which s card is banned". Sibling of
 * `BannedSearchParityTest` on the phone.
 */
class BannedSearchPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window = window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** A phone's width, through the menu, its Guide, then the Banned cards tile. */
    private suspend fun openOnAPhone(): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "400px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = remember { mutableStateOf(AppState()) }
            AppNav(held.value, onState = { held.value = it })
            AppShell(state = held.value, onState = { held.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle()
        frame.all("button.nav-burger").single().click()
        settle()
        frame.all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        frame.all("button.guide-tile").first { it.textContent.orEmpty().contains("Banned cards") }.click()
        settle()
        return frame
    }

    private suspend fun HTMLElement.search(words: String, w: org.w3c.dom.Window = window) {
        val box = all("input.ban-search").singleOrNull()?.unsafeCast<HTMLInputElement>()
            ?: error("the banned cards page has no search bar")
        box.value = words
        box.dispatchEvent(Event("input"))
        settle(w)
    }

    /** Each format left on the page, with the names of its rows. */
    private fun HTMLElement.sections(): List<Pair<String, List<String>>> = all(".ban-list .ban-format").map { panel ->
        panel.all(".panel-head h2").single().textContent.orEmpty() to panel.all(".deck-line .t-name").map { it.textContent.orEmpty() }
    }

    @Test
    fun aNameLeavesOnlyTheFormatsThatBanItAndTheCarouselSaysWhereItIsBanned() = runTest {
        val page = openOnAPhone()
        page.search("black lotus")
        assertEquals(
            listOf("Commander" to listOf("Black Lotus"), "Legacy" to listOf("Black Lotus")),
            page.sections(),
            "searching black lotus did not leave Commander's and Legacy's Black Lotus",
        )
        assertEquals("2 of ${Banned.pageOrder.size} cards", page.all(".ban-search-count").singleOrNull()?.textContent?.trim(), "nothing says how many were found")
        val bar = page.all("input.ban-search").single().getBoundingClientRect()
        val first = page.all(".ban-list .ban-format").first().getBoundingClientRect()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the lists: ${bar.bottom} vs ${first.top}")
        assertTrue(bar.right <= page.getBoundingClientRect().right + 0.5, "the search bar runs off a 400px screen")

        page.all(".ban-list .ban-format")[1].all(".deck-line").single().click()
        settle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Black Lotus opened no carousel")
        assertEquals("Black Lotus", page.all(".peek-name").single().textContent)
        assertEquals("2 of 2", page.all(".peek-place").single().textContent, "the carousel is over every banned card, not the ones found")
        val tag = page.all(".peek-tags .tag").firstOrNull { it.textContent.orEmpty().startsWith("Banned in") }
            ?: error("the carousel does not say where Black Lotus is banned: ${page.all(".peek-tags .tag").map { it.textContent }}")
        assertEquals("Banned in Commander and Legacy", tag.textContent)
        assertTrue(tag.classList.contains("bad"), "a ban is not drawn as a bad fact")
        val sheet = page.all(".peek-sheet").single().getBoundingClientRect()
        assertTrue(tag.getBoundingClientRect().right <= sheet.right + 0.5, "the ban runs off the carousel's sheet")
    }

    @Test
    fun aNameNothingBansSaysSoAndClearingItBringsBackEveryList() = runTest {
        val page = openOnAPhone()
        page.search("llanowar elves")
        assertEquals("No banned card is called \"llanowar elves\".", page.all(".ban-search-none").singleOrNull()?.textContent?.trim())
        assertTrue(page.sections().isEmpty(), "lists are left under a search that found nothing: ${page.sections()}")
        page.search("")
        assertEquals(Banned.pageOrder.size, page.all(".ban-list .deck-line").size, "clearing the search did not bring back every banned card")
        assertTrue(page.all(".ban-search-count").isEmpty(), "a count is left over lists nobody searched")
    }

    private suspend fun searchReads(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val state = mutableStateOf(AppState().navigate(Route.parse("#/guide/${Guide.BANNED}")))
        renderComposable(root = s.body) {
            AppShell(state = state.value, onState = { state.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"
        doc.body!!.unsafeCast<HTMLElement>().search("mana crypt", s.window)

        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        assertEquals(listOf("Commander", "Legacy"), all(".ban-list .ban-format .panel-head h2").map { it.textContent }, "$on searching mana crypt did not leave Commander and Legacy")
        val page = all(".guide").single().getBoundingClientRect()
        val list = all(".ban-list").single().getBoundingClientRect()
        assertTrue(list.width in 320.0..720.0, "$on the list is ${list.width}px wide")
        assertTrue(
            abs((list.left - page.left) - (page.right - list.right)) < 4.0,
            "$on the list is not centred: ${list.left - page.left}px on the left, ${page.right - list.right}px on the right",
        )
        val bar = all("input.ban-search").single().getBoundingClientRect()
        assertTrue(
            abs(bar.left - list.left) < 4.0 && abs(bar.right - list.right) < 4.0,
            "$on the search bar is not over the list: ${bar.left}..${bar.right} against ${list.left}..${list.right}",
        )

        all(".ban-list .deck-line").first().click()
        settle(s.window)
        val sheet = all(".peek-sheet").singleOrNull()?.getBoundingClientRect() ?: error("$on tapping Mana Crypt opened no carousel")
        val tag = all(".peek-tags .tag").firstOrNull { it.textContent.orEmpty().startsWith("Banned in") } ?: error("$on the carousel does not say where Mana Crypt is banned")
        assertEquals("Banned in Commander and Legacy", tag.textContent)
        val t = tag.getBoundingClientRect()
        assertTrue(t.right <= sheet.right + 0.5 && t.bottom <= size.second + 0.5, "$on the ban runs off the carousel")
    }

    @Test
    fun searchReadsOnAFoldingPhoneOpenedOut() = runTest { searchReads(Tablet.UNFOLDED) }

    @Test
    fun searchReadsOnATabletHeldSideways() = runTest { searchReads(Tablet.LANDSCAPE) }
}
