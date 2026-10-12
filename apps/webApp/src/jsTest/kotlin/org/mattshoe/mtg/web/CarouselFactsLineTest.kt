package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Type • {U}{B} • Rarity" under the carousel's name, the website's half.
 *
 * Matt: "underneath the name text, i want to see 'Type • <<color
 * identity>> • Rarity' where color identity is just the mana symbols,
 * not the words". The phone's `CarouselFactsLineTest` makes the same
 * claims through its `AppShell`.
 */
class CarouselFactsLineTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

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

    /** Stored as the Worker stores it: a colourless identity is NULL. */
    private val solRing = DeckCard(
        name = "Sol Ring",
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = "sol ring",
        typeLine = "Artifact",
        scryfallId = "abcdef12-0000",
        colorIdentity = null,
        rarity = "uncommon",
        price = 1.75,
        setCode = "c21",
        collectorNumber = "263",
    )

    private suspend fun peeking(card: DeckCard = alela) {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        val start = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt0001", null, "UB", 1, null)))
                        .opened("alela", listOf(card)),
                )
            }
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(start) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
        settle()
        document.querySelectorAll(".deck-line").let { l -> (0 until l.length).map { l[it] as HTMLElement } }
            .first { it.textContent?.contains(card.name.substringBefore(",")) == true }
            .click()
        settle()
        assertNotNull(held.value.peeked, "the carousel did not open")
    }

    private fun el(sel: String): HTMLElement =
        assertNotNull(document.querySelector(sel) as? HTMLElement, "nothing on the page matches $sel")

    @Test
    fun theLineReadsTypeThenIdentitySymbolsThenRarity() = runTest {
        peeking()
        val line = el(".peek-sheet .peek-facts")
        // Spacing is the stylesheet's, so it is taken out before comparing.
        val words = line.textContent.orEmpty().replace(Regex("\\s+"), "")
        assertEquals("Creature••Rare", words, "the words on the line are wrong")
        val pips = line.querySelectorAll("img.mana-sym").let { l -> (0 until l.length).map { l[it] as HTMLImageElement } }
        assertEquals(listOf("{U}", "{B}"), pips.map { it.alt }, "the identity is not drawn as its symbols, in order")
        assertTrue(!words.contains("Blue") && !words.contains("Black"), "the identity is spelled out in words")
        val rects = pips.map { it.getBoundingClientRect() }
        assertTrue(rects.all { it.width > 0 && it.height > 0 }, "a symbol has no size")
        assertTrue(rects[0].right <= rects[1].left + 0.5, "the symbols are not side by side, left to right")
    }

    @Test
    fun aColourlessCardShowsTheColourlessSymbol() = runTest {
        // Matt: "colorless cards aren't showing colorless pip".
        peeking(solRing)
        val line = el(".peek-sheet .peek-facts")
        assertEquals("Artifact••Uncommon", line.textContent.orEmpty().replace(Regex("\\s+"), ""), "the words on the line are wrong")
        val pips = line.querySelectorAll("img.mana-sym").let { l -> (0 until l.length).map { l[it] as HTMLImageElement } }
        assertEquals(listOf("{C}"), pips.map { it.alt }, "a colourless card has no colourless symbol")
    }

    @Test
    fun theLineSitsDirectlyUnderTheName() = runTest {
        peeking()
        val name = el(".peek-sheet .peek-name").getBoundingClientRect()
        val line = el(".peek-sheet .peek-facts").getBoundingClientRect()
        val printing = el(".peek-sheet .peek-sub").getBoundingClientRect()
        assertTrue(line.top >= name.bottom - 0.5, "the line (top ${line.top}) is not under the name (bottom ${name.bottom})")
        assertTrue(line.bottom <= printing.top + 0.5, "the printing (top ${printing.top}) comes before the line (bottom ${line.bottom})")
    }
}
