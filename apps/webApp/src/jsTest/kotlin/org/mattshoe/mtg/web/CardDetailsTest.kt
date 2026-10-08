package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardFacts
import org.mattshoe.mtg.core.DeckUse
import org.mattshoe.mtg.core.Printing
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page: every field the database has, and nobody's copies.
 *
 * Matt: "the card details page still has a ton of missing fields from
 * the database. I DO NOT want ownership information on the cards
 * details. But i do need to keep deck membership."
 *
 * Sibling of `CardDetailsParityTest` on the phone, asserting the same
 * sentences.
 */
class CardDetailsTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun open(card: CardDetail): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { CardPage(card) }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

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

    /** The label beside each value in the Details section, as drawn. */
    private fun HTMLElement.facts(): Map<String, String> = all(".fact").associate { row ->
        (row.querySelector(".fact-label") as HTMLElement).textContent.orEmpty() to
            (row.querySelector(".fact-value") as HTMLElement).textContent.orEmpty()
    }

    @Test
    fun everyFieldTheDatabaseHasIsOnThePage() = runTest {
        val page = open(signet())
        settle()
        val drawn = page.facts()
        assertEquals("#3", drawn["EDHREC rank"], "no EDHREC rank on the page: $drawn")
        assertEquals("2", drawn["Mana value"], "no mana value: $drawn")
        assertEquals("White, Blue, Black, Red, Green", drawn["Produces"], "nothing says what it taps for: $drawn")
        assertEquals("Common", drawn["Rarity"], "no rarity: $drawn")
        assertEquals("Ioannis Fiore", drawn["Artist"], "no artist: $drawn")
        assertEquals("Game changer", drawn["Flags"], "the game changer flag is not said: $drawn")
        assertTrue(page.textContent.orEmpty().contains("Details"), "the section has no heading")
    }

    @Test
    fun eachFactIsALabelBesideItsValueNotARunOnSentence() = runTest {
        val page = open(signet())
        settle()
        val row = page.all(".fact").firstOrNull() ?: error("no fact rows drawn")
        val label = (row.querySelector(".fact-label") as HTMLElement).getBoundingClientRect()
        val value = (row.querySelector(".fact-value") as HTMLElement).getBoundingClientRect()
        assertTrue(value.left >= label.right - 1, "the value is not beside its label: ${label.right} vs ${value.left}")
        assertTrue(kotlin.math.abs(value.top - label.top) < 4, "the value is not on the label's line")
    }

    @Test
    fun nothingOnThePageSaysWhoOwnsItOrHowMany() = runTest {
        val page = open(signet())
        settle()
        val text = page.textContent.orEmpty()
        listOf("Who owns it", "owned", "free", "Kayla", "6×", "2×").forEach {
            assertTrue(it !in text, "the page still carries ownership: \"$it\" in $text")
        }
    }

    @Test
    fun theSamePrintingOwnedTwiceIsListedOnce() = runTest {
        val page = open(signet())
        settle()
        assertEquals(1, page.all(".print-line").size, "one printing is drawn as ${page.all(".print-line").size} lines")
    }

    @Test
    fun deckMembershipStays() = runTest {
        val page = open(signet())
        settle()
        val text = page.textContent.orEmpty()
        assertTrue("In decks" in text, "the decks section is gone")
        assertTrue("Alela" in text, "the deck that wants it is gone")
    }
}
