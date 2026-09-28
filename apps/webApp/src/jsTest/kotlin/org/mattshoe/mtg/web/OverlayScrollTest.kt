package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Overlay
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page behind an overlay does not move.
 *
 * On a phone a flick that reached the end of the drawer carried on
 * into the search results underneath, and a swipe that started on the
 * scrim scrolled the page without touching the drawer at all. Two
 * separate causes with one symptom: scroll chaining off the drawer,
 * and the scrim being a plain fixed box with nothing to absorb the
 * gesture.
 */
class OverlayScrollTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        document.body!!.classList.remove("overlay-open")
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun css(el: HTMLElement, prop: String) =
        window.getComputedStyle(el).getPropertyValue(prop).trim()

    private fun mount(start: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            var s by remember { mutableStateOf(start) }
            AppShell(s, { s = it }, {}, {}, {}, {}, {}, {})
        }
        return root
    }

    private fun withCardOpen() = AppState(card = CardDetail(name = "Sol Ring", owner = "matt"))
        .opening(Overlay.CARD)

    // ------------------------------------------------- the stylesheet

    @Test
    fun theDrawerDoesNotHandItsLeftoverScrollToThePage() = runTest {
        val root = mount(withCardOpen())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val drawer = root.querySelector("div.drawer") as? HTMLElement ?: error("no drawer")
        assertEquals("contain", css(drawer, "overscroll-behavior-y"))
        assertEquals("auto", css(drawer, "overflow-y"), "the drawer stopped scrolling at all")
    }

    @Test
    fun aSwipeOnTheScrimIsNotASwipeOnThePage() = runTest {
        val root = mount(withCardOpen())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val scrim = root.querySelector("div.drawer-scrim") as? HTMLElement ?: error("no scrim")
        assertEquals("none", css(scrim, "touch-action"))
    }

    // ------------------------------------------------------- the lock

    @Test
    fun anOpenOverlayHoldsThePageStill() = runTest {
        mount(withCardOpen())
        settle()
        assertTrue(
            document.body!!.classList.contains("overlay-open"),
            "the page behind the drawer is still free to scroll",
        )
        if (!Stylesheet.applied()) return@runTest
        assertEquals("hidden", css(document.body!!, "overflow"))
    }

    @Test
    fun andClosingItLetsThePageGoAgain() = runTest {
        val root = mount(withCardOpen())
        settle()
        assertTrue(document.body!!.classList.contains("overlay-open"))

        val close = root.querySelectorAll("button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? org.w3c.dom.HTMLButtonElement }
        }.first { it.textContent?.trim() == "Close" }
        close.click()
        settle()

        assertTrue(
            !document.body!!.classList.contains("overlay-open"),
            "the page is still locked with nothing open over it",
        )
    }

    @Test
    fun nothingOpenMeansNothingLocked() = runTest {
        mount(AppState())
        settle()
        assertTrue(!document.body!!.classList.contains("overlay-open"))
    }
}
