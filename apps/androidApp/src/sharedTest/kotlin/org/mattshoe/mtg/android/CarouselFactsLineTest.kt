package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Type • {U}{B} • Rarity" under the carousel's name, the phone's half.
 *
 * Matt: "underneath the name text, i want to see 'Type • <<color
 * identity>> • Rarity' where color identity is just the mana symbols,
 * not the words". The website's `CarouselFactsLineTest` makes the
 * same claims in a real browser.
 */
@RunWith(AndroidJUnit4::class)
class CarouselFactsLineTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val alela = DeckCard(
        name = "Alela, Cunning Conqueror",
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = "alela, cunning conqueror",
        typeLine = "Legendary Creature — Faerie Warlock",
        scryfallId = "abcdef12-3456",
        colorIdentity = "UB",
        rarity = "rare",
        price = 1.75,
        setCode = "woe",
        collectorNumber = "1",
    )

    private fun peeking() {
        val start = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UB", 1, null)))
                        .opened("alela", listOf(alela)),
                )
            }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
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
        val row = rule.onAllNodes(hasText("Alela, Cunning Conqueror"))[0]
        runCatching { row.performScrollTo() }
        row.performClick()
        rule.waitForIdle()
        assertTrue(held.value.peeked != null, "the row did not open the carousel")
    }

    private fun onTheSheet(text: String): SemanticsNodeInteraction =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("carousel-sheet")))[0]

    private fun onTheLine(tag: String): SemanticsNodeInteraction =
        rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("carousel-facts")))[0]

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("carousel-facts")))
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theLineReadsTypeThenIdentitySymbolsThenRarity() {
        peeking()
        assertTrue(exists("carousel-fact-0"), "there is no line under the name at all")
        val type = onTheLine("carousel-fact-0")
        val rarity = onTheLine("carousel-fact-2")
        val u = onTheLine("carousel-pip-U")
        val b = onTheLine("carousel-pip-B")
        type.assertTextEquals("Legendary Creature — Faerie Warlock")
        rarity.assertTextEquals("Rare")
        assertTrue(
            rule.onAllNodes(hasText("Blue") and hasAnyAncestor(hasTestTag("carousel-facts"))).fetchSemanticsNodes().isEmpty(),
            "the identity is spelled out in words",
        )
        val x = listOf(type, u, b, rarity).map { it.getUnclippedBoundsInRoot().left }
        assertEquals(x.sortedBy { it.value }, x, "the parts are not type, {U}, {B}, rarity from left to right")
    }

    @Test
    fun theLineSitsDirectlyUnderTheName() {
        peeking()
        val name = onTheSheet("Alela, Cunning Conqueror").getUnclippedBoundsInRoot()
        val line = rule.onAllNodes(hasTestTag("carousel-facts"))[0].getUnclippedBoundsInRoot()
        val printing = onTheSheet("WOE · 1 · $1.75").getUnclippedBoundsInRoot()
        assertTrue(line.top >= name.bottom - Dp(0.5f), "the line (top ${line.top}) is not under the name (bottom ${name.bottom})")
        assertTrue(line.bottom <= printing.top + Dp(0.5f), "the printing (top ${printing.top}) comes before the line (bottom ${line.bottom})")
    }

    private fun SemanticsNodeInteraction.assertTextEquals(want: String) {
        val got = fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.joinToString("") { it.text }
        assertEquals(want, got)
    }
}
