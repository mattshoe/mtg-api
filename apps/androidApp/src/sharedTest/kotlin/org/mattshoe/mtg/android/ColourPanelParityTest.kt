package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck page's COLOUR panel under its three rings, on an open deck
 * in a real `AppShell`.
 *
 * Matt: "Get rid of this text under the charts" and "I want the
 * 'exact' chart to be on its own line and bigger than the others".
 * The website's sibling is `DeckStatsLayoutTest`.
 */
@RunWith(AndroidJUnit4::class)
class ColourPanelParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(
        name: String,
        typeLine: String,
        manaCost: String? = null,
        cmc: Double? = 2.0,
        produces: String? = null,
        qty: Int = 1,
    ) = DeckCard(
        name,
        qty = qty,
        role = null,
        owned = qty,
        nameNorm = name.lowercase(),
        typeLine = typeLine,
        manaCost = manaCost,
        cmc = cmc,
        producedMana = produces,
        scryfallId = "abcdef12-3456",
    )

    /** Two colours wanted and made, and a dual, so every ring has slices. */
    private fun cards() = listOf(
        card("Plains", "Basic Land — Plains", cmc = 0.0, produces = "W", qty = 5),
        card("Island", "Basic Land — Island", cmc = 0.0, produces = "U", qty = 3),
        card("Hallowed Fountain", "Land", cmc = 0.0, produces = "WU", qty = 2),
        card("Counterspell", "Instant", manaCost = "{U}{U}"),
        card("Swords to Plowshares", "Instant", manaCost = "{W}", cmc = 1.0),
    )

    private fun shell() {
        val start = AppState(
            route = Route(View.DECKS, "alela"),
            admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
            decks = DecksState().loaded(listOf(deck())).opened("alela", cards()),
        )
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

    /** The drawn ring itself, not its caption or key. */
    private fun ring(caption: String) = rule
        .onNode(hasContentDescription("$caption · ", substring = true), useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    private fun says(fragment: String) = rule
        .onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theColourPanelHasNoCaptionUnderTheRings() {
        shell()
        assertTrue(says("Makes · "), "the colour panel never drew, so this proves nothing")
        assertTrue(!says("Pips the deck asks for"), "the caption about pips is still under the rings")
        assertTrue(!says("Hybrid pips count for both halves"), "the hybrid sentence is still under the rings")
    }

    @Test
    fun aSplashWithNoSourceIsStillSaid() {
        // The caption is gone; the warning it used to end with is not.
        val start = listOf(
            card("Plains", "Basic Land — Plains", cmc = 0.0, produces = "W"),
            card("Counterspell", "Instant", manaCost = "{U}{U}"),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = AppState(
                            route = Route(View.DECKS, "alela"),
                            admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
                            decks = DecksState().loaded(listOf(deck())).opened("alela", start),
                        ),
                        onState = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue(says("No source for Blue."), "a splash with no sources went unsaid")
    }

    @Test
    fun needsAndMakesShareARowAndExactlyHasALineOfItsOwnBelowThem() {
        shell()
        val needs = ring("Needs")
        val makes = ring("Makes")
        val exactly = ring("Exactly")
        assertEquals(needs.top, makes.top, "needs and makes are not in one row")
        assertTrue(
            exactly.top >= needs.bottom,
            "the Exactly ring starts at ${exactly.top}, beside needs and makes rather than below them (they end at ${needs.bottom})",
        )
    }

    @Test
    fun everyExactlySliceHasItsNumberDrawnInsideIt() {
        // Matt: "It's not at all clear which slice is which in the
        // exactly chart" and "put symbols on the slices". Five Plains,
        // three Islands, two Hallowed Fountains: white runs 0-180
        // degrees, blue 180-288 and WU 288-360.
        shell()
        val ring = ring("Exactly")
        val cx = (ring.left + ring.right) / 2
        val cy = (ring.top + ring.bottom) / 2
        val spans = listOf(0.0 to 180.0, 180.0 to 288.0, 288.0 to 360.0)
        spans.forEachIndexed { i, span ->
            val n = i + 1
            val found = rule.onAllNodes(hasTestTag("slice-no-$n"), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(found.isNotEmpty(), "slice $n has no number drawn on the ring")
            val mark = rule.onNode(hasTestTag("slice-no-$n"), useUnmergedTree = true).getUnclippedBoundsInRoot()
            val dx = ((mark.left + mark.right) / 2 - cx).value.toDouble()
            val dy = ((mark.top + mark.bottom) / 2 - cy).value.toDouble()
            val angle = (kotlin.math.atan2(dx, -dy) * 180 / kotlin.math.PI + 360) % 360
            assertTrue(
                angle > span.first && angle < span.second,
                "slice $n's number sits at $angle degrees, outside its slice ${span.first}-${span.second}",
            )
            assertTrue(
                kotlin.math.sqrt(dx * dx + dy * dy) < (ring.right - ring.left).value / 2,
                "slice $n's number is off the ring",
            )
        }
    }

    @Test
    fun aTableUnderTheExactlyRingSaysWhatEachNumberIs() {
        // Matt: "This needs a better legend ... perhaps a table".
        shell()
        val rows = (1..3).map { n ->
            val found = rule.onAllNodes(hasTestTag("exactly-row-$n"), useUnmergedTree = false).fetchSemanticsNodes()
            assertTrue(found.isNotEmpty(), "the Exactly ring has no table row for slice $n")
            found.single().config.getOrNull(SemanticsProperties.Text)
                .orEmpty().joinToString(" | ") { it.text }
        }
        assertEquals(
            listOf(
                // The letter under each symbol is read out with it.
                "1 | W | Mono-white | 5 | 50%",
                "2 | U | Mono-blue | 3 | 30%",
                "3 | W | U | Azorius | 2 | 20%",
            ),
            rows,
        )
        assertTrue(
            rule.onAllNodes(hasTestTag("pip-Exactly-WU-U"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the Azorius row does not show its symbols",
        )
    }

    @Test
    fun aCombinationSliceIsOneBlendedColour() {
        // Matt: "Slice 3 should be ONE COLOR. Not multiple colors. Do
        // whatever the color mix of the 2 colors is, like purple".
        // White F8F3E0 and blue 61A3DD mixed is ADCBDF.
        shell()
        val node = rule
            .onNode(hasContentDescription("Exactly · ", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
        val arcs = node.config.getOrNull(RingArcs)
        assertTrue(arcs != null, "the Exactly ring says nothing about what it paints")
        assertEquals(
            listOf(0xFFF8F3E0 to 180, 0xFF61A3DD to 108, 0xFFADCBDF to 72),
            arcs.map { it.colour to kotlin.math.round(it.sweep).toInt() },
            "the Azorius slice is not one blend of white and blue",
        )
    }

    /** Where the glyphs of [text] start and end, inside table row [n]. */
    private fun glyphs(n: Int, text: String): Pair<Float, Float> {
        val node = rule.onNode(
            hasText(text) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("exactly-row-$n")),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        // Not `boundsInRoot`: the panel is below the fold, where the
        // clipped bounds are all zero.
        val left = node.positionInRoot.x
        return (left + layout.getLineLeft(0)) to (left + layout.getLineRight(0))
    }

    @Test
    fun theExactlyTablesColumnsLineUp() {
        // Matt: "The table columns don't line up". Read off the laid
        // out glyphs: every name starts at one x, and every count and
        // every share ends at one x, whether the row has one symbol
        // before it or two.
        shell()
        val names = listOf(glyphs(1, "Mono-white"), glyphs(2, "Mono-blue"), glyphs(3, "Azorius")).map { it.first }
        val counts = listOf(glyphs(1, "5"), glyphs(2, "3"), glyphs(3, "2")).map { it.second }
        val shares = listOf(glyphs(1, "50%"), glyphs(2, "30%"), glyphs(3, "20%")).map { it.second }
        assertTrue(names.max() - names.min() < 1f, "the names start at different places: $names")
        assertTrue(counts.max() - counts.min() < 1f, "the counts end at different places: $counts")
        assertTrue(shares.max() - shares.min() < 1f, "the shares end at different places: $shares")
    }

    @Test
    fun theExactlyRingIsBigEnoughToReadItsNumbers() {
        // Matt: "You can make the chart bigger".
        shell()
        val exactly = ring("Exactly")
        val wide = (exactly.right - exactly.left).value
        assertTrue(wide >= 170f, "the Exactly ring is ${wide}dp across, no bigger than before")
    }

    @Test
    fun theExactlyRingIsBiggerThanTheOtherTwo() {
        shell()
        val needs = ring("Needs")
        val exactly = ring("Exactly")
        val wide = exactly.right - exactly.left
        val narrow = needs.right - needs.left
        assertTrue(
            wide > narrow * 1.25f,
            "the Exactly ring is $wide across and needs is $narrow, so it is not bigger",
        )
    }
}
