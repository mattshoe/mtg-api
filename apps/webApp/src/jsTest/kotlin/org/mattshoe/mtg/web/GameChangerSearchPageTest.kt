package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Overlay
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
 * Searching the Game Changers by name, in a real browser, through the
 * real menu and shell.
 *
 * Matt: "add a name search to the game changers page". Sibling of
 * `GameChangerSearchParityTest` on the phone.
 */
class GameChangerSearchPageTest {

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

    /** The menu, its Guide, then the Game Changers tile, the way a person gets there. */
    private suspend fun HTMLElement.openGameChangers() {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        all("button.guide-tile").first { it.querySelector(".guide-tile-title")?.textContent == "Game Changers" }.click()
        settle()
    }

    private suspend fun HTMLElement.search(words: String) {
        val box = all("input.gc-search").singleOrNull() as? HTMLInputElement
            ?: error("the Game Changers page has no search bar")
        box.value = words
        box.dispatchEvent(Event("input"))
        settle()
    }

    private fun HTMLElement.names() = all(".gc-list .deck-line .t-name").map { it.textContent.orEmpty() }

    @Test
    fun aNameShowsOnlyTheCardsWithItUnderTheirOwnHeads() = runTest {
        val page = mount()
        page.openGameChangers()
        page.search("tutor")
        assertEquals(
            listOf("Enlightened Tutor", "Mystical Tutor", "Vampiric Tutor", "Worldly Tutor", "Demonic Tutor"),
            page.names(),
            "searching tutor did not leave the tutors",
        )
        assertEquals(listOf("Instants", "Sorceries"), page.all(".gc-list .panel-head h2").map { it.textContent.orEmpty() })
        assertEquals("5 of 53 cards", page.all(".gc-search-count").singleOrNull()?.textContent?.trim(), "nothing says how many were found")

        val bar = page.all("input.gc-search").single().getBoundingClientRect()
        val first = page.all(".gc-list .panel").first().getBoundingClientRect()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the list: ${bar.bottom} vs ${first.top}")
        assertTrue(bar.right <= page.getBoundingClientRect().right + 0.5, "the search bar runs off a 400px screen")
    }

    @Test
    fun tappingAFoundCardPopsTheCarouselOverWhatWasFound() = runTest {
        val page = mount()
        page.openGameChangers()
        page.search("tutor")
        page.all(".gc-list .deck-line").first { it.textContent.orEmpty().contains("Worldly Tutor") }.click()
        settle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Worldly Tutor opened no carousel")
        assertEquals("Worldly Tutor", page.all(".peek-name").single().textContent)
        assertEquals("4 of 5", page.all(".peek-place").single().textContent, "the carousel is over every game changer, not the ones found")
    }

    @Test
    fun aNameNoGameChangerHasSaysSoAndClearingItBringsBackEveryCard() = runTest {
        val page = mount()
        page.openGameChangers()
        page.search("sol ring")
        assertEquals("No game changer is called \"sol ring\".", page.all(".gc-search-none").singleOrNull()?.textContent?.trim())
        assertTrue(page.names().isEmpty(), "rows are left under a search that found nothing: ${page.names()}")
        page.search("")
        assertEquals(53, page.names().size, "clearing the search did not bring back every game changer")
        assertTrue(page.all(".gc-search-count").isEmpty(), "a count is left over a list nobody searched")
    }
}
