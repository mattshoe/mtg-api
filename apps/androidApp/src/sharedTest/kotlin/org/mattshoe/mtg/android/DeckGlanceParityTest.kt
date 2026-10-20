package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
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
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The deck at a glance's headline numbers, through the real shell.
 *
 * Matt: "i don't like this 'tile in tile' layout in the deck at a
 * glance section ... i want it to be sleek and stylish". Every number
 * sat in a bordered box of its own inside the bordered panel. Now the
 * numbers stand on the panel itself. And: "i also want to see median,
 * mode for mana cost". On a phone and both tablet sizes. The website's
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

    /** Spells at 1, 1, 2, 2, 2, 2, 2, 3: average 1.88, median 2, mode 2. */
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

    private fun shows(told: String): Boolean =
        rule.onAllNodes(hasContentDescription(told), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun check(size: DpSize?) {
        shell(size)
        standOnThePanel(listOf("18 cards", "10 lands · 56%", "8 spells", "1.88 avg mana"))
        val wanted = listOf("2 median mana", "2 mode mana")
        wanted.forEach { assertTrue(shows(it), "no \"$it\" among the deck's headline numbers") }
        standOnThePanel(wanted)
    }

    private fun standOnThePanel(figures: List<String>) {
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        figures.forEach { told ->
            val f = figure(told).performScrollTo()
            val box = f.getUnclippedBoundsInRoot()
            val number = f.onChildren().onFirst().getUnclippedBoundsInRoot()
            // A figure with a frame of its own pads its number in from
            // the frame's edge. One standing on the panel has no inset.
            val inset = maxOf(abs(number.left.value - box.left.value), abs(number.top.value - box.top.value))
            assertTrue(
                inset < 1f,
                "\"$told\" is a tile inside the panel: its number sits ${inset}dp inside a frame of its own",
            )
            assertTrue(
                box.right <= screen.right,
                "\"$told\" runs off the right of the screen, ${box.right} past ${screen.right}",
            )
        }
    }

    @Test
    fun theHeadlineNumbersStandOnThePanelAndIncludeMedianAndMode() = check(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theHeadlineNumbersOnAFoldingPhoneOpenedOut() = check(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theHeadlineNumbersOnATabletHeldSideways() = check(Tablet.LANDSCAPE)
}
