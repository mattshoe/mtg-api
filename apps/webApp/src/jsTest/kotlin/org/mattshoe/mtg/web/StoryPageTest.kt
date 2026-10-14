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
import org.mattshoe.mtg.core.StoryPage
import org.mattshoe.mtg.core.storyPage
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The story of Magic, in a real browser, through the real menu and shell.
 *
 * Matt, after the first cut: "you put all of it in one single article.
 * each of those sections should be their own article, with its own
 * tabs, just like the phyrexian one". So a section is a tile and its
 * articles are its tabs.
 *
 * Matt: "Add Magic the Gathering lore content to the Guide. 10
 * sections ... Every article has its own tabs ... When an article
 * mentions something covered in another article, link to it ...
 * Characters who span sections ... get a short shared profile their
 * names link to, listing every article they appear in." Sibling of
 * `StoryParityTest` on the phone.
 */
class StoryPageTest {

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

    /** The menu, Guide, then a section's own tile: the way a person gets there. */
    private suspend fun HTMLElement.openSection(title: String) {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        // Every story is inside Lore.
        all("button.guide-tile").first { it.querySelector(".guide-tile-title")?.textContent?.trim() == "Lore" }.click()
        settle()
        val tile = all("button.guide-tile").firstOrNull { it.all(".guide-tile-title").singleOrNull()?.textContent?.trim() == title }
            ?: error("the Guide has no tile for $title: ${all(".guide-tile-title").map { it.textContent }}")
        tile.click()
        settle()
    }

    private suspend fun HTMLElement.openTab(label: String) {
        val tab = tabs().firstOrNull { it.textContent.orEmpty().trim() == label } ?: error("there is no $label tab: ${tabs().map { it.textContent }}")
        tab.click()
        settle()
    }

    private fun HTMLElement.tabs() = all(".lore-tabs [role=tab]")
    private fun HTMLElement.open() = tabs().filter { it.getAttribute("aria-selected") == "true" }.map { it.textContent.orEmpty().trim() }
    private fun HTMLElement.story() = all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }

    @Test
    fun everySectionIsATileOfItsOwnAndThereIsNoOneArticleOfThemAll() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        val titles = run {
            frame.all("button.guide-back").single().click()
            settle()
            frame.all(".guide-tile-title").map { it.textContent.orEmpty().trim() }
        }
        assertTrue("The story of Magic" !in titles, "the ten sections are still one tile: $titles")
        MagicStory.sections.forEach { s -> assertTrue(s.title in titles, "${s.title} is not a tile of its own: $titles") }
        val war = frame.all("button.guide-tile").first { it.textContent.orEmpty().contains("Brothers' War") }
        assertTrue(MagicStory.sections.first().hook in war.textContent.orEmpty(), "the tile does not carry the section's hook")
    }

    @Test
    fun aSectionOpensLikePhyrexiaWithItsArticlesAsItsTabs() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        val war = MagicStory.sections.first()
        assertEquals("#/guide/${war.slug}", held.value.route.toHash())
        assertEquals(war.articles.map { it.title }, frame.tabs().map { it.textContent.orEmpty().trim() }, "the tabs are not the section's articles")
        assertEquals(listOf(war.articles.first().title), frame.open(), "the first tab is not the one open")
        assertEquals(1, frame.all("button.guide-back").size, "there is no way back to the Guide, as Phyrexia has")
        assertTrue(frame.all(".story-section").isEmpty(), "the old page of all ten sections is still drawn")
        assertTrue(frame.all(".lore-story p").size >= 2, "the story is not paragraphs")
    }

    @Test
    fun tappingATabTellsThatArticleInstead() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        val before = frame.story()
        frame.openTab("The War")
        assertEquals("#/guide/brothers-war/the-war", held.value.route.toHash(), "the tab is not in the address")
        assertEquals(listOf("The War"), frame.open(), "The War does not read as the open tab")
        assertTrue(frame.story() != before, "the second tab tells the same story as the first")
        assertTrue("Mishra" in frame.story(), "The War does not tell of Mishra: ${frame.story().take(200)}")
    }

    @Test
    fun aTabTellsEveryPartOfItsArticleUnderItsOwnHeading() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        frame.openTab("Koilos")
        val koilos = MagicStory.article("koilos")!!
        assertEquals(koilos.tabs.map { it.tab }, frame.all(".story-part-title").map { it.textContent.orEmpty().trim() }, "the tab does not tell every part of Koilos")
        assertEquals(koilos.tabs.sumOf { it.story.size }, frame.all(".lore-story p").size, "a paragraph of Koilos is missing")
    }

    @Test
    fun aLinkInTheStoryOpensTheArticleInItsOwnSectionAndIsUnderlined() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Weatherlight Saga")
        frame.openTab(MagicStory.article("rath-stronghold")!!.title)
        val link = frame.all(".lore-story a.lore-link").firstOrNull { it.getAttribute("data-target") == "rath-made" }
            ?: error("Rath in the Weatherlight Saga shows no link to the other Rath")
        val look = window.getComputedStyle(link)
        assertTrue("underline" in look.textDecorationLine, "a link is told apart only by colour: ${look.textDecorationLine}")
        assertEquals("#/guide/phyrexia-origins/rath-made", link.getAttribute("href")?.substringAfter('#')?.let { "#$it" }, "the link does not name the article's own section")
        link.click()
        settle()
        assertEquals("#/guide/phyrexia-origins/rath-made", held.value.route.toHash(), "the link did not open the other Rath")
        assertEquals(MagicStory.sections.first { it.slug == "phyrexia-origins" }.articles.map { it.title }, frame.tabs().map { it.textContent.orEmpty().trim() }, "the tabs are not Phyrexia's Origins")
        assertEquals(listOf("Rath"), frame.open())
    }

    @Test
    fun aNameOpensTheirProfileWhichListsEveryArticleTheyAreIn() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        frame.openTab("The War")
        frame.all(".lore-story a.lore-link").first { it.getAttribute("data-target") == "who/urza" }.click()
        settle()
        assertEquals("#/guide/brothers-war/who/urza", held.value.route.toHash(), "Urza's name did not open his profile")
        val page = held.value.storyPage as? StoryPage.Profile ?: error("the app is not showing a profile: ${held.value.storyPage}")
        assertTrue(frame.all(".story-profile p").isNotEmpty(), "the profile says nothing")
        val listed = frame.all("button.story-appears").map { it.getAttribute("data-article") }
        assertEquals(MagicStory.appearances(page.person).map { it.slug }, listed, "the profile does not list every article Urza is in")
        frame.all("button.story-appears").first { it.getAttribute("data-article") == "sylex-blast" }.click()
        settle()
        assertEquals("#/guide/brothers-war/sylex-blast", held.value.route.toHash(), "a listed article does not open")
    }

    @Test
    fun upFromAProfileIsTheSectionItWasOpenedFrom() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        frame.openTab("The War")
        frame.all(".lore-story a.lore-link").first { it.getAttribute("data-target") == "who/urza" }.click()
        settle()
        val up = frame.all("button.story-up").singleOrNull() ?: error("a profile has no way back up")
        assertTrue("Brothers' War" in up.textContent.orEmpty(), "up does not name the section: ${up.textContent}")
        up.click()
        settle()
        assertEquals("#/guide/brothers-war", held.value.route.toHash())
        assertEquals(listOf("The Thran"), frame.open(), "up did not show the section")
    }

    @Test
    fun theSourcesComeAtTheEndOutsideTheNarration() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        frame.openTab("The War")
        val sources = frame.all(".story-sources").singleOrNull() ?: error("The War says nowhere where it is told")
        assertEquals(MagicStory.article("the-war")!!.sources.size, sources.all(".story-source").size, "not every source is listed")
        val lastParagraph = frame.all(".lore-story p").last().getBoundingClientRect()
        assertTrue(sources.getBoundingClientRect().top >= lastParagraph.bottom, "the sources sit inside the narration, not after it")
        assertTrue(sources.all("p").isEmpty(), "the sources are set as narration")
    }

    @Test
    fun readOnGoesTabByTabThenOnToTheNextSection() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        val war = MagicStory.sections.first()
        war.articles.drop(1).forEach { a ->
            frame.all("button.lore-next").single().click()
            settle()
            assertEquals(listOf(a.title), frame.open(), "Read on did not open ${a.title}")
        }
        val next = frame.all("button.lore-next").singleOrNull() ?: error("the last tab of the Brothers' War has no way on")
        assertTrue("Phyrexia's Origins" in next.textContent.orEmpty(), "the end of the Brothers' War does not lead to the next section: ${next.textContent}")
        next.click()
        settle()
        assertEquals("#/guide/phyrexia-origins/halcyon", held.value.route.toHash())
    }

    @Test
    fun cardsAreSetApartFromTheNarration() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        val card = frame.all(".lore-story .lore-card").firstOrNull() ?: error("The Thran names a card and none is on the page")
        assertEquals("italic", window.getComputedStyle(card).fontStyle, "a card's name is not set apart")
    }

    @Test
    fun itIsSetForReadingNotForScanning() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Brothers' War")
        frame.openTab("Koilos")
        val look = window.getComputedStyle(frame.all(".lore-story p").first())
        val size = look.fontSize.removeSuffix("px").toDouble()
        val line = look.lineHeight.removeSuffix("px").toDoubleOrNull() ?: error("the story has no set line height: ${look.lineHeight}")
        assertTrue(size >= 15.0, "the story is ${size}px, too small to read a chapter in")
        assertTrue(line >= size * 1.5, "the story's lines are ${line}px apart at ${size}px, too tight for prose")
        assertTrue(frame.scrollWidth <= 400, "the article pushes the page ${frame.scrollWidth}px wide on a 400px phone")
    }

    @Test
    fun backFromASectionIsTheTiles() = runTest {
        val frame = mount()
        settle()
        frame.openSection("Ravnica")
        frame.all("button.guide-back").single().click()
        settle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertTrue(frame.all("button.guide-tile").isNotEmpty(), "back did not show the tiles")
    }
}
