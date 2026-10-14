package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import org.mattshoe.mtg.core.Overlay
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the Game Changers by name, through the real shell and its
 * real bottom bar.
 *
 * Matt: "add a name search to the game changers page". Sibling of
 * `GameChangerSearchPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class GameChangerSearchParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    /** The Guide tab, then the Game Changers tile, the way a person gets there. */
    private fun openGameChangers() {
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
        rule.onNodeWithTag("guide-tile-game-changers").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun search(words: String) {
        rule.onNodeWithTag("gc-search").assertExists("the Game Changers page has no search bar")
        rule.onNodeWithTag("gc-search").performTextClearance()
        if (words.isNotEmpty()) rule.onNodeWithTag("gc-search").performTextInput(words)
        rule.waitForIdle()
    }

    private fun rowNames(): List<String> =
        rule.onAllNodes(hasTestTag("gc-row")).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    @Test
    fun aNameShowsOnlyTheCardsWithItUnderTheirOwnHeads() {
        openGameChangers()
        search("tutor")
        assertEquals(
            listOf("Enlightened Tutor", "Mystical Tutor", "Vampiric Tutor", "Worldly Tutor", "Demonic Tutor"),
            rowNames(),
            "searching tutor did not leave the tutors",
        )
        val heads = listOf("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts", "Enchantments", "Lands")
            .filter { rule.onAllNodes(hasTestTag("group-head-$it")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Instants", "Sorceries"), heads, "a section with no tutor in it is still on the page")
        assertEquals(listOf("5 of 53 cards"), texts("gc-search-count"), "nothing says how many were found")

        val bar = rule.onNodeWithTag("gc-search").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag("group-head-Instants").getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the list: ${bar.bottom} vs ${first.top}")
    }

    @Test
    fun tappingAFoundCardPopsTheCarouselOverWhatWasFound() {
        openGameChangers()
        search("tutor")
        rule.onAllNodes(hasTestTag("gc-row") and hasText("Worldly Tutor"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Worldly Tutor opened no carousel")
        assertEquals(listOf("4 of 5"), texts("carousel-place"), "the carousel is over every game changer, not the ones found")
    }

    @Test
    fun aNameNoGameChangerHasSaysSoAndClearingItBringsBackEveryCard() {
        openGameChangers()
        search("sol ring")
        assertEquals(listOf("No game changer is called \"sol ring\"."), texts("gc-search-none"))
        assertTrue(rowNames().isEmpty(), "rows are left under a search that found nothing: ${rowNames()}")
        search("")
        assertEquals(53, rowNames().size, "clearing the search did not bring back every game changer")
        assertTrue(texts("gc-search-count").isEmpty(), "a count is left over a list nobody searched")
    }
}
