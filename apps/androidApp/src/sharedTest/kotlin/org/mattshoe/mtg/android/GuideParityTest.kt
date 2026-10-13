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

    // ------------------------------------------------------------ the wheel
    // Matt: "i want a guide section on the color wheel. i know nothing
    // about it and it'll be cool to have ... it gets its own dedicated page"

    private fun openWheel() {
        openGuide()
        rule.onNodeWithTag("guide-tile-wheel").assertExists("the Guide has no colour wheel tile")
        rule.onNodeWithTag("guide-tile-wheel").performClick()
        rule.waitForIdle()
    }

    @Test
    fun theWheelTileOpensAPageOfItsOwn() {
        shell()
        openWheel()
        assertEquals("#/guide/wheel", held.value.route.toHash())
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green"), texts("wheel-colour-name"), "the five colours on the page")
        assertTrue(texts("combo-name").isEmpty(), "the wheel page is drawing the colour combinations")
        assertTrue(headerSays().contains("wheel", ignoreCase = true), "the header does not name the page: ${headerSays()}")
    }

    @Test
    fun theWheelIsDrawnAsACircleWithWhiteAtTheTopGoingClockwise() {
        shell()
        openWheel()
        val centres = "WUBRG".map { p ->
            val b = rule.onNodeWithTag("wheel-spoke-$p", useUnmergedTree = true).getUnclippedBoundsInRoot()
            Triple(p.toString(), ((b.left + b.right) / 2).value.toDouble(), ((b.top + b.bottom) / 2).value.toDouble())
        }
        val cx = centres.sumOf { it.second } / 5
        val cy = centres.sumOf { it.third } / 5
        val radii = centres.map { kotlin.math.hypot(it.second - cx, it.third - cy) }
        assertTrue(radii.min() > 30.0, "the colours are bunched up, not round a wheel: radii $radii")
        assertTrue(radii.max() - radii.min() < radii.max() * 0.05, "the colours are not on one circle: radii $radii")
        assertEquals("W", centres.minBy { it.third }.first, "white is not at the top")
        val order = centres.sortedBy { (_, x, y) ->
            val a = kotlin.math.atan2(x - cx, cy - y)
            if (a < -0.01) a + 2 * kotlin.math.PI else a
        }.map { it.first }
        assertEquals(listOf("W", "U", "B", "R", "G"), order, "the colours do not go round clockwise in WUBRG order")
    }

    @Test
    fun eachColourNamesItsAlliesAndItsEnemies() {
        shell()
        openWheel()
        val allies = texts("wheel-allies-W").joinToString(" ")
        val enemies = texts("wheel-enemies-W").joinToString(" ")
        assertTrue("Blue" in allies && "Green" in allies, "white's allies should be blue and green: \"$allies\"")
        assertTrue("Black" in enemies && "Red" in enemies, "white's enemies should be black and red: \"$enemies\"")
        listOf("wheel-wants", "wheel-good", "wheel-bad").forEach { part ->
            val said = texts(part)
            assertEquals(5, said.size, "not every colour has $part: $said")
            said.forEach { assertTrue(it.isNotBlank(), "a colour has nothing in $part") }
        }
    }

    @Test
    fun theTenPairsAreListedByGuild() {
        shell()
        openWheel()
        val names = texts("wheel-pair-name")
        assertEquals(10, names.size, "the pairs on the page: $names")
        listOf("Azorius", "Gruul", "Izzet", "Orzhov").forEach { assertTrue(it in names, "$it is not on the page: $names") }
    }

    @Test
    fun backFromTheWheelIsTheTiles() {
        shell()
        openWheel()
        rule.onNodeWithTag("guide-back").performClick()
        rule.waitForIdle()
        assertEquals("#/guide", held.value.route.toHash())
        rule.onNodeWithTag("guide-tile-wheel").assertExists("back did not show the tiles")
    }
}
