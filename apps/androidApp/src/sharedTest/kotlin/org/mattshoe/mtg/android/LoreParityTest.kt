package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The story of Phyrexia, through the real shell and its real bottom bar.
 *
 * Matt: "i want a new guide section on phyrexian lore ... from thran to
 * modern times ... i want this particular guide to be tabbed, so the
 * user can tab through the various 'eras' ... this should read as
 * literature". Sibling of `LorePageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class LoreParityTest {

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

    private fun openLore() {
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        // Every story is inside Lore.
        rule.onNodeWithTag("guide-tile-lore").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-phyrexia").assertExists("the Guide has no Phyrexia tile")
        rule.onNodeWithTag("guide-tile-phyrexia").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun tabs() = rule.onAllNodes(hasTestTag("lore-tab"), useUnmergedTree = true)

    private fun tabTexts(): List<String> = tabs().fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun selected(): List<Boolean> = tabs().fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Selected) ?: false
    }

    private fun story(): String =
        rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true).fetchSemanticsNodes().joinToString(" ") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        }

    private fun tab(named: String) {
        val i = tabTexts().indexOfFirst { named in it }
        assertTrue(i >= 0, "there is no $named tab: ${tabTexts()}")
        tabs()[i].performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    @Test
    fun theTileOpensOnTheThranWithATabForEveryEra() {
        shell()
        openLore()
        assertEquals("#/guide/phyrexia", held.value.route.toHash())
        val names = tabTexts()
        assertTrue(names.size >= 6, "the page has ${names.size} tabs: $names")
        assertEquals("The Thran", names.first(), "the first tab is not the Thran: $names")
        assertTrue(names.any { "Brothers" in it } && names.any { "Mirrodin" in it }, "eras are missing: $names")
        assertEquals(listOf(true) + List(names.size - 1) { false }, selected(), "the Thran is not the one open tab")
        assertTrue("Yawgmoth" in story(), "the Thran's story does not tell of Yawgmoth: ${story().take(200)}")
        assertTrue(rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true).fetchSemanticsNodes().size >= 4, "the story is not paragraphs")
    }

    @Test
    fun tappingATabTellsThatErasStoryInstead() {
        shell()
        openLore()
        tab("Brothers")
        assertEquals("#/guide/phyrexia/brothers", held.value.route.toHash(), "the tab is not in the address")
        val names = tabTexts()
        assertEquals(names.map { "Brothers" in it }, selected(), "the Brothers' War is not the one open tab")
        assertTrue("Mishra" in story(), "the Brothers' War tab does not tell of Mishra: ${story().take(200)}")
        assertTrue("Glacian" !in story(), "the Thran's story is still on the page under the Brothers' War")
        rule.onNodeWithTag("lore-title", useUnmergedTree = true).assertExists("the era has no title")
    }

    @Test
    fun theTabsAreOneRowThatScrollsSidewaysOnAPhone() {
        shell()
        openLore()
        val boxes = tabs().fetchSemanticsNodes().indices.map { tabs()[it].getUnclippedBoundsInRoot() }
        assertEquals(1, boxes.map { it.top }.toSet().size, "the tabs wrap onto more than one row, so they are a pile, not a tab bar")
        // Lines, not a height in dp: the JVM's font is taller than a
        // phone's, and a 14sp label measured 55dp there on one line.
        tabTexts().forEachIndexed { i, name ->
            val layout = assertNotNull(tabs()[i].textLayout(), "the $name tab has no text layout")
            assertEquals(1, layout.lineCount, "the $name tab's label runs to ${layout.lineCount} lines")
            assertTrue(!layout.isLineEllipsized(0) && !layout.hasVisualOverflow, "the $name tab's label is cut short")
        }
        val screen = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        assertTrue(boxes.last().right > screen.right, "every tab fits on a phone already, so this proves nothing about scrolling")
        // The last era has to be reachable, by scrolling the row.
        tab("Machine")
        assertTrue(selected().last(), "the last tab could not be opened")
    }

    @Test
    fun itIsSetForReadingNotForScanning() {
        shell()
        openLore()
        val style = rule.onAllNodes(hasTestTag("lore-paragraph"), useUnmergedTree = true)[0].textLayout()?.layoutInput?.style
        assertNotNull(style, "the story has no text layout")
        assertTrue(style.fontSize.value >= 15f, "the story is ${style.fontSize}, too small to read a chapter in")
        assertTrue(
            style.lineHeight.isSp && style.lineHeight.value >= style.fontSize.value * 1.5f,
            "the story's lines are ${style.lineHeight} apart at ${style.fontSize}, too tight for prose",
        )
    }

    @Test
    fun readOnGoesToTheNextEraAndTheLastHasNowhereToGo() {
        shell()
        openLore()
        rule.onNodeWithTag("lore-next").assertExists("the Thran has no way on to the next era")
        rule.onNodeWithTag("lore-next").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/phyrexia/brothers", held.value.route.toHash(), "Read on did not go to the Brothers' War")
        tab("Machine")
        rule.onNodeWithTag("lore-next").assertDoesNotExist()
    }

    @Test
    fun aQuoteSaysWhichCardItIsFrom() {
        shell()
        openLore()
        tab("Invasion")
        rule.onNodeWithTag("lore-quote", useUnmergedTree = true).assertExists("the Invasion quotes nothing")
        val source = rule.onNodeWithTag("lore-quote-source", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        assertTrue(source.isNotBlank(), "the quote does not say where it is from")
    }

    @Test
    fun backFromAnEraIsTheTiles() {
        shell()
        openLore()
        tab("Rath")
        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("#/guide/lore", held.value.route.toHash())
        rule.onNodeWithTag("guide-tile-phyrexia").assertExists("back did not show the tiles")
    }
}
