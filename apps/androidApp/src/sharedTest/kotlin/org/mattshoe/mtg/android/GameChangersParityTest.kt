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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.GameChangers
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's Game Changers, through the real shell and its real
 * bottom bar.
 *
 * Matt: "add a guide section for game changers. it should be a list
 * of cards just like a deck page. tapping any of them should pop a
 * carousel like any other cards". Sibling of `GameChangersPageTest`.
 */
@RunWith(AndroidJUnit4::class)
class GameChangersParityTest {

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

    /** The Guide tab, then the Game Changers tile, the way a person gets there. */
    private fun openGameChangers() {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasTestTag("guide-tile-game-changers")).fetchSemanticsNodes().isNotEmpty(),
            "the Guide has no Game Changers tile",
        )
        rule.onNodeWithTag("guide-tile-game-changers").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun rowNames(): List<String> =
        rule.onAllNodes(hasTestTag("gc-row")).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        }

    private fun said(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    @Test
    fun theTileOpensEveryGameChangerByTypeLikeADeck() {
        shell()
        openGameChangers()
        assertEquals("#/guide/game-changers", held.value.route.toHash(), "the tile did not open its own address")

        val groups = listOf("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts", "Enchantments", "Lands")
        val missing = groups.filter { rule.onAllNodes(hasTestTag("group-head-$it")).fetchSemanticsNodes().isEmpty() }
        assertTrue(missing.isEmpty(), "the list is not sectioned the way a deck is; no heading for $missing")
        val tops = groups.map { rule.onNodeWithTag("group-head-$it").getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "the sections are not in a deck list's order: ${groups.zip(tops)}")

        val names = rowNames()
        assertEquals(53, names.size, "the page does not list the 53 game changers: $names")
        assertEquals(GameChangers.pageOrder.map { it.shown }, names, "the rows are not in the page order the carousel swipes through")
        val rows = rule.onAllNodes(hasTestTag("gc-row"))
        val first = rows[0].getUnclippedBoundsInRoot()
        val second = rows[1].getUnclippedBoundsInRoot()
        assertTrue(second.top >= first.bottom, "the second row is not under the first, so this is not a list")
    }

    @Test
    fun tappingACardPopsTheCarouselOnThatCard() {
        shell()
        openGameChangers()
        assertTrue(
            rule.onAllNodes(hasTestTag("gc-row") and hasText("Rhystic Study")).fetchSemanticsNodes().isNotEmpty(),
            "there is no Rhystic Study row",
        )
        rule.onAllNodes(hasTestTag("gc-row") and hasText("Rhystic Study"))[0].performScrollTo().performClick()
        rule.waitForIdle()

        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Rhystic Study opened no carousel")
        assertEquals(PeekOf.GAME_CHANGERS, held.value.peek.of)
        rule.onNodeWithTag("carousel-sheet").assertExists("the carousel is not drawn")
        val at = GameChangers.pageOrder.indexOfFirst { it.name == "Rhystic Study" }
        assertEquals("${at + 1} of 53", said("carousel-place"))
        assertTrue(
            rule.onAllNodes(hasText("Rhystic Study")).fetchSemanticsNodes().size >= 2,
            "the carousel does not name the card",
        )
        assertTrue(
            rule.onAllNodes(hasText("EDHREC #46 of all cards"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the carousel does not say how played it is",
        )
    }
}
