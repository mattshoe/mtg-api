package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardFacts
import org.mattshoe.mtg.core.DeckUse
import org.mattshoe.mtg.core.Printing
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page: every field the database has, and nobody's copies.
 *
 * Matt: "the card details page still has a ton of missing fields from
 * the database. I DO NOT want ownership information on the cards
 * details. But i do need to keep deck membership."
 *
 * Sibling of `CardDetailsTest` on the web, asserting the same
 * sentences. Mounted the way `CardSheetParityTest` mounts the sheet:
 * it is handed a `CardDetail` and touches nothing else.
 */
@RunWith(AndroidJUnit4::class)
class CardDetailsParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

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

    private fun count(text: String, anywhere: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = anywhere), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun seen(text: String) = count(text) > 0

    /** Arcane Signet, two people's copies of it, and a deck of each. */
    private fun signet() = CardDetail(
        name = "Arcane Signet",
        nameNorm = "arcane signet",
        printings = listOf(
            Printing(1, "blc", "Bloomburrow Commander", "127", "nonfoil", 6, null, owner = "k4yl4", ownerName = "Kayla", price = 0.5),
            Printing(2, "blc", "Bloomburrow Commander", "127", "nonfoil", 2, null, owner = "m4tt", ownerName = "Matt", price = 0.5),
        ),
        usedIn = listOf(DeckUse("alela", "Alela", "m4tt", 1, null, false, ownerName = "Matt")),
        facts = CardFacts(
            mapOf(
                "edhrec_rank" to "3", "cmc" to "2.0", "produced_mana" to "BGRUW",
                "rarity" to "common", "setcode" to "blc", "set_name" to "Bloomburrow Commander",
                "artist" to "Ioannis Fiore", "game_changer" to "1", "reserved" to "0",
            ),
        ),
    )

    /** The label and its value sit on one line, the value to the right. */
    private fun beside(label: String, value: String): Boolean {
        if (!seen(label) || !seen(value)) return false
        val l = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val v = rule.onNodeWithText(value, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return v.left >= l.right - 1.dp && kotlin.math.abs((v.top - l.top).value) < 4f
    }

    @Test
    fun everyFieldTheDatabaseHasIsOnThePage() {
        open(signet())
        assertTrue(seen("Details"), "the section has no heading")
        assertTrue(beside("EDHREC rank", "#3"), "no EDHREC rank beside its label")
        assertTrue(beside("Mana value", "2"), "no mana value beside its label")
        assertTrue(beside("Produces", "White, Blue, Black, Red, Green"), "nothing says what it taps for")
        assertTrue(beside("Rarity", "Common"), "no rarity")
        assertTrue(beside("Artist", "Ioannis Fiore"), "no artist")
        assertTrue(beside("Flags", "Game changer"), "the game changer flag is not said")
    }

    @Test
    fun nothingOnThePageSaysWhoOwnsItOrHowMany() {
        open(signet())
        listOf("Who owns it", "owned", "free", "Kayla", "6×", "2×").forEach {
            assertEquals(0, count(it, anywhere = true), "the page still carries ownership: \"$it\"")
        }
    }

    @Test
    fun theSamePrintingOwnedTwiceIsListedOnce() {
        open(signet())
        assertEquals(1, count("Bloomburrow Commander"), "one printing is drawn more than once")
    }

    @Test
    fun deckMembershipStays() {
        open(signet())
        assertTrue(seen("In decks"), "the decks section is gone")
        assertTrue(seen("Alela"), "the deck that wants it is gone")
    }
}
