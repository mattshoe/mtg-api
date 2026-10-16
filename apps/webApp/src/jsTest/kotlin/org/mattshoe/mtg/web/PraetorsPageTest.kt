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
 * Matt: "Please add the details of the 5 phyrexian praetors and their
 * respective colors. in the elesh norn section, add her work with
 * Atraxa"
 *
 * In a real browser, through the real menu: the Guide, Lore, the New
 * Phyrexia tile, then its Praetors tab, on a phone and on a tablet.
 * Sibling of `PraetorsParityTest` on the phone.
 */
class PraetorsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val five = listOf("Five Suns", "Elesh Norn", "Jin-Gitaxias", "Sheoldred", "Urabrask", "Vorinclex", "Mother of Machines")

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

    private fun thePraetorsAreTold(on: String, page: HTMLElement) {
        val parts = page.all(".story-part-title").map { it.textContent.orEmpty().trim() }
        val at = parts.indexOf("Five Suns")
        assertEquals(five, parts.drop(at).take(7), "$on the five praetors are not parts of their own after Five Suns: $parts")
        val story = page.all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }
        listOf("Machine Orthodoxy", "Progress Engine", "Steel Thanes", "Quiet Furnace", "Vicious Swarm").forEach {
            assertTrue(it in story, "$on the story never names the $it")
        }
        assertTrue("Atraxa, Grand Unifier" in story, "$on Elesh Norn's part does not tell of Atraxa, Grand Unifier")
    }

    @Test
    fun thePraetorsArticleTellsEachPraetorOnAPhone() = runTest {
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
        frame.all("button.guide-tile").first { it.all(".guide-tile-title").singleOrNull()?.textContent?.trim() == "New Phyrexia" }.click()
        settle()
        val tabs = frame.all(".lore-tabs [role=tab]")
        val at = tabs.indexOfFirst { it.textContent.orEmpty().trim() == "Praetors" }
        assertTrue(at >= 0, "New Phyrexia has no Praetors tab: ${tabs.map { it.textContent }}")
        tabs[at].click()
        settle()
        assertEquals("#/guide/new-phyrexia/praetors", held.value.route.toHash(), "the tab is not in the address")
        thePraetorsAreTold("on a phone", frame)
    }

    private suspend fun onATablet(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val state = remember { mutableStateOf(AppState().navigate(Route.parse("#/guide/new-phyrexia/praetors"))) }
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
        thePraetorsAreTold(on, page)
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
