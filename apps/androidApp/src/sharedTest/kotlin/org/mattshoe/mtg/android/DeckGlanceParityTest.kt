package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
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
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The top of the deck at a glance, through the real shell.
 *
 * Matt, after the borders came off and nothing else changed: "i asked
 * you to make the fucking at a glance view more stylish and attractive
 * and you just removed the borders!!!!". The numbers were one ragged
 * row at one size, wrapping wherever they ran out of room. Now they
 * are laid out: the cards and their worth big, lands against spells as
 * one split bar, and the mana value's average, median and mode in
 * three equal columns. On a phone and both tablet sizes. The website's
 * sibling is `DeckGlanceTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeckGlanceParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(name: String, type: String, cost: String?, cmc: Double, qty: Int, role: String? = null) =
        DeckCard(
            name, qty, role, qty,
            nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc, colorIdentity = "G",
            price = 1.0,
        )

    /** 10 lands and 8 spells, the spells at 1, 1, 2, 2, 2, 2, 2, 3: average 1.88, median 2, mode 2. */
    private fun opened() = AppState(
        route = Route(View.DECKS, "a"),
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState()
            .loaded(listOf(Deck("a", "Gruul", "matt", "Leader (KHC) 2", "RG", 18, null)))
            .opened(
                "a",
                listOf(
                    card("Leader", "Legendary Creature — Elf", "{2}{G}", 3.0, 1, role = "commander"),
                    card("Bear", "Creature — Bear", "{1}{G}", 2.0, 3),
                    card("Bolt", "Instant", "{R}", 1.0, 2),
                    card("Gold", "Sorcery", "{R}{G}", 2.0, 1),
                    card("Rock", "Artifact", "{2}", 2.0, 1),
                    card("Forest", "Basic Land — Forest", null, 0.0, 10),
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

    private fun figure(told: String) =
        rule.onAllNodes(hasContentDescription(told), useUnmergedTree = true).onFirst()

    private fun box(told: String): DpRect = figure(told).performScrollTo().getUnclippedBoundsInRoot()

    private fun tagged(t: String) = rule.onAllNodes(hasTestTag(t), useUnmergedTree = true)

    /** The size the figure's number is set in, read back off its laid-out text. */
    private fun numberSize(told: String): Float {
        val node = figure(told).onChildren().onFirst().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.fontSize.value
    }

    private fun DpRect.w() = (right - left).value

    private fun check(size: DpSize?) {
        shell(size)
        val screen = rule.onRoot().getUnclippedBoundsInRoot()

        // Lands against spells, one bar split by share.
        assertTrue(
            tagged("glance-split").fetchSemanticsNodes().isNotEmpty(),
            "no bar splitting the deck's lands from its spells",
        )
        tagged("glance-split").onFirst().performScrollTo()
        val bar = tagged("glance-split").onFirst().getUnclippedBoundsInRoot()
        val spells = tagged("glance-split-spells").onFirst().getUnclippedBoundsInRoot()
        val share = spells.w() / bar.w()
        assertTrue(
            abs(share - 8f / 18f) < 0.02f,
            "the spells take ${share * 100}% of the split bar, not 8 of 18 cards",
        )

        // Avg, median and mode: three equal columns on one line.
        val trio = listOf("1.88 avg mana", "2 median mana", "2 mode mana").map { it to box(it) }
        val (first, a) = trio.first()
        trio.drop(1).forEach { (told, b) ->
            assertTrue(abs(b.top.value - a.top.value) < 1f, "\"$told\" is not on the line with \"$first\"")
            assertTrue(
                abs(b.w() - a.w()) < 2f, // weights hand a pixel of remainder to one cell
                "\"$told\" is ${b.w()}dp wide and \"$first\" ${a.w()}dp: a ragged row, not three columns",
            )
        }
        val spread = trio.last().second.right.value - a.left.value
        assertTrue(
            spread > bar.w() * 0.9f,
            "the mana trio spans ${spread}dp of a ${bar.w()}dp panel: bunched to one side, not laid across it",
        )

        // The cards and their worth are the headline, so they are big.
        val big = numberSize("18 cards")
        val mana = numberSize("1.88 avg mana")
        assertTrue(
            big > mana * 1.25f,
            "the deck's card count is set at ${big}sp beside the average's ${mana}sp: nothing leads",
        )
        assertTrue(numberSize("\$18 value") == big, "the deck's value is not set as big as its card count")

        (trio.map { it.first } + listOf("18 cards", "\$18 value", "8 spells", "10 lands · 56%")).forEach { told ->
            val b = box(told)
            assertTrue(b.right <= screen.right, "\"$told\" runs off the right of the screen, ${b.right} past ${screen.right}")
        }
    }

    @Test
    fun theGlanceIsLaidOutOnAPhone() = check(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theGlanceOnAFoldingPhoneOpenedOut() = check(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theGlanceOnATabletHeldSideways() = check(Tablet.LANDSCAPE)
}
