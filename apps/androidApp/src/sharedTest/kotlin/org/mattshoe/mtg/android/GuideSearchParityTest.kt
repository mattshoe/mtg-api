package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.StoryPage
import org.mattshoe.mtg.core.loreEra
import org.mattshoe.mtg.core.storyPage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the Guide's articles, through the real shell and its real
 * bottom bar.
 *
 * Matt: "i want to be able to search guide articles on the Guide page
 * ... searching should show any articles which contain the keywords on
 * the search bar". Sibling of `GuideSearchPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class GuideSearchParityTest {

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
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
    }

    private fun search(words: String) {
        rule.onNodeWithTag("guide-search").assertExists("the Guide has no search bar")
        rule.onNodeWithTag("guide-search").performTextClearance()
        if (words.isNotEmpty()) rule.onNodeWithTag("guide-search").performTextInput(words)
        rule.waitForIdle()
    }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun hits() = texts("guide-hit-title")

    @Test
    fun searchingShowsEveryArticleWithTheWordInsteadOfTheTiles() {
        shell()
        search("Sylex")
        val hits = hits()
        assertTrue("Sylex Blast" in hits, "searching Sylex did not find Sylex Blast: $hits")
        assertTrue(hits.size > 3, "searching Sylex found too little: $hits")
        texts("guide-hit-snippet").forEach { assertTrue(it.contains("sylex", ignoreCase = true), "a hit does not show where the word is: \"$it\"") }
        assertEquals(hits.size, texts("guide-hit-part").count { it.isNotBlank() }, "a hit does not say where it is from")
        assertEquals(listOf("${hits.size} articles"), texts("guide-search-count"))
        assertTrue(rule.onAllNodes(hasTestTag("guide-tile-lore")).fetchSemanticsNodes().isEmpty(), "the tiles are still there under the hits")

        val bar = rule.onNodeWithTag("guide-search").getUnclippedBoundsInRoot()
        val first = rule.onAllNodes(hasTestTag("guide-hit"))[0].getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the hits: ${bar.bottom} vs ${first.top}")
    }

    @Test
    fun everyWordHasToBeThere() {
        shell()
        search("Yawgmoth")
        val one = hits()
        search("Yawgmoth Sylex")
        val both = hits()
        assertTrue(both.isNotEmpty() && both.size < one.size, "a second word did not narrow it: ${one.size} then ${both.size}")
    }

    @Test
    fun tappingAHitOpensTheArticleAndBackFindsTheSearchStillThere() {
        shell()
        search("Sylex")
        val i = hits().indexOf("Sylex Blast")
        rule.onAllNodes(hasTestTag("guide-hit"))[i].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/brothers-war/sylex-blast", held.value.route.toHash())
        assertEquals("sylex-blast", (held.value.storyPage as? StoryPage.Reading)?.article?.slug)
        assertTrue(rule.onAllNodes(hasTestTag("lore-story"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the article is not on screen")

        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("Sylex", held.value.guideSearch)
        assertTrue("Sylex Blast" in hits(), "back to the Guide lost the hits")
    }

    @Test
    fun aPhyrexiaEraOpensAtItsOwnTab() {
        shell()
        search("Glacian")
        val i = texts("guide-hit-part").indexOf("Phyrexia")
        assertTrue(i >= 0, "no Phyrexia era found for Glacian: ${texts("guide-hit-part")}")
        rule.onAllNodes(hasTestTag("guide-hit"))[i].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("thran", held.value.loreEra?.slug, "the era did not open: ${held.value.route.toHash()}")
    }

    @Test
    fun aWordNoArticleHasSaysSoAndClearingItBringsTheTilesBack() {
        shell()
        search("xyzzyplugh")
        assertEquals(listOf("No article mentions \"xyzzyplugh\"."), texts("guide-search-none"))
        assertTrue(hits().isEmpty())
        search("")
        rule.onNodeWithTag("guide-tile-lore").assertExists("clearing the search did not bring the tiles back")
    }
}
