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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Guide, through the real shell and its real bottom bar.
 *
 * Matt: "i want a new section in the bottom nav ... when you tap the
 * tab on bottom nav, you land on a new page, which is a collection of
 * very attractive and stylish 'tiles' ... a reference to all the names
 * for all the color combinations like izzet, dimir, etc etc. all of
 * them all the way through wubrg." Sibling of `GuidePageTest`.
 */
@RunWith(AndroidJUnit4::class)
class GuideParityTest {

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

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        }

    private fun headerSays(): String =
        rule.onNodeWithTag("topbar-title", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)
            .orEmpty().joinToString("") { it.text }

    private fun openGuide() {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
    }

    private fun openColours() {
        openGuide()
        rule.onNodeWithTag("guide-tile-colours").performClick()
        rule.waitForIdle()
    }

    @Test
    fun theBottomBarHasAGuideTabWithItsOwnIcon() {
        shell()
        assertTrue(
            rule.onAllNodes(hasTestTag("nav-icon-Guide"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the Guide tab is missing, or has no icon",
        )
        openGuide()
        assertEquals(View.GUIDE, held.value.view, "tapping Guide did not go to the Guide")
        rule.onNodeWithTag("guide-tile-colours").assertExists("the Guide has no colour combinations tile")
    }

    @Test
    fun theColourTileListsEveryCombinationByName() {
        shell()
        openColours()
        assertEquals("#/guide/colours", held.value.route.toHash())
        val names = texts("combo-name")
        assertEquals(31, names.size, "every combination from one colour to five is 31; the page shows $names")
        listOf("Izzet", "Dimir", "Azorius", "Esper", "Jeskai", "Glint-Eye", "Five-colour").forEach {
            assertTrue(it in names, "$it is not on the page: $names")
        }
        assertTrue(headerSays().contains("Colour"), "the header does not name the page: ${headerSays()}")
    }

    @Test
    fun theCombinationsSitTwoToARow() {
        shell()
        openColours()
        val w = rule.onNodeWithTag("combo-W", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val u = rule.onNodeWithTag("combo-U", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(w.top, u.top, "blue is not beside white, so the page is one long column")
        assertTrue(u.left >= w.right, "the two tiles overlap: ${w.right} vs ${u.left}")
        assertTrue(w.right - w.left >= 120.dp, "a tile is ${w.right - w.left} wide, too narrow for Selesnya")
    }

    @Test
    fun everyTileHasItsColourStrip() {
        shell()
        openColours()
        val strips = rule.onAllNodes(hasTestTag("guide-band"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(31, strips.size, "a combination is missing its strip")
        val one = strips.first()
        assertTrue(one.size.height > 0, "the strip has no height")
    }

    @Test
    fun backFromTheColoursIsTheTilesAndTheButtonDoesTheSame() {
        shell()
        openColours()
        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("#/guide", held.value.route.toHash())
        rule.onNodeWithTag("guide-tile-colours").assertExists("the back button did not show the tiles")

        rule.onNodeWithTag("guide-tile-colours").performClick()
        rule.waitForIdle()
        val back = held.value.back()
        assertNotNull(back, "back from the colour page closed the app")
        rule.runOnIdle { held.value = back }
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-colours").assertExists("system back did not show the tiles")
    }
}
