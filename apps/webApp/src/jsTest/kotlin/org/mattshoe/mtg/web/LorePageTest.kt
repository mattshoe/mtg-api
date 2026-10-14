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
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The story of Phyrexia, in a real browser, through the real menu and shell.
 *
 * Matt: "i want a new guide section on phyrexian lore ... from thran to
 * modern times ... i want this particular guide to be tabbed, so the
 * user can tab through the various 'eras' ... this should read as
 * literature". Sibling of `LoreParityTest` on the phone.
 */
class LorePageTest {

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

    /** The menu, Guide, then the Phyrexia tile: the way a person gets there. */
    private suspend fun HTMLElement.openLore() {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        // Every story is inside Lore.
        all("button.guide-tile").first { it.querySelector(".guide-tile-title")?.textContent?.trim() == "Lore" }.click()
        settle()
        val tile = all("button.guide-tile").firstOrNull { it.querySelector(".guide-tile-title")?.textContent?.trim() == "Phyrexia" }
            ?: error("the Guide has no Phyrexia tile: ${all("button.guide-tile").map { it.textContent }}")
        tile.click()
        settle()
    }

    private fun HTMLElement.tabs() = all(".lore-tabs [role=tab]")
    private fun HTMLElement.story() = all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }

    @Test
    fun theTileOpensOnTheThranWithATabForEveryEra() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        assertEquals("#/guide/phyrexia", held.value.route.toHash())
        val tabs = frame.tabs().map { it.textContent.orEmpty().trim() }
        assertTrue(tabs.size >= 6, "the page has ${tabs.size} tabs: $tabs")
        assertEquals("The Thran", tabs.first(), "the first tab is not the Thran: $tabs")
        assertTrue(tabs.any { "Brothers" in it } && tabs.any { "Mirrodin" in it }, "eras are missing: $tabs")
        assertEquals("true", frame.tabs().first().getAttribute("aria-selected"), "the Thran is not the tab that is open")
        assertTrue("Yawgmoth" in frame.story(), "the Thran's story does not tell of Yawgmoth: ${frame.story().take(200)}")
        assertTrue(frame.all(".lore-story p").size >= 4, "the story is not paragraphs")
    }

    @Test
    fun tappingATabTellsThatErasStoryInstead() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        val brothers = frame.tabs().first { "Brothers" in it.textContent.orEmpty() }
        brothers.click()
        settle()
        assertEquals("#/guide/phyrexia/brothers", held.value.route.toHash(), "the tab is not in the address")
        assertEquals("true", frame.tabs().first { "Brothers" in it.textContent.orEmpty() }.getAttribute("aria-selected"))
        assertEquals("false", frame.tabs().first().getAttribute("aria-selected"), "the Thran still reads as the open tab")
        val story = frame.story()
        assertTrue("Mishra" in story, "the Brothers' War tab does not tell of Mishra: ${story.take(200)}")
        assertTrue("Glacian" !in story, "the Thran's story is still on the page under the Brothers' War")
        assertTrue(frame.all(".lore-title").single().textContent.orEmpty().isNotBlank(), "the era has no title")
    }

    @Test
    fun theTabsAreOneRowThatScrollsSidewaysOnAPhone() = runTest {
        val frame = mount(400)
        settle()
        frame.openLore()
        val tabs = frame.tabs()
        val tops = tabs.map { it.getBoundingClientRect().top }.toSet()
        assertEquals(1, tops.size, "the tabs wrap onto ${tops.size} rows, so they are a pile, not a tab bar")
        tabs.forEach {
            assertTrue(it.getBoundingClientRect().height <= 48.0, "${it.textContent} is ${it.getBoundingClientRect().height}px tall, so its label wraps")
        }
        val row = frame.all(".lore-tabs").single()
        assertTrue(row.scrollWidth > row.clientWidth, "every tab fits on a phone already, so this proves nothing about scrolling")
        assertTrue(window.getComputedStyle(row).overflowX in setOf("auto", "scroll"), "the tab row does not scroll, so the later eras are cut off")
        assertTrue(frame.scrollWidth <= 400, "the tabs push the page ${frame.scrollWidth}px wide on a 400px phone")
    }

    @Test
    fun itIsSetForReadingNotForScanning() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        val p = frame.all(".lore-story p").first()
        val look = window.getComputedStyle(p)
        val size = look.fontSize.removeSuffix("px").toDouble()
        val line = look.lineHeight.removeSuffix("px").toDoubleOrNull() ?: error("the story has no set line height: ${look.lineHeight}")
        assertTrue(size >= 15.0, "the story is ${size}px, too small to read a chapter in")
        assertTrue(line >= size * 1.5, "the story's lines are ${line}px apart at ${size}px, too tight for prose")
    }

    @Test
    fun readOnGoesToTheNextEraAndTheLastHasNowhereToGo() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        val next = frame.all("button.lore-next").singleOrNull() ?: error("the Thran has no way on to the next era")
        next.click()
        settle()
        assertEquals("#/guide/phyrexia/brothers", held.value.route.toHash(), "Read on did not go to the Brothers' War")
        frame.tabs().last().click()
        settle()
        assertTrue(frame.all("button.lore-next").isEmpty(), "the last era offers to read on into nothing")
    }

    @Test
    fun aQuoteSaysWhichCardItIsFrom() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        frame.tabs().first { "Invasion" in it.textContent.orEmpty() }.click()
        settle()
        val quote = frame.all(".lore-quote").singleOrNull() ?: error("the Invasion quotes nothing")
        val source = quote.all(".lore-quote-source").singleOrNull()?.textContent.orEmpty()
        assertTrue(source.isNotBlank(), "the quote does not say where it is from")
        assertTrue(quote.textContent.orEmpty().length > source.length + 10, "the quote is only its source")
    }

    @Test
    fun backFromAnEraIsTheTiles() = runTest {
        val frame = mount()
        settle()
        frame.openLore()
        frame.tabs()[2].click()
        settle()
        frame.all("button.guide-back").single().click()
        settle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertTrue(frame.all("button.guide-tile").isNotEmpty(), "back did not show the tiles")
    }
}
