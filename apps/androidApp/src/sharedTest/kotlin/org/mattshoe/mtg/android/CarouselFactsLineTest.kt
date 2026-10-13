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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Type • {U}{G} • Rarity" under the carousel's name, the phone's half.
 *
 * Matt: "underneath the name text, i want to see 'Type • <<color
 * identity>> • Rarity' where color identity is just the mana symbols,
 * not the words". The website's `CarouselFactsLineTest` makes the
 * same claims in a real browser.
 */
@RunWith(AndroidJUnit4::class)
class CarouselFactsLineTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    /**
     * The fixture `DeckCardCarouselParityTest` already drives, so the
     * only thing new here is the line: Cultivate carries a type, an
     * identity and a rarity, and the others are as they were.
     */
    private fun card(name: String, type: String = "Artifact", identity: String? = null, rarity: String? = null) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 3,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
        colorIdentity = identity,
        rarity = rarity,
        price = 1.75,
        setCode = "m3c",
        setName = "Modern Horizons 3 Commander",
        collectorNumber = "409",
        edhrecRank = 412,
    )

    /** In `pageOrder`, which groups by type and sorts by name inside a group. */
    private val run = listOf(
        card("Counterspell"),
        card("Cultivate", type = "Kindred Sorcery — Elf", identity = "UG", rarity = "common"),
        // Stored as the Worker stores it: a colourless identity is NULL.
        card("Sol Ring", rarity = "uncommon"),
    )

    private fun peeking(name: String = "Cultivate") {
        val start = AppState(admin = Admin().signIn(Account("matt"), "t"))
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                        .opened("alela", run),
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
                        onTweak = { _, _ -> },
                        onOpenPeeked = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val row = rule.onNodeWithText(name)
        runCatching { row.performScrollTo() }
        row.performClick()
        rule.waitForIdle()
        assertEquals(name, held.value.peeked?.title, "the row did not open the carousel on $name")
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
        val g = onTheLine("carousel-pip-G")
        type.assertTextEquals("Sorcery")
        rarity.assertTextEquals("Common")
        assertTrue(
            rule.onAllNodes(hasText("Blue") and hasAnyAncestor(hasTestTag("carousel-facts"))).fetchSemanticsNodes().isEmpty(),
            "the identity is spelled out in words",
        )
        val x = listOf(type, u, g, rarity).map { it.getUnclippedBoundsInRoot().left }
        assertEquals(x.sortedBy { it.value }, x, "the parts are not type, {U}, {G}, rarity from left to right")
    }

    @Test
    fun aColourlessCardShowsTheColourlessSymbol() {
        // Matt: "colorless cards aren't showing colorless pip".
        peeking("Sol Ring")
        assertTrue(exists("carousel-fact-0"), "there is no line under the name at all")
        onTheLine("carousel-fact-0").assertTextEquals("Artifact")
        assertTrue(exists("carousel-pip-C"), "a colourless card has no colourless symbol")
        onTheLine("carousel-fact-2").assertTextEquals("Uncommon")
    }

    @Test
    fun theLineSitsDirectlyUnderTheName() {
        peeking()
        val name = onTheSheet("Cultivate").getUnclippedBoundsInRoot()
        val line = rule.onAllNodes(hasTestTag("carousel-facts"))[0].getUnclippedBoundsInRoot()
        val printing = onTheSheet("M3C · 409 · $1.75").getUnclippedBoundsInRoot()
        assertTrue(line.top >= name.bottom - Dp(0.5f), "the line (top ${line.top}) is not under the name (bottom ${name.bottom})")
        assertTrue(line.bottom <= printing.top + Dp(0.5f), "the printing (top ${printing.top}) comes before the line (bottom ${line.bottom})")
    }

    private fun SemanticsNodeInteraction.assertTextEquals(want: String) {
        val got = fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.joinToString("") { it.text }
        assertEquals(want, got)
    }
}
