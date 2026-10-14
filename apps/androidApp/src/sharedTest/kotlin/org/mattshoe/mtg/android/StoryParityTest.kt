package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MagicStory
import org.mattshoe.mtg.core.StoryPage
import org.mattshoe.mtg.core.StoryTab
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The story of Magic, through the real shell and its real bottom bar.
 *
 * Matt: "Add Magic the Gathering lore content to the Guide. 10
 * sections ... Every article has its own tabs ... When an article
 * mentions something covered in another article, link to it ...
 * Characters who span sections ... get a short shared profile their
 * names link to, listing every article they appear in." Sibling of
 * `StoryPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class StoryParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun shell() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(AppState())
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openStory() {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-lore").assertExists("the Guide has no story tile")
        rule.onNodeWithTag("guide-tile-lore").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openArticle(slug: String) {
        rule.onNodeWithTag("story-article-$slug").assertExists("the sections have no button for $slug")
        rule.onNodeWithTag("story-article-$slug").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun tabs() = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)

    private fun selected(): List<Boolean> = tabs().fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Selected) ?: false
    }

    private fun story() = texts("lore-paragraph").joinToString(" ")

    private fun tab(named: String) {
        val i = texts("lore-tab").indexOf(named)
        assertTrue(i >= 0, "there is no $named tab: ${texts("lore-tab")}")
        tabs()[i].performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    /** The first tab of an article whose narration links to [target]. */
    private fun tabLinking(article: String, target: String): StoryTab =
        MagicStory.article(article)!!.tabs.firstOrNull { t -> t.story.any { p -> MagicStory.spans(p).any { it.target == target } } }
            ?: error("$article never links to $target")

    /** Where in the open tab a link to [target] is: its paragraph and the offset of its first letter. */
    private fun linkIn(tab: StoryTab, target: String): Pair<Int, Int> {
        tab.story.forEachIndexed { i, p ->
            var at = 0
            MagicStory.spans(p).forEach { s ->
                if (s.target == target) return i to at
                at += s.text.length
            }
        }
        error("${tab.tab} has no link to $target")
    }

    /** Taps the words of a link, where they are drawn. */
    private fun tapLink(tab: StoryTab, target: String) {
        val (paragraph, offset) = linkIn(tab, target)
        val node = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[paragraph]
        node.performScrollTo()
        rule.waitForIdle()
        val layout = assertNotNull(node.textLayout(), "the paragraph has no text layout")
        val span = layout.layoutInput.text.spanStyles.firstOrNull { offset in it.start until it.end }
        assertEquals(TextDecoration.Underline, span?.item?.textDecoration, "a link is told apart only by colour, or not at all")
        val box = layout.getBoundingBox(offset + 1)
        node.performTouchInput { click(box.center) }
        rule.waitForIdle()
    }

    @Test
    fun theTileOpensOnTheTenSectionsEachWithAHookAndItsArticles() {
        shell()
        openStory()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertEquals(10, rule.onAllNodes(hasTestTag("story-section"), useUnmergedTree = true).fetchSemanticsNodes().size, "the story is not ten sections")
        val hooks = texts("story-hook")
        assertEquals(10, hooks.size, "not every section has its hook on screen")
        assertTrue(hooks.all { it.isNotBlank() }, "a hook is blank")
        MagicStory.articles.forEach { rule.onNodeWithTag("story-article-${it.slug}").assertExists("${it.title} is not on the page") }
    }

    @Test
    fun anArticleOpensOnItsFirstTabWithTabsOfItsOwn() {
        shell()
        openStory()
        openArticle("the-war")
        assertEquals("#/guide/lore/the-war", held.value.route.toHash(), "the article is not in the address")
        assertEquals(listOf("The War"), texts("story-article-title"))
        rule.onNodeWithTag("guide-back").assertDoesNotExist()
        val names = texts("lore-tab")
        assertTrue(names.size >= 2, "The War has ${names.size} tabs")
        assertEquals(listOf(true) + List(names.size - 1) { false }, selected(), "the first tab is not the one open tab")
        assertTrue(texts("lore-paragraph").size >= 2, "the story is not paragraphs")
        assertTrue("Mishra" in story(), "The War does not tell of Mishra: ${story().take(200)}")
    }

    @Test
    fun tappingATabTellsThatPartOfTheStoryInstead() {
        shell()
        openStory()
        openArticle("the-war")
        val before = story()
        val second = texts("lore-tab")[1]
        tab(second)
        assertTrue(held.value.route.toHash().startsWith("#/guide/lore/the-war/"), "the tab is not in the address: ${held.value.route.toHash()}")
        assertEquals(texts("lore-tab").map { it == second }, selected(), "$second is not the one open tab")
        assertTrue(story() != before, "the second tab tells the same story as the first")
    }

    @Test
    fun aLinkInTheStoryOpensTheArticleItNamesAndIsUnderlined() {
        shell()
        openStory()
        openArticle("rath-stronghold")
        val t = tabLinking("rath-stronghold", "rath-made")
        tab(t.tab)
        tapLink(t, "rath-made")
        assertEquals("#/guide/lore/rath-made", held.value.route.toHash(), "the link did not open the other Rath")
        assertEquals(listOf("Rath"), texts("story-article-title"))
    }

    @Test
    fun aNameOpensTheirProfileWhichListsEveryArticleTheyAreIn() {
        shell()
        openStory()
        openArticle("the-war")
        val t = tabLinking("the-war", "who/urza")
        tab(t.tab)
        tapLink(t, "who/urza")
        assertEquals("#/guide/lore/who/urza", held.value.route.toHash(), "Urza's name did not open his profile")
        val page = held.value.storyPage as? StoryPage.Profile ?: error("the app is not showing a profile: ${held.value.storyPage}")
        assertTrue(texts("lore-paragraph").isNotEmpty(), "the profile says nothing")
        MagicStory.appearances(page.person).forEach {
            rule.onNodeWithTag("story-appears-${it.slug}").assertExists("Urza's profile does not list ${it.title}")
        }
        rule.onNodeWithTag("story-appears-sylex-blast").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore/sylex-blast", held.value.route.toHash(), "a listed article does not open")
    }

    @Test
    fun theSourcesComeAtTheEndOutsideTheNarration() {
        shell()
        openStory()
        openArticle("the-war")
        val sources = rule.onAllNodes(hasTestTag("story-source"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(MagicStory.article("the-war")!!.sources.size, sources.size, "not every source is listed")
        val paragraphs = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)
        val last = paragraphs[paragraphs.fetchSemanticsNodes().size - 1].getUnclippedBoundsInRoot()
        val block = rule.onNodeWithTag("story-sources", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(block.top >= last.bottom, "the sources sit inside the narration, not after it")
    }

    @Test
    fun readOnGoesTabByTabThenOnToTheNextArticle() {
        shell()
        openStory()
        openArticle("the-war")
        val war = MagicStory.article("the-war")!!
        war.tabs.drop(1).forEach { t ->
            rule.onNodeWithTag("lore-next").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(texts("lore-tab").map { it == t.tab }, selected(), "Read on did not open ${t.tab}")
        }
        val label = texts("lore-next").singleOrNull() ?: error("the last tab of The War has no way on")
        assertTrue("Argoth" in label, "the end of The War does not lead to Argoth: $label")
        rule.onNodeWithTag("lore-next").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore/argoth", held.value.route.toHash())
    }

    @Test
    fun cardsAreSetApartFromTheNarration() {
        shell()
        openStory()
        val article = MagicStory.articles.first { a -> a.tabs.first().story.any { p -> MagicStory.spans(p).any { it.card } } }
        openArticle(article.slug)
        val i = article.tabs.first().story.indexOfFirst { p -> MagicStory.spans(p).any { it.card } }
        val node = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[i]
        val layout = assertNotNull(node.textLayout(), "the paragraph has no text layout")
        val name = MagicStory.spans(article.tabs.first().story[i]).first { it.card }.text
        val at = layout.layoutInput.text.text.indexOf(name)
        assertTrue(at >= 0, "$name is not in the paragraph on screen")
        val style = layout.layoutInput.text.spanStyles.firstOrNull { at in it.start until it.end }?.item
        assertEquals(FontStyle.Italic, style?.fontStyle, "$name is not set apart from the narration")
    }

    @Test
    fun itIsSetForReadingNotForScanning() {
        shell()
        openStory()
        openArticle("koilos")
        val style = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[0].textLayout()?.layoutInput?.style
        assertNotNull(style, "the story has no text layout")
        assertTrue(style.fontSize.value >= 15f, "the story is ${style.fontSize}, too small to read a chapter in")
        assertTrue(
            style.lineHeight.isSp && style.lineHeight.value >= style.fontSize.value * 1.5f,
            "the story's lines are ${style.lineHeight} apart at ${style.fontSize}, too tight for prose",
        )
    }

    @Test
    fun upFromAnArticleIsTheSectionsAndBackFromThereIsTheTiles() {
        shell()
        openStory()
        openArticle("argoth")
        rule.onNodeWithTag("story-up").performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        assertEquals(10, rule.onAllNodes(hasTestTag("story-section"), useUnmergedTree = true).fetchSemanticsNodes().size, "up did not show the sections")
        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("#/guide", held.value.route.toHash())
        rule.onNodeWithTag("guide-tile-lore").assertExists("back did not show the tiles")
    }
}
