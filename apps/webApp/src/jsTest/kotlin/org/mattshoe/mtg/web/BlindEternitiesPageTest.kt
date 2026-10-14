package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MagicStory
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
 * The Blind Eternities section, in a real browser, through the real menu and shell.
 *
 * Matt: "i want a guide section about the blind eternities, and
 * generally the "topology" of the mtg multiverse/universe/whatever".
 * Sibling of `BlindEternitiesParityTest` on the phone.
 */
class BlindEternitiesPageTest {

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

    private fun mount(): HTMLElement {
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
        return frame
    }

    private suspend fun settle(w: org.w3c.dom.Window = window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.tabs() = all(".lore-tabs [role=tab]").map { it.textContent.orEmpty().trim() }
    private fun HTMLElement.story() = all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }

    /** The menu, Guide, then the tile: the way a person gets there. */
    private suspend fun HTMLElement.openTheBlindEternities() {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        val tile = all("button.guide-tile").firstOrNull { it.all(".guide-tile-title").singleOrNull()?.textContent?.trim() == "The Blind Eternities" }
            ?: error("the Guide has no Blind Eternities tile: ${all(".guide-tile-title").map { it.textContent }}")
        tile.click()
        settle()
    }

    @Test
    fun theGuideHasABlindEternitiesTileThatOpensOnItsArticlesAsTabs() = runTest {
        val frame = mount()
        settle()
        frame.openTheBlindEternities()
        assertEquals("#/guide/blind-eternities", held.value.route.toHash())
        assertEquals(
            listOf("The Multiverse", "The Blind Eternities", "Crossing the Void", "Planes Made and Unmade"),
            frame.tabs(),
            "the tabs are not the section's articles",
        )
        assertTrue("plane" in frame.story(), "The Multiverse does not tell of planes: ${frame.story().take(200)}")
    }

    @Test
    fun aLinkFromTheVoidOpensTheStoryItTouches() = runTest {
        val frame = mount()
        settle()
        frame.openTheBlindEternities()
        frame.all(".lore-tabs [role=tab]").first { it.textContent.orEmpty().trim() == "Crossing the Void" }.click()
        settle()
        assertEquals("#/guide/blind-eternities/crossing-the-void", held.value.route.toHash(), "the tab is not in the address")
        val link = frame.all(".lore-story a.lore-link").firstOrNull { it.getAttribute("data-target") == "the-omenpaths" }
            ?: error("Crossing the Void does not link to the Omenpaths")
        link.click()
        settle()
        assertEquals("#/guide/omenpaths/the-omenpaths", held.value.route.toHash(), "the link did not open the Omenpaths in their own section")
    }

    private suspend fun readsOn(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val h = remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/blind-eternities/the-blind-eternities"))) }
            AppShell(state = h.value, onState = { h.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val on = "on a ${size.first} by ${size.second} screen"
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
        val page = all(".guide").single().getBoundingClientRect()
        val box = all(".lore-story").singleOrNull()?.getBoundingClientRect() ?: error("$on there is no Blind Eternities story")
        assertTrue(box.width in 320.0..720.0, "$on the story is ${box.width}px wide")
        assertTrue(abs((box.left - page.left) - (page.right - box.right)) < 4.0, "$on the story is not centred")
        val tabs = all(".lore-tabs [role=tab]").map { it.getBoundingClientRect() }
        assertEquals(4, tabs.size, "$on the section does not have its four articles as tabs")
        assertTrue(tabs.all { it.left >= 0 && it.right <= size.first }, "$on a tab runs off the side")
    }

    @Test
    fun itReadsOnAFoldingPhoneOpenedOut() = runTest { readsOn(Tablet.UNFOLDED) }

    @Test
    fun itReadsOnATabletHeldSideways() = runTest { readsOn(Tablet.LANDSCAPE) }
}
