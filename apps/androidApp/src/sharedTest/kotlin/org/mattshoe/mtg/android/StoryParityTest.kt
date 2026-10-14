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
import org.mattshoe.mtg.core.storyPage
import org.mattshoe.mtg.core.StoryArticle
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The story of Magic, through the real shell and its real bottom bar.
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
 * `StoryPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class StoryParityTest {

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

    /** The bottom bar's Guide, then a section's own tile: the way a person gets there. */
    private fun openSection(slug: String) {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-$slug").assertExists("the Guide has no tile for $slug")
        rule.onNodeWithTag("guide-tile-$slug").performScrollTo().performClick()
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

    /** Where in an open article a link to [target] is: its paragraph, counted across every part, and the offset of its first letter. */
    private fun linkIn(article: StoryArticle, target: String): Pair<Int, Int> {
        article.tabs.flatMap { it.story }.forEachIndexed { i, p ->
            var at = 0
            MagicStory.spans(p).forEach { s ->
                if (s.target == target) return i to at
                at += s.text.length
            }
        }
        error("${article.title} has no link to $target")
    }

    /** Taps the words of a link, where they are drawn. */
    private fun tapLink(article: StoryArticle, target: String) {
        val (paragraph, offset) = linkIn(article, target)
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
    fun everySectionIsATileOfItsOwnAndThereIsNoOneArticleOfThemAll() {
        shell()
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-lore").assertDoesNotExist()
        MagicStory.sections.forEach { rule.onNodeWithTag("guide-tile-${it.slug}").assertExists("${it.title} is not a tile of its own") }
    }

    @Test
    fun aSectionOpensLikePhyrexiaWithItsArticlesAsItsTabs() {
        shell()
        val war = MagicStory.sections.first()
        openSection(war.slug)
        assertEquals("#/guide/${war.slug}", held.value.route.toHash())
        assertEquals(war.articles.map { it.title }, texts("lore-tab"), "the tabs are not the section's articles")
        assertEquals(listOf(true) + List(war.articles.size - 1) { false }, selected(), "the first tab is not the one open tab")
        rule.onNodeWithTag("guide-back").assertExists("there is no way back to the Guide, as Phyrexia has")
        rule.onNodeWithTag("story-section").assertDoesNotExist()
        assertTrue(texts("lore-paragraph").size >= 2, "the story is not paragraphs")
    }

    @Test
    fun tappingATabTellsThatArticleInstead() {
        shell()
        openSection("brothers-war")
        val before = story()
        tab("The War")
        assertEquals("#/guide/brothers-war/the-war", held.value.route.toHash(), "the tab is not in the address")
        assertEquals(texts("lore-tab").map { it == "The War" }, selected(), "The War is not the one open tab")
        assertTrue(story() != before, "the second tab tells the same story as the first")
        assertTrue("Mishra" in story(), "The War does not tell of Mishra: ${story().take(200)}")
    }

    @Test
    fun aTabTellsEveryPartOfItsArticleUnderItsOwnHeading() {
        shell()
        openSection("brothers-war")
        tab("Koilos")
        val koilos = MagicStory.article("koilos")!!
        assertEquals(koilos.tabs.map { it.tab.uppercase() }, texts("story-part-title"), "the tab does not tell every part of Koilos")
        assertEquals(koilos.tabs.sumOf { it.story.size }, texts("lore-paragraph").size, "a paragraph of Koilos is missing")
    }

    @Test
    fun aLinkInTheStoryOpensTheArticleInItsOwnSectionAndIsUnderlined() {
        shell()
        openSection("weatherlight")
        val rath = MagicStory.article("rath-stronghold")!!
        tab(rath.title)
        tapLink(rath, "rath-made")
        assertEquals("#/guide/phyrexia-origins/rath-made", held.value.route.toHash(), "the link did not open the other Rath")
        assertEquals(MagicStory.sections.first { it.slug == "phyrexia-origins" }.articles.map { it.title }, texts("lore-tab"), "the tabs are not Phyrexia's Origins")
        assertEquals(texts("lore-tab").map { it == "Rath" }, selected())
    }

    @Test
    fun aNameOpensTheirProfileWhichListsEveryArticleTheyAreIn() {
        shell()
        openSection("brothers-war")
        tab("The War")
        tapLink(MagicStory.article("the-war")!!, "who/urza")
        assertEquals("#/guide/brothers-war/who/urza", held.value.route.toHash(), "Urza's name did not open his profile")
        val page = held.value.storyPage as? StoryPage.Profile ?: error("the app is not showing a profile: ${held.value.storyPage}")
        assertTrue(texts("lore-paragraph").isNotEmpty(), "the profile says nothing")
        MagicStory.appearances(page.person).forEach {
            rule.onNodeWithTag("story-appears-${it.slug}").assertExists("Urza's profile does not list ${it.title}")
        }
        rule.onNodeWithTag("story-appears-sylex-blast").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/brothers-war/sylex-blast", held.value.route.toHash(), "a listed article does not open")
    }

    @Test
    fun upFromAProfileIsTheSectionItWasOpenedFrom() {
        shell()
        openSection("brothers-war")
        tab("The War")
        tapLink(MagicStory.article("the-war")!!, "who/urza")
        val up = texts("story-up").singleOrNull() ?: error("a profile has no way back up")
        assertTrue("Brothers' War" in up, "up does not name the section: $up")
        rule.onNodeWithTag("story-up").performClick()
        rule.waitForIdle()
        assertEquals("#/guide/brothers-war", held.value.route.toHash())
        assertEquals(texts("lore-tab").map { it == "The Thran" }, selected(), "up did not show the section")
    }

    @Test
    fun theSourcesComeAtTheEndOutsideTheNarration() {
        shell()
        openSection("brothers-war")
        tab("The War")
        val sources = rule.onAllNodes(hasTestTag("story-source"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(MagicStory.article("the-war")!!.sources.size, sources.size, "not every source is listed")
        val paragraphs = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)
        val last = paragraphs[paragraphs.fetchSemanticsNodes().size - 1].getUnclippedBoundsInRoot()
        val block = rule.onNodeWithTag("story-sources", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(block.top >= last.bottom, "the sources sit inside the narration, not after it")
    }

    @Test
    fun readOnGoesTabByTabThenOnToTheNextSection() {
        shell()
        val war = MagicStory.sections.first()
        openSection(war.slug)
        war.articles.drop(1).forEach { a ->
            rule.onNodeWithTag("lore-next").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(texts("lore-tab").map { it == a.title }, selected(), "Read on did not open ${a.title}")
        }
        val label = texts("lore-next").singleOrNull() ?: error("the last tab of the Brothers' War has no way on")
        assertTrue("Phyrexia's Origins" in label, "the end of the Brothers' War does not lead to the next section: $label")
        rule.onNodeWithTag("lore-next").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/phyrexia-origins/halcyon", held.value.route.toHash())
    }

    @Test
    fun cardsAreSetApartFromTheNarration() {
        shell()
        openSection("brothers-war")
        val article = MagicStory.sections.first().articles.first()
        val paragraphs = article.tabs.flatMap { it.story }
        val i = paragraphs.indexOfFirst { p -> MagicStory.spans(p).any { it.card } }
        assertTrue(i >= 0, "${article.title} names no card")
        val node = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[i]
        val layout = assertNotNull(node.textLayout(), "the paragraph has no text layout")
        val name = MagicStory.spans(paragraphs[i]).first { it.card }.text
        val at = layout.layoutInput.text.text.indexOf(name)
        assertTrue(at >= 0, "$name is not in the paragraph on screen")
        val style = layout.layoutInput.text.spanStyles.firstOrNull { at in it.start until it.end }?.item
        assertEquals(FontStyle.Italic, style?.fontStyle, "$name is not set apart from the narration")
    }

    @Test
    fun itIsSetForReadingNotForScanning() {
        shell()
        openSection("brothers-war")
        tab("Koilos")
        val style = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[0].textLayout()?.layoutInput?.style
        assertNotNull(style, "the story has no text layout")
        assertTrue(style.fontSize.value >= 15f, "the story is ${style.fontSize}, too small to read a chapter in")
        assertTrue(
            style.lineHeight.isSp && style.lineHeight.value >= style.fontSize.value * 1.5f,
            "the story's lines are ${style.lineHeight} apart at ${style.fontSize}, too tight for prose",
        )
    }

    @Test
    fun backFromASectionIsTheTiles() {
        shell()
        openSection("ravnica")
        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("#/guide", held.value.route.toHash())
        rule.onNodeWithTag("guide-tile-ravnica").assertExists("back did not show the tiles")
    }
}
