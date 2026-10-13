package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Four of section 6's smaller cosmetics, the ones that are measured.
 *
 * A dp of gap between curve bars, a row of pager buttons that sits
 * where the stylesheet centres it, the half-step font weight
 * `.btn` sets and will not round to one of Material's nine, and a
 * card name the website keeps on one line and the phone let run to
 * two.
 *
 * All four are read off a laid-out screen rather than out of the
 * source: the bars' own rectangles, the pager's midpoint against its
 * row's, and the `TextLayoutResult` the node reports through its own
 * semantics action. All four go through a real `AppShell`, because
 * the Library's pager and its filter panel both live inside a lazy
 * grid and a tile is not a tile until the grid has laid one out.
 */
@RunWith(AndroidJUnit4::class)
class SmallCosmeticsLayoutParityTest {

    @get:Rule
    val rule = createComposeRule()

    // --------------------------------------------------------- fixtures

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun spell(name: String, cost: Double) = DeckCard(
        name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Creature — Faerie",
        cmc = cost,
        scryfallId = "abcdef12-3456",
    )

    private fun row(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 1, printings = 1, free = 1, price = 2.5, value = 2.5,
    )

    private fun shell(start: AppState) {
        rule.setContent {
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
        rule.waitForIdle()
    }

    /** The Library, with a grid that has something in it. */
    private fun library(name: String, total: Int = 1) = AppState(
        route = Route(View.LIBRARY),
        library = Library().loaded(listOf(row(name)), total),
    )

    // ---------------------------------------------------------- reading

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    private fun tagBox(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun textBox(text: String) =
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    // ======================================= the curve's 6px bar gap

    @Test
    fun theCurveLeavesTheWebsSixPixelsBetweenItsBars() {
        shell(
            AppState(
                route = Route(View.DECKS, "alela"),
                admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
                decks = DecksState().loaded(listOf(deck())).opened(
                    "alela",
                    listOf(spell("One", 1.0), spell("Two", 2.0), spell("Three", 3.0)),
                ),
            ),
        )
        val first = tagBox("curve-bar-1")
        val second = tagBox("curve-bar-2")
        val gap = second.left.value - first.right.value
        // `.curve { gap: 6px }`, read off the laid-out bars rather
        // than off the `Arrangement` the source asks for.
        assertTrue(
            abs(gap - 6f) < 0.6f,
            "the curve's bars are ${gap}dp apart where the stylesheet sets 6px",
        )
    }

    // ============================== the pager, centred under the grid

    @Test
    fun thePagerSitsInTheMiddleOfItsRow() {
        shell(library("Sol Ring", total = 250))
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()

        val row = tagBox("pager")
        val previous = textBox("← Previous")
        val next = textBox("Next →")
        // Not wrapped onto two lines — a column of three controls is
        // centred too, and would pass a midpoint check while looking
        // nothing like the bar the website draws.
        assertTrue(
            abs(previous.top.value - next.top.value) < 1f,
            "the pager wrapped, so this measures a column and not a row",
        )
        // `.pager { justify-content: center }`: the content's middle
        // is the row's middle. Packed to the start, the left gutter
        // was nothing and the right one was everything left over.
        val leftGutter = previous.left.value - row.left.value
        val rightGutter = row.right.value - next.right.value
        assertTrue(
            leftGutter > 2f,
            "the pager is flush against the left edge of its row",
        )
        assertTrue(
            abs(leftGutter - rightGutter) < 2f,
            "the pager has ${leftGutter}dp on its left and ${rightGutter}dp on its right",
        )
    }

    // ========================================= the Reset button's weight

    @Test
    fun resetEverythingCarriesTheWebsButtonWeight() {
        shell(library("Sol Ring"))
        val reset = rule.onNodeWithText("Reset everything", useUnmergedTree = true)
        reset.assertExists()
        val weight = reset.textLayout()?.layoutInput?.style?.fontWeight
        assertNotNull(weight, "the Reset button reports no weight at all")
        // `.btn { font-weight: 550 }`. Material's Medium is 500 and
        // its SemiBold is 600; the stylesheet means neither.
        assertEquals(
            FontWeight(550),
            weight,
            "Reset is drawn at ${weight.weight} where `.btn` sets 550",
        )
    }

    // ===================================== the card name, on one line

    /**
     * A real card, and the longest name Magic has.
     *
     * Robolectric cannot be asked whether it wrapped. Its text
     * measurement is a stub — these 141 characters measure 142
     * pixels, one per glyph — so every name fits every box and
     * `lineCount` is 1 whatever the clamp says. Measured, not
     * assumed: the first version of this test asserted `lineCount`
     * and went green against the two-line code.
     *
     * What the JVM can be asked is what the laid-out node's clamp
     * actually is, read back off the screen through the same
     * semantics action the style comes through. The wrap itself is
     * checked on a device, below.
     */
    private val longestName =
        "Our Market Research Shows That Players Like Really Long Card Names " +
            "So We Made this Card to Have the Absolute Longest Card Name Ever Elemental"

    private fun tileName(): TextLayoutResult {
        shell(library(longestName))
        rule.onNodeWithTag("library").performScrollToKey("matt:${longestName.lowercase()}")
        rule.waitForIdle()
        val layout = rule.onNodeWithText(longestName, useUnmergedTree = true).textLayout()
        assertNotNull(layout, "the tile's name reports no layout")
        return layout
    }

    @Test
    fun theGridClampsACardNameToOneLine() {
        val layout = tileName()
        // `.card-meta .nm { white-space: nowrap; text-overflow: ellipsis }`.
        // Two lines made a row of the grid as tall as its longest
        // name, so a shelf of tiles stepped up and down.
        assertEquals(
            1,
            layout.layoutInput.maxLines,
            "the tile's name is clamped at ${layout.layoutInput.maxLines} lines, not the page's one",
        )
        assertEquals(
            TextOverflow.Ellipsis,
            layout.layoutInput.overflow,
            "a name too long for its tile is cut with nothing saying there is more of it",
        )
    }

    @Test
    fun theLongestCardNameInMagicReallyDoesStayOnOneLine() {
        // The clamp above, as the thing it is for. Only a device can
        // answer it: see `longestName`.
        Parity.needsRealRendering()
        val layout = tileName()
        assertTrue(
            layout.isLineEllipsized(0),
            "the longest name in Magic fits a 132dp tile, so this proves nothing",
        )
        assertEquals(
            1,
            layout.lineCount,
            "the name ran to ${layout.lineCount} lines where the page keeps it on one",
        )
    }
}
