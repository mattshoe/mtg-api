package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertTrue

/**
 * The deck hero, read through the real shell.
 *
 * Section 5 of the audit: the website leads the hero with the
 * commander and folds the bracket, the colours and the card count into
 * the small line under it. Android led with the deck's own title —
 * which the header you just tapped already says — demoted the
 * commander into the small line, and dropped the colours and the count
 * altogether. Matt's ruling on the design differences was "match the
 * web exactly", so the hierarchy is the web's now.
 *
 * Through `AppShell` over a real open deck rather than `DecksScreen`
 * on its own, because `DecksParityTest` mounts the screen directly and
 * a hero is exactly the kind of thing that reads fine in isolation and
 * wrong in place.
 */
@RunWith(AndroidJUnit4::class)
class DeckHeroParityTest {

    @get:Rule
    val rule = createComposeRule()

    // --------------------------------------------------------- fixtures

    /**
     * A deck whose own name and whose commander are different words,
     * so "which one is the big line" is answerable at all. A fixture
     * where the deck is called "Alela" and the commander is "Alela,
     * Artful Provocateur" cannot tell them apart by prefix.
     */
    private fun deck() = Deck(
        "alela", "Faerie Swarm", "matt",
        "Alela, Artful Provocateur (ELD) 324", "UW", 3, null,
    )

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 2, role = role, owned = 2,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    /** Three cards at two copies each: six, which no other number here is. */
    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin(token = "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(
                card("Alela, Artful Provocateur", "commander"),
                card("Sol Ring"),
                card("Rhystic Study"),
            ),
        ),
    )

    // ---------------------------------------------------------- harness

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
                        onUnlock = {},
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

    private fun says(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun described(d: String) =
        rule.onAllNodesWithContentDescription(d).fetchSemanticsNodes().size

    // ------------------------------------------------------------ tests

    @Test
    fun theCommanderIsTheBigLineAndTheDeckNameIsNotRepeated() {
        shell()
        // The commander's own name, set as the hero's lead.
        assertTrue(says("Alela, Artful Provocateur"), "the hero does not name the commander")

        // The hero's own name node, by tag: the commander is also a
        // row in the list below, so "the node with this text" is two
        // nodes and neither answer would mean anything.
        val who = rule.onNodeWithTag("deck-hero-who").getUnclippedBoundsInRoot()
        val what = rule.onNodeWithTag("deck-hero-what").getUnclippedBoundsInRoot()
        assertTrue(
            rule.onNodeWithTag("deck-hero-who").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("")
                ?.contains("Alela, Artful Provocateur") == true,
            "the hero's big line is not the commander",
        )
        // The lead sits above the small line, which is what makes it
        // the lead rather than merely present.
        assertTrue(
            who.top.value < what.top.value,
            "the commander is not above the small line — it is still the demoted half",
        )
        // And the deck's own name is gone from the hero, deliberately.
        // The website never repeats it here: the header you tapped to
        // arrive already said it, and the hero is for the card the
        // deck is built around.
        assertTrue(
            rule.onNodeWithTag("deck-hero-who").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") != "Faerie Swarm",
            "the hero still leads with the deck's own name",
        )
    }

    @Test
    fun theSmallLineCarriesTheBracketTheColoursAndTheCount() {
        shell()
        assertTrue(says("Bracket 3"), "the hero dropped the bracket")
        assertTrue(says("6 cards"), "the hero dropped the card count — the web has always had it")
        // Two colours in the identity, as real mana symbols. The web
        // prints the letters; Android draws the pips, which is what
        // Matt asked for everywhere colours appear.
        // `ManaSymbol` describes itself by `Pip.label`, not by the
        // letter — the letter is only the fallback glyph drawn under
        // the artwork, and "W" as a substring matches half the screen.
        assertTrue(described("White") >= 1, "no white pip in the hero")
        assertTrue(described("Blue") >= 1, "no blue pip in the hero")
    }

    @Test
    fun theCountIsOfCopiesRatherThanOfNames() {
        shell()
        // Three cards at two copies each. A hero saying "3 cards" is
        // counting rows in a table, not cards in a deck.
        assertTrue(says("6 cards"), "the count is of distinct names, not of physical cards")
        assertTrue(!says("3 cards"), "the hero counted rows instead of copies")
    }

    @Test
    fun aLongCommanderNameIsClampedRatherThanPushingTheSmallLineOut() {
        shell()
        // `-webkit-line-clamp: 2` on the web. The hero is a fixed
        // height, so an unclamped three-line name pushes the bracket
        // and the count off the bottom of the band — which is a
        // regression you only see on a deck with a long commander, and
        // there are plenty.
        val hero = rule.onNodeWithTag("deck-hero-who").getUnclippedBoundsInRoot()
        val tall = hero.bottom.value - hero.top.value
        assertTrue(
            tall <= 2 * 16 * 1.5f + 2f,
            "the commander name is not clamped to two lines: ${tall}dp",
        )
    }
}
