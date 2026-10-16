package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "please add the background lore for the Wilds of eldraine into
 * the Omenppaths lore section."
 *
 * In a real browser, through the real menu: the Guide, Lore, the
 * Omenpath Era tile, then its Wilds of Eldraine tab, on a phone and on
 * a tablet. Sibling of `WildsOfEldraineParityTest` on the phone.
 */
class WildsOfEldrainePageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val parts = listOf("The Wicked Slumber", "Talion's Bargain", "Agatha's Cauldron", "The Frozen Heart", "The Ardent Queen", "Broken Oaths")

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
        querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }

    private fun HTMLElement.tabs() = all(".lore-tabs [role=tab]").map { it.textContent.orEmpty().trim() }

    private fun theArticleIsTold(on: String, page: HTMLElement) {
        val open = page.all(".lore-tabs [role=tab]").filter { it.getAttribute("aria-selected") == "true" }.map { it.textContent.orEmpty().trim() }
        assertEquals(listOf("Wilds of Eldraine"), open, "$on Wilds of Eldraine is not the one open tab")
        assertEquals(parts, page.all(".story-part-title").map { it.textContent.orEmpty().trim() }, "$on the article does not tell its parts")
        val story = page.all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }
        assertTrue("Wicked Slumber" in story && "Kellan" in story, "$on the story does not tell of the Wicked Slumber and Kellan: ${story.take(200)}")
    }

    @Test
    fun theOmenpathEraHasAWildsOfEldraineTabAfterTheOmenpaths() = runTest {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "400px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = remember { mutableStateOf(AppState()) }
            AppNav(held.value, onState = { held.value = it })
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        settle()
        frame.all("button.nav-burger").single().click()
        settle()
        frame.all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        frame.all("button.guide-tile").first { it.querySelector(".guide-tile-title")?.textContent?.trim() == "Lore" }.click()
        settle()
        frame.all("button.guide-tile").first { it.all(".guide-tile-title").singleOrNull()?.textContent?.trim() == "Omenpath Era" }.click()
        settle()
        val tabs = frame.tabs()
        val at = tabs.indexOf("Wilds of Eldraine")
        assertTrue(at > 0, "the Omenpath Era has no Wilds of Eldraine tab: $tabs")
        assertEquals("The Omenpaths", tabs[at - 1], "Wilds of Eldraine does not follow The Omenpaths: $tabs")
        assertEquals("Thunder Junction", tabs[at + 1], "Wilds of Eldraine does not come before Thunder Junction: $tabs")
        frame.all(".lore-tabs [role=tab]")[at].click()
        settle()
        assertEquals("#/guide/omenpaths/wilds-of-eldraine", held.value.route.toHash(), "the tab is not in the address")
        theArticleIsTold("on a phone", frame)
    }

    private suspend fun onATablet(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val state = remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/omenpaths/wilds-of-eldraine"))) }
            AppShell(
                state = state.value,
                onState = { state.value = it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        settle(s.window)
        val on = "on a ${size.first} by ${size.second} screen"
        val doc = s.frame.contentDocument!!
        val page = s.body
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        theArticleIsTold(on, page)
        val box = page.all(".lore-story").single().getBoundingClientRect()
        val guide = page.all(".guide").single().getBoundingClientRect()
        assertTrue(box.width in 320.0..720.0, "$on the story is ${box.width}px wide")
        assertTrue(abs((box.left - guide.left) - (guide.right - box.right)) < 4.0, "$on the story is not centred")
    }

    @Test
    fun itReadsOnAFoldingPhoneOpenedOut() = runTest { onATablet(Tablet.UNFOLDED) }

    @Test
    fun itReadsOnATabletHeldSideways() = runTest { onATablet(Tablet.LANDSCAPE) }
}
