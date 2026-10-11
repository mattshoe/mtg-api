package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * The deck's creature types, through the real shell over an open deck.
 *
 * Matt: "a human warrior would add 1 to human and 1 to warrior. There
 * would be no 'human warrior' type". Most common first. The website's
 * sibling is `DeckCreatureTypesTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeckCreatureTypesParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun card(name: String, type: String, qty: Int) = DeckCard(
        name, qty, null, qty,
        nameNorm = name.lowercase(), typeLine = type, manaCost = "{1}{G}", cmc = 2.0, colorIdentity = "G",
    )

    /** Elf 6, Druid 4, Warrior 3, Human 1: no two alike, so order and scale both mean something. */
    private fun opened() = AppState(
        route = Route(View.DECKS, "a"),
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState()
            .loaded(listOf(Deck("a", "Elves", "matt", "Lathril, Blade of the Elves (KHC) 2", "BG", 3, null)))
            .opened(
                "a",
                listOf(
                    card("Elf Druid", "Creature — Elf Druid", 4),
                    card("Elf Warrior", "Creature — Elf Warrior", 2),
                    card("Human Warrior", "Creature — Human Warrior", 1),
                    card("Forest", "Basic Land — Forest", 10),
                ),
            ),
    )

    private fun shell() {
        val start = opened()
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

    private fun exists(t: String): Boolean =
        rule.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun eachCreatureTypeIsItsOwnBarMostCommonFirst() {
        shell()
        assertTrue(
            rule.onAllNodes(hasText("CREATURE TYPES"), true).fetchSemanticsNodes().isNotEmpty(),
            "the deck page has no Creature types chart",
        )
        val order = listOf("Elf", "Druid", "Warrior", "Human")
        order.forEach { assertTrue(exists("tribe-$it"), "no bar for $it") }
        assertTrue(!exists("tribe-Human Warrior"), "\"Human Warrior\" is a bar of its own, not two types")
        val tops = order.map { bounds("tribe-$it").top.value }
        assertEquals(tops.sorted(), tops, "the types are not drawn most common first: $order at $tops")
    }

    @Test
    fun eachBarIsToScaleAgainstTheMostCommonType() {
        shell()
        val widths = listOf("Elf" to 6, "Druid" to 4, "Warrior" to 3, "Human" to 1)
            .map { (t, n) -> Triple(t, n, bounds("tribe-$t").let { it.right.value - it.left.value }) }
        val full = widths.first().third
        assertTrue(full > 20f, "the longest bar is only ${full}dp")
        widths.drop(1).forEach { (t, n, w) ->
            val want = full * n / 6f
            assertTrue(kotlin.math.abs(w - want) < 2f, "$t drew ${w}dp, wanted ${want}dp")
        }
    }
}
