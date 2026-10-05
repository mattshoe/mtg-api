package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.Legality
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.View
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The legality chips, and two placeholders Android had and the
 * website does not.
 *
 * `.chip.mini` leaves the chip's face the page's own colour, puts the
 * tone on a one-pixel edge, rounds it to a pill, sets 11px and takes
 * the body's weight. Android drew a tinted lozenge at 12sp in Medium
 * or SemiBold depending on the status — four separate differences in
 * one small thing, which is how it stayed on the list through five
 * rounds of bigger fixes.
 *
 * What a JVM run can see of a chip is its size, its weight, its text
 * colour and its measured box. What it cannot see is a fill or an
 * edge: that needs pixels, and `graphicsMode = NATIVE` was removed
 * from this module for crashing the executor mid-run. The fill and
 * the edge are stated in `DesignTest` instead, against the
 * declaration in `app.css` that sets them — which is a claim about
 * the two platforms agreeing on a number, and is said here rather
 * than implied.
 */
@RunWith(AndroidJUnit4::class)
class SmallCosmeticsChipParityTest {

    @get:Rule
    val rule = createComposeRule()

    // --------------------------------------------------------- fixtures

    /** One of every status, which is the web suite's own `mixed()`. */
    private fun mixed() = CardDetail(
        name = "Sol Ring",
        legalities = listOf(
            Legality("commander", "legal"),
            Legality("vintage", "restricted"),
            Legality("legacy", "banned"),
            Legality("standard", "not_legal"),
        ),
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
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onRunSql = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The card page, reached the way a person reaches it. */
    private fun onACard(card: CardDetail = mixed()) =
        shell(AppState(route = Route(View.CARD, card.name), card = card))

    // ---------------------------------------------------------- reading

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    private fun chip(text: String) = rule.onNodeWithText(text, useUnmergedTree = true)

    private fun nothingSays(fragment: String) = rule
        .onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isEmpty()

    // ============================================== the chips' weight

    @Test
    fun everyLegalityChipTakesTheBodysWeightWhateverTheStatus() {
        onACard()
        // `.chip` sets `font: inherit` and never a weight, so all four
        // of these are the body's. Android gave `legal` Medium and
        // every other status SemiBold, which made "not legal" the
        // boldest thing in the row.
        val chips = mixed().legalityChips.map { it.chip }
        assertEquals(4, chips.size, "the fixture lost a status on the way to the row")
        chips.forEach { text ->
            val weight = chip(text).textLayout()?.layoutInput?.style?.fontWeight
            assertNotNull(weight, "\"$text\" is not on the card page at all")
            assertEquals(
                FontWeight.Normal,
                weight,
                "\"$text\" is drawn at ${weight.weight} where `.chip` takes the body's 400",
            )
        }
    }

    @Test
    fun aLegalityChipIsTheWebsElevenPixelsAndNotTwelve() {
        onACard()
        // `.chip.mini { font-size: 11px }`, and every legality chip on
        // the web carries `mini`.
        mixed().legalityChips.map { it.chip }.forEach { text ->
            assertEquals(
                Design.TINY.sp,
                chip(text).textLayout()?.layoutInput?.style?.fontSize,
                "\"$text\" is not at the size `.chip.mini` sets",
            )
        }
    }

    @Test
    fun aLegalityChipKeepsItsToneOnTheWordsAndTheWebsAirAroundThem() {
        onACard()
        val legal = mixed().legalityChips.first { it.legal }.chip
        val off = mixed().legalityChips.first { !it.legal && !it.banned && !it.restricted }.chip
        // `.chip.ok { color: var(--ok) }` and `.chip.off { color:
        // var(--text-3) }`: the tone is on the text, which is the one
        // part of a chip a JVM run can read back.
        assertEquals(Ok, chip(legal).textLayout()?.layoutInput?.style?.color, "a legal chip's words")
        assertEquals(Ink3, chip(off).textLayout()?.layoutInput?.style?.color, "a \"not legal\" chip's words")

        // `.chip.mini { padding: 2px 7px }` — two above and below,
        // where Android had three. Measured as the box around the
        // words rather than read off the modifier: the glyphs sit
        // inside the same node, so the difference between the node's
        // height and the line's is the padding plus the border.
        val box = rule.onNodeWithTag("legality-Commander", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val line = chip(legal).textLayout()!!.size.height / rule.density.density
        val vertical = (box.bottom.value - box.top.value - line) / 2f
        // `border` paints inside the box and takes no layout space of
        // its own, so this is the padding alone: two, where Android
        // had three.
        assertTrue(
            abs(vertical - 2f) < 0.6f,
            "a chip keeps ${vertical}dp above its words where `.chip.mini` sets 2px",
        )
    }


    @Test
    fun theMassEntryBoxOffersNoPlaceholderTheWebDoesNotOffer() {
        shell(
            AppState(
                route = Route(View.ENTRY),
                entry = MassEntry().choose(Direction.ADD).goTo(Step.LIST),
            ),
        )
        // The web's box has none: the panel head above it asks the
        // question and the tally below counts the lines.
        assertTrue(
            nothingSays("One card per line."),
            "the mass entry box still carries a placeholder the website does not",
        )
    }
}
