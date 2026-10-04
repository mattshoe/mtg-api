package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Face
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page says what the card does (3.2).
 *
 * It did not. `CardDetail` carried printings, decks, legalities and
 * rulings and nothing about the card itself, so opening Sol Ring told
 * you which sets it was in and which decks wanted it and never that
 * it taps for two colourless. `app.css` had `.oracle` and `.flavor`
 * rules with nothing using them, which is the clearest possible sign
 * that this was dropped rather than decided.
 */
class CardFaceTest {

    private val roots = mutableListOf<HTMLElement>()

    // `pre-wrap` is a real rule in `app.css`, so the real stylesheet
    // has to be in the document for `getComputedStyle` to see it.
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
    fun theCardSaysWhatItDoes() = runTest {
        val root = open(solRing())
        settle()
        val text = root.textContent!!
        assertTrue(text.contains("Artifact"), "no type line")
        assertTrue(text.contains("{T}: Add {C}{C}."), "no oracle text")
        assertTrue(text.contains("The ring is a tool"), "no flavour text")
        // The cost is drawn as symbols, not written as "{1}" — the
        // same `ManaCostRow` the rest of the site uses.
        assertEquals(
            1,
            root.all(".card-face .mana-cost .mana-sym").size,
            "the mana cost is not symbols",
        )
    }

    @Test
    fun theRulesTextKeepsItsLineBreaks() = runTest {
        val card = CardDetail(
            name = "Two Abilities",
            faces = listOf(Face(oracleText = "Flying\nVigilance", typeLine = "Creature")),
        )
        val root = open(card)
        settle()
        val oracle = root.all(".oracle").single()
        // One ability per line is how a card is read. `pre-wrap` is
        // what keeps the newline, and the text goes in unmangled.
        assertTrue(oracle.textContent!!.contains("\n"), "the newline was collapsed")
        assertEquals("pre-wrap", window.getComputedStyle(oracle).whiteSpace)
    }

    @Test
    fun aCreatureShowsItsPowerAndToughness() = runTest {
        val root = open(dfc())
        settle()
        val boxes = root.all(".ptbox")
        assertEquals(1, boxes.size, "only the creature face has a stat box")
        assertEquals("3/2", boxes[0].textContent)
    }

    @Test
    fun aDoubleFacedCardShowsBothFacesNamed() = runTest {
        val root = open(dfc())
        settle()
        assertEquals(2, root.all(".card-face").size, "both faces are not drawn")
        assertEquals(
            listOf("Adventurous Eater", "Have a Bite"),
            root.all(".face-name").map { it.textContent },
            "the faces are not named, so there is no telling which is which",
        )
    }

    @Test
    fun aSingleFacedCardDoesNotRepeatItsOwnNameInsideThePanel() = runTest {
        val root = open(solRing())
        settle()
        // The page title already says it. Naming the face as well is
        // the same word twice for no reason.
        assertEquals(0, root.all(".face-name").size)
    }

    @Test
    fun aCardWithNothingLoadedDrawsNoEmptyPanel() = runTest {
        val root = open(CardDetail(name = "Sol Ring"))
        settle()
        assertEquals(0, root.all(".card-face").size, "an empty bordered box was drawn")
    }
}
