package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Face
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's card page says what the card does (3.2).
 *
 * Sibling of `CardFaceTest` on the web, asserting the same sentences.
 * Neither platform showed any of this: `CardDetail` carried printings,
 * decks, legalities and rulings and nothing about the card, so opening
 * Sol Ring said which sets it was in and never that it taps for two
 * colourless.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CardFaceParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun open(card: CardDetail) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Box(Modifier.width(340.dp)) { CardSheet(card) {} } }
            }
        }
        rule.waitForIdle()
    }

    private fun says(text: String) =
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()

    private fun textOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

    private fun solRing() = CardDetail(
        name = "Sol Ring",
        faces = listOf(
            Face(
                name = "Sol Ring",
                manaCost = "{1}",
                typeLine = "Artifact",
                oracleText = "{T}: Add {C}{C}.",
                flavorText = "The ring is a tool, not a crown.",
            ),
        ),
    )

    private fun dfc() = CardDetail(
        name = "Adventurous Eater // Have a Bite",
        faces = listOf(
            Face(
                name = "Adventurous Eater",
                manaCost = "{2}{B}",
                typeLine = "Creature — Human Warlock",
                oracleText = "When this creature enters, mill two cards.",
                power = "3",
                toughness = "2",
            ),
            Face(
                name = "Have a Bite",
                manaCost = "{B}",
                typeLine = "Sorcery",
                oracleText = "Target creature gets -2/-2 until end of turn.",
            ),
        ),
    )

    @Test
    fun theCardSaysWhatItDoes() {
        open(solRing())
        assertTrue(says("Artifact"), "no type line")
        assertTrue(says("{T}: Add {C}{C}."), "no oracle text")
        assertTrue(says("The ring is a tool"), "no flavour text")
    }

    @Test
    fun theCostIsDrawnAsSymbols() {
        open(solRing())
        // `ManaSymbol` describes itself by `Pip.label`. `{1}` is
        // generic, so the symbol it draws is the numeral itself —
        // what matters is that something drew it rather than the
        // string "{1}" appearing as text in the type line's place.
        assertTrue(
            rule.onAllNodesWithContentDescription("1").fetchSemanticsNodes().isNotEmpty(),
            "the mana cost was not drawn as a symbol",
        )
    }

    @Test
    fun theRulesTextArrivesWholeWithItsLineBreaks() {
        open(
            CardDetail(
                name = "Two Abilities",
                faces = listOf(Face(oracleText = "Flying\nVigilance", typeLine = "Creature")),
            ),
        )
        // One ability per line is how a card is read. The string goes
        // in unmangled and Compose wraps it; nothing collapses the
        // newline on the way.
        assertEquals("Flying\nVigilance", textOf("oracle"))
    }

    @Test
    fun aCreatureShowsItsPowerAndToughness() {
        open(dfc())
        assertEquals(1, tagged("ptbox").size, "only the creature face has a stat box")
        assertEquals("3/2", textOf("ptbox"))
    }

    @Test
    fun aDoubleFacedCardShowsBothFacesNamed() {
        open(dfc())
        assertTrue(says("Adventurous Eater"), "the front face is not named")
        assertTrue(says("Have a Bite"), "the back face is not named")
        assertTrue(says("Sorcery"), "the back face's type line is missing")
    }

    @Test
    fun aCardWithNothingLoadedDrawsNoEmptyPanel() {
        open(CardDetail(name = "Sol Ring"))
        assertTrue(tagged("oracle").isEmpty(), "an empty face panel was drawn")
        assertTrue(tagged("ptbox").isEmpty())
    }
}
