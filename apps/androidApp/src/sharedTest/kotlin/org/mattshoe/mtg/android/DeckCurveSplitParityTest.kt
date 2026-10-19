package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mana curve's dropdown, through the real shell over an open deck.
 *
 * Matt: "i want a dropdown to select what my mana curve graph shows.
 * it defaults to all cards ... card type, color ... this will mean
 * showing numerous graphs at once". On a phone and both tablet sizes.
 * The website's sibling is `DeckCurveSplitTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeckCurveSplitParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(name: String, type: String, cost: String?, cmc: Double, qty: Int, ci: String, role: String? = null) =
        DeckCard(
            name, qty, role, qty,
            nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc, colorIdentity = ci,
        )

    private fun opened() = AppState(
        route = Route(View.DECKS, "a"),
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState()
            .loaded(listOf(Deck("a", "Gruul", "matt", "Leader (KHC) 2", "RG", 18, null)))
            .opened(
                "a",
                listOf(
                    card("Leader", "Legendary Creature — Elf", "{2}{G}", 3.0, 1, "G", role = "commander"),
                    card("Bear", "Creature — Bear", "{1}{G}", 2.0, 3, "G"),
                    card("Bolt", "Instant", "{R}", 1.0, 2, "R"),
                    card("Gold", "Sorcery", "{R}{G}", 2.0, 1, "RG"),
                    card("Rock", "Artifact", "{2}", 2.0, 1, ""),
                    card("Forest", "Basic Land — Forest", null, 0.0, 10, "G"),
                ),
            ),
    )

    private fun shell(size: DpSize? = null) {
        val start = opened()
        rule.setContent {
            val body = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(start)
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
            if (size == null) body() else Tablet.Sized(size) { body() }
        }
        rule.waitForIdle()
    }

    private fun exists(t: String): Boolean =
        rule.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun words(t: String): String =
        rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }

    /** What the dropdown reads, merged the way a screen reader hears it. */
    private fun shown(): String =
        rule.onNodeWithTag("select-curve-split").fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }

    private fun pick(label: String) {
        assertTrue(exists("select-curve-split"), "the Mana curve chart has no dropdown")
        rule.onNodeWithTag("select-curve-split", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(label)).onLast().performClick()
        rule.waitForIdle()
    }

    /** The graphs drawn, in order down the screen, each with its non-empty columns as "label=count". */
    private fun graphs(titles: List<String>): List<Pair<String, List<String>>> {
        val drawn = titles.filter { exists("curve-chart-$it") }
            .sortedBy { bounds("curve-chart-$it").top.value }
        return drawn.map { t ->
            t to (0..7).mapNotNull { mv ->
                val label = if (mv == 7) "7+" else "$mv"
                words("curve-$t-count-$label").takeIf { it.isNotEmpty() }?.let { "$label=$it" }
            }
        }
    }

    private val types = listOf("Commander", "Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts", "Enchantments", "Battles", "Other")
    private val colours = listOf("White", "Blue", "Black", "Red", "Green", "Colourless")

    @Test
    fun theDropdownStartsOnAllCardsAndDrawsOneCurve() {
        shell()
        assertTrue(exists("select-curve-split"), "the Mana curve chart has no dropdown")
        assertTrue("All cards" in shown(), "the curve does not start on all cards: \"${shown()}\"")
        assertTrue(exists("curve"), "no all-cards curve")
        assertEquals(emptyList(), graphs(types + colours), "all cards drew split graphs")
    }

    @Test
    fun cardTypeDrawsOneCurvePerTypeWithNoLands() {
        shell()
        pick("Card type")
        assertEquals(
            listOf(
                "Creatures" to listOf("2=3", "3=1"),
                "Instants" to listOf("1=2"),
                "Sorceries" to listOf("2=1"),
                "Artifacts" to listOf("2=1"),
            ),
            graphs(types + colours),
        )
    }

    @Test
    fun colourDrawsOneCurvePerColourAndBackToAllCardsIsOneAgain() {
        shell()
        pick("Colour")
        assertEquals(
            listOf(
                "Red" to listOf("1=2", "2=1"),
                "Green" to listOf("2=4", "3=1"),
                "Colourless" to listOf("2=1"),
            ),
            graphs(types + colours),
        )
        pick("All cards")
        assertEquals(emptyList(), graphs(types + colours), "going back to all cards left split graphs")
        assertTrue(exists("curve"), "going back to all cards drew no curve")
    }

    /** Every graph inside the screen, and one scale across them all. */
    private fun fits(size: DpSize?) {
        shell(size)
        pick("Card type")
        val width = rule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot().right.value
        listOf("Creatures", "Instants", "Sorceries", "Artifacts").forEach { t ->
            val b = bounds("curve-chart-$t")
            assertTrue(b.left.value >= -0.5f && b.right.value <= width + 0.5f, "$t runs off a ${width}dp screen: ${b.left}..${b.right}")
        }
        fun tall(t: String) = bounds("curve-$t-bar-2").let { it.bottom.value - it.top.value }
        val creatures = tall("Creatures")
        val sorcery = tall("Sorceries")
        assertTrue(creatures > 30f, "the tallest column is only ${creatures}dp")
        assertTrue(
            kotlin.math.abs(creatures / sorcery - 3f) < 0.25f,
            "the graphs are not on one scale: 3 creatures drew ${creatures}dp, 1 sorcery ${sorcery}dp",
        )
    }

    @Test
    fun theGraphsFitAPhone() = fits(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theGraphsFitAFoldingPhoneOpenedOut() = fits(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theGraphsFitATabletHeldSideways() = fits(Tablet.LANDSCAPE)
}
