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

    /** The menu, Guide, then the story's tile: the way a person gets there. */
    private suspend fun HTMLElement.openStory() {
        all("button.nav-burger").single().click()
        settle()
        all(".app-menu button.app-tab").first { it.textContent?.trim() == "Guide" }.click()
        settle()
        val tile = all("button.guide-tile").firstOrNull { it.textContent.orEmpty().contains("The story of Magic") }
            ?: error("the Guide has no story tile: ${all("button.guide-tile").map { it.textContent }}")
        tile.click()
        settle()
    }

    private suspend fun HTMLElement.openArticle(slug: String) {
        val button = all("button.story-article").firstOrNull { it.getAttribute("data-article") == slug }
            ?: error("the sections have no button for $slug")
        button.click()
        settle()
    }

    private suspend fun HTMLElement.openTab(label: String) {
        val tab = tabs().firstOrNull { it.textContent.orEmpty().trim() == label } ?: error("there is no $label tab: ${tabs().map { it.textContent }}")
        tab.click()
        settle()
    }

    private fun HTMLElement.tabs() = all(".lore-tabs [role=tab]")
    private fun HTMLElement.story() = all(".lore-story p").joinToString(" ") { it.textContent.orEmpty() }

    /** The first tab of an article whose narration links to [target]. */
    private fun tabLinking(article: String, target: String) =
        MagicStory.article(article)!!.tabs.firstOrNull { t -> t.story.any { p -> MagicStory.spans(p).any { it.target == target } } }
            ?: error("$article never links to $target")

    @Test
    fun theTileOpensOnTheTenSectionsEachWithAHookAndItsArticles() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        assertEquals("#/guide/lore", held.value.route.toHash())
        val sections = frame.all(".story-section")
        assertEquals(10, sections.size, "the story has ${sections.size} sections")
        assertEquals("Brothers' War", sections.first().all(".story-section-title").single().textContent?.trim())
        assertEquals("Omenpath Era", sections.last().all(".story-section-title").single().textContent?.trim())
        sections.forEach { s ->
            val name = s.all(".story-section-title").single().textContent
            assertTrue(s.all(".story-hook").single().textContent.orEmpty().isNotBlank(), "$name has no hook on screen")
            assertTrue(s.all("button.story-article").size >= 4, "$name lists ${s.all("button.story-article").size} articles")
        }
        assertEquals(48, frame.all("button.story-article").size, "not every article is on the page")
    }

    @Test
    fun anArticleOpensOnItsFirstTabWithTabsOfItsOwn() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("the-war")
        assertEquals("#/guide/lore/the-war", held.value.route.toHash(), "the article is not in the address")
        assertEquals("The War", frame.all(".story-article-title").single().textContent?.trim())
        assertTrue(frame.all("button.guide-back").isEmpty(), "the article offers a way back to the tiles, past its own sections")
        val tabs = frame.tabs()
        assertTrue(tabs.size >= 2, "The War has ${tabs.size} tabs")
        assertEquals("true", tabs.first().getAttribute("aria-selected"), "the first tab is not the open one")
        assertTrue(tabs.drop(1).all { it.getAttribute("aria-selected") == "false" }, "more than one tab reads as open")
        assertTrue(frame.all(".lore-story p").size >= 2, "the story is not paragraphs")
        assertTrue("Mishra" in frame.story(), "The War does not tell of Mishra: ${frame.story().take(200)}")
    }

    @Test
    fun tappingATabTellsThatPartOfTheStoryInstead() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("the-war")
        val before = frame.story()
        val second = frame.tabs()[1]
        val label = second.textContent.orEmpty().trim()
        second.click()
        settle()
        assertTrue(held.value.route.toHash().startsWith("#/guide/lore/the-war/"), "the tab is not in the address: ${held.value.route.toHash()}")
        assertEquals("true", frame.tabs().first { it.textContent.orEmpty().trim() == label }.getAttribute("aria-selected"))
        assertEquals("false", frame.tabs().first().getAttribute("aria-selected"), "the first tab still reads as open")
        assertTrue(frame.story() != before, "the second tab tells the same story as the first")
    }

    @Test
    fun aLinkInTheStoryOpensTheArticleItNamesAndIsUnderlined() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("rath-stronghold")
        val tab = tabLinking("rath-stronghold", "rath-made")
        frame.openTab(tab.tab)
        val link = frame.all(".lore-story a.lore-link").firstOrNull { it.getAttribute("data-target") == "rath-made" }
            ?: error("the ${tab.tab} tab shows no link to the other Rath")
        val look = window.getComputedStyle(link)
        assertTrue("underline" in look.textDecorationLine, "a link is told apart only by colour: ${look.textDecorationLine}")
        link.click()
        settle()
        assertEquals("#/guide/lore/rath-made", held.value.route.toHash(), "the link did not open the other Rath")
        assertEquals("Rath", frame.all(".story-article-title").single().textContent?.trim())
        assertEquals("Phyrexia's Origins", frame.all(".story-kicker").single().textContent?.trim(), "the other Rath does not say which section it is in")
    }

    @Test
    fun aNameOpensTheirProfileWhichListsEveryArticleTheyAreIn() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("the-war")
        frame.openTab(tabLinking("the-war", "who/urza").tab)
        frame.all(".lore-story a.lore-link").first { it.getAttribute("data-target") == "who/urza" }.click()
        settle()
        assertEquals("#/guide/lore/who/urza", held.value.route.toHash(), "Urza's name did not open his profile")
        val page = held.value.storyPage as? StoryPage.Profile ?: error("the app is not showing a profile: ${held.value.storyPage}")
        assertTrue(frame.all(".story-profile p").isNotEmpty(), "the profile says nothing")
        val listed = frame.all("button.story-appears").map { it.getAttribute("data-article") }
        assertEquals(MagicStory.appearances(page.person).map { it.slug }, listed, "the profile does not list every article Urza is in")
        assertTrue("sylex-blast" in listed, "Urza's profile leaves out the Sylex Blast")
        frame.all("button.story-appears").first { it.getAttribute("data-article") == "sylex-blast" }.click()
        settle()
        assertEquals("#/guide/lore/sylex-blast", held.value.route.toHash(), "a listed article does not open")
    }

    @Test
    fun theSourcesComeAtTheEndOutsideTheNarration() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("the-war")
        val sources = frame.all(".story-sources").singleOrNull() ?: error("The War says nowhere where it is told")
        assertEquals(MagicStory.article("the-war")!!.sources.size, sources.all(".story-source").size, "not every source is listed")
        val lastParagraph = frame.all(".lore-story p").last().getBoundingClientRect()
        assertTrue(sources.getBoundingClientRect().top >= lastParagraph.bottom, "the sources sit inside the narration, not after it")
        assertTrue(sources.all("p").isEmpty(), "the sources are set as narration")
    }

    @Test
    fun readOnGoesTabByTabThenOnToTheNextArticle() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("the-war")
        val war = MagicStory.article("the-war")!!
        war.tabs.drop(1).forEach { t ->
            frame.all("button.lore-next").single().click()
            settle()
            assertEquals("true", frame.tabs().first { it.textContent.orEmpty().trim() == t.tab }.getAttribute("aria-selected"), "Read on did not open ${t.tab}")
        }
        val next = frame.all("button.lore-next").singleOrNull() ?: error("the last tab of The War has no way on")
        assertTrue("Argoth" in next.textContent.orEmpty(), "the end of The War does not lead to Argoth: ${next.textContent}")
        next.click()
        settle()
        assertEquals("#/guide/lore/argoth", held.value.route.toHash())
    }

    @Test
    fun cardsAreSetApartFromTheNarration() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        val article = MagicStory.articles.first { a -> a.tabs.first().story.any { p -> MagicStory.spans(p).any { it.card } } }
        frame.openArticle(article.slug)
        val card = frame.all(".lore-story .lore-card").firstOrNull() ?: error("${article.title} names a card and none is on the page")
        assertEquals("italic", window.getComputedStyle(card).fontStyle, "a card's name is not set apart")
    }

    @Test
    fun itIsSetForReadingNotForScanning() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("koilos")
        val look = window.getComputedStyle(frame.all(".lore-story p").first())
        val size = look.fontSize.removeSuffix("px").toDouble()
        val line = look.lineHeight.removeSuffix("px").toDoubleOrNull() ?: error("the story has no set line height: ${look.lineHeight}")
        assertTrue(size >= 15.0, "the story is ${size}px, too small to read a chapter in")
        assertTrue(line >= size * 1.5, "the story's lines are ${line}px apart at ${size}px, too tight for prose")
        assertTrue(frame.scrollWidth <= 400, "the article pushes the page ${frame.scrollWidth}px wide on a 400px phone")
    }

    @Test
    fun upFromAnArticleIsTheSectionsAndBackFromThereIsTheTiles() = runTest {
        val frame = mount()
        settle()
        frame.openStory()
        frame.openArticle("argoth")
        frame.all("button.story-up").single().click()
        settle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertEquals(10, frame.all(".story-section").size, "up did not show the sections")
        frame.all("button.guide-back").single().click()
        settle()
        assertEquals("#/guide", held.value.route.toHash())
        assertTrue(frame.all("button.guide-tile").isNotEmpty(), "back did not show the tiles")
    }
}
