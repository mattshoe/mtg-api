package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Library
import kotlin.test.assertEquals

/**
 * Numbers that must not jog sideways as their digits widen.
 *
 * The web pins the mana curve's counts and axis labels, a deck row's
 * "N×" and the Library price badge to `var(--mono)`, and the pager's
 * "Page X of Y" to `font-variant-numeric: tabular-nums`. Android had
 * none of it, even though `Theme.kt` already carries a `monoSmall`
 * convention for exactly this. A node's actual resolved `TextStyle` —
 * read back through the same `getTextLayoutResult` semantics action a
 * screen reader's "read font" would use — is the fact; a screenshot
 * read by eye is not.
 */
@RunWith(AndroidJUnit4::class)
class FixedWidthNumbersParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    private fun SemanticsNodeInteraction.fontFamily(): FontFamily? = textLayout()?.layoutInput?.style?.fontFamily

    private fun card(name: String, qty: Int) = DeckCard(
        name, qty, null, qty, nameNorm = name.lowercase(), typeLine = "Creature", cmc = 1.0,
    )

    // ======================================================== the curve

    @Test
    fun theCurveCountIsMonospace() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { DeckStatsPanel(DeckAnalysis.of(listOf(card("Soldier", 6)))) }
            }
        }
        rule.waitForIdle()
        assertEquals(
            FontFamily.Monospace,
            rule.onNodeWithTag("curve-count-1", useUnmergedTree = true).fontFamily(),
            "the curve's count over the bar is not drawn in the fixed-width family the web uses",
        )
    }

    @Test
    fun theCurveAxisLabelIsMonospace() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { DeckStatsPanel(DeckAnalysis.of(listOf(card("Soldier", 6)))) }
            }
        }
        rule.waitForIdle()
        assertEquals(
            FontFamily.Monospace,
            rule.onNodeWithTag("curve-label-1", useUnmergedTree = true).fontFamily(),
            "the curve's axis label is not drawn in the fixed-width family the web uses",
        )
    }

    // =================================================== the deck row "N×"

    @Test
    fun theDeckRowQuantityIsMonospace() {
        val deck = Deck("a", "Alela", "matt", null, "W", null, null)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    DecksScreen(
                        DecksState().loaded(listOf(deck)).opened("a", listOf(card("Soldier", 6))),
                        {},
                        {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(
            FontFamily.Monospace,
            rule.onNodeWithText("6×", useUnmergedTree = true).fontFamily(),
            "the deck row's quantity is not drawn in the fixed-width family the web uses",
        )
    }

    // ================================================= the Library price

    private fun priceCard(price: Double) = CardRow(
        id = 1, owner = "matt", name = "Sol Ring", nameNorm = "sol ring", face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 1, printings = 1, free = 1, price = price, value = price,
    )

    @Test
    fun theLibraryPriceBadgeIsMonospace() {
        val row = priceCard(2.5)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    LibraryScreen(
                        state = Library().loaded(listOf(row), 1),
                        onState = {},
                        onSearch = {},
                        onOpen = {},
                        facets = Facets(),
                    )
                }
            }
        }
        rule.waitForIdle()
        // The grid is lazy. A row not scrolled to is not composed.
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()
        assertEquals(
            FontFamily.Monospace,
            rule.onNodeWithText("$2.50", useUnmergedTree = true).fontFamily(),
            "the Library price badge is not drawn in the fixed-width family the web uses",
        )
    }

    // ========================================================= the pager

    @Test
    fun thePagerPageCountHasTabularFigures() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    LibraryScreen(
                        state = Library().loaded(listOf(priceCard(2.5)), 250),
                        onState = {},
                        onSearch = {},
                        onOpen = {},
                        facets = Facets(),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()
        val node = rule.onNodeWithText("Page 1 of 3", useUnmergedTree = true)
        assertEquals(
            "tnum",
            node.textLayout()?.layoutInput?.style?.fontFeatureSettings,
            "\"Page X of Y\" has no tabular-figure feature, so Next can jog sideways as it widens",
        )
    }
}
