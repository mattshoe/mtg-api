package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the Guide's articles, in a real browser, through the real
 * menu and shell.
 *
 * Matt: "i want to be able to search guide articles on the Guide page
 * ... searching should show any articles which contain the keywords on
 * the search bar". Sibling of `GuideSearchParityTest` on the phone.
 */
class GuideSearchPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    /** A phone's width, where Matt reads it. */
    private fun mount(width: Int = 400): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
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

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** The menu, then Guide: the way a person gets there. */
    private suspend fun HTMLElement.openGuide() {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
    }

    private suspend fun HTMLElement.search(words: String) {
        val box = all("input.guide-search").singleOrNull() as? HTMLInputElement
            ?: error("the Guide has no search bar: ${all(".guide-tile-title").map { it.textContent }}")
        box.value = words
        box.dispatchEvent(Event("input"))
        settle()
    }

    private fun HTMLElement.hits() = all("button.guide-hit .guide-hit-title").map { it.textContent.orEmpty().trim() }

    @Test
    fun searchingShowsEveryArticleWithTheWordInsteadOfTheTiles() = runTest {
        val page = mount()
        page.openGuide()
        page.search("Sylex")
        val hits = page.hits()
        assertTrue("Sylex Blast" in hits, "searching Sylex did not find Sylex Blast: $hits")
        assertTrue(hits.size > 3, "searching Sylex found too little: $hits")
        page.all("button.guide-hit").forEach { hit ->
            val snippet = hit.all(".guide-hit-snippet").singleOrNull()?.textContent.orEmpty()
            assertTrue(snippet.contains("sylex", ignoreCase = true), "a hit does not show where the word is: \"$snippet\"")
            assertTrue(hit.all(".guide-hit-part").singleOrNull()?.textContent.orEmpty().isNotBlank(), "a hit does not say where it is from")
        }
        assertEquals("${hits.size} articles", page.all(".guide-search-count").singleOrNull()?.textContent?.trim())
        assertTrue(page.all("button.guide-tile").isEmpty(), "the tiles are still there under the hits")

        val bar = page.all("input.guide-search").single().getBoundingClientRect()
        val first = page.all("button.guide-hit").first().getBoundingClientRect()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the hits: ${bar.bottom} vs ${first.top}")
    }

    @Test
    fun everyWordHasToBeThere() = runTest {
        val page = mount()
        page.openGuide()
        page.search("Yawgmoth")
        val one = page.hits()
        page.search("Yawgmoth Sylex")
        val both = page.hits()
        assertTrue(both.isNotEmpty() && both.size < one.size, "a second word did not narrow it: ${one.size} then ${both.size}")
    }

    @Test
    fun tappingAHitOpensTheArticleAndBackFindsTheSearchStillThere() = runTest {
        val page = mount()
        page.openGuide()
        page.search("Sylex")
        page.all("button.guide-hit").first { it.all(".guide-hit-title").single().textContent?.trim() == "Sylex Blast" }.click()
        settle()
        assertEquals("#/guide/brothers-war/sylex-blast", held.value.route.toHash())
        val open = page.all(".lore-tabs [role=tab]").filter { it.getAttribute("aria-selected") == "true" }.map { it.textContent?.trim() }
        assertEquals(listOf("Sylex Blast"), open, "the hit did not open its article")

        page.all("button.guide-back").single().click()
        settle()
        assertEquals("Sylex", (page.all("input.guide-search").single() as HTMLInputElement).value)
        assertTrue("Sylex Blast" in page.hits(), "back to the Guide lost the hits")
    }

    @Test
    fun aPhyrexiaEraOpensAtItsOwnTab() = runTest {
        val page = mount()
        page.openGuide()
        page.search("Glacian")
        page.all("button.guide-hit").first { it.all(".guide-hit-part").single().textContent?.trim() == "Phyrexia" }.click()
        settle()
        assertTrue(held.value.route.toHash().startsWith("#/guide/phyrexia/"), "the era did not open: ${held.value.route.toHash()}")
    }

    @Test
    fun aWordNoArticleHasSaysSoAndClearingItBringsTheTilesBack() = runTest {
        val page = mount()
        page.openGuide()
        page.search("xyzzyplugh")
        assertEquals("No article mentions \"xyzzyplugh\".", page.all(".guide-search-none").singleOrNull()?.textContent?.trim())
        assertTrue(page.hits().isEmpty())
        page.search("")
        assertTrue(page.all("button.guide-tile").size > 4, "clearing the search did not bring the tiles back")
    }
}
