package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Legality
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The "Legal in" row, drawn.
 *
 * Every chip that was not the word `legal` came out in the same red,
 * which made "banned in Legacy" and "was never a Standard card" look
 * like the same news — and the news was carried in hue, which is the
 * one channel the person who owns this collection cannot read.
 */
class CardLegalitiesTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
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

    private fun HTMLElement.chips(): List<HTMLElement> = all("div.legalities > span.chip")
    private fun HTMLElement.chipText(): List<String> = chips().map { it.textContent.orEmpty().trim() }

    private fun card(vararg l: Pair<String, String>) =
        CardDetail(name = "Sol Ring", legalities = l.map { (f, s) -> Legality(f, s) })

    /** A card with one of everything on it. */
    private fun mixed() = card(
        "standard" to "not_legal",
        "vintage" to "restricted",
        "commander" to "legal",
        "legacy" to "banned",
    )

    @Test
    fun theLegalityRowDrawsOneChipPerFormat() = runTest {
        val frame = open(mixed())
        settle()
        assertEquals(4, frame.chips().size, "the row shows ${frame.chipText()}")
    }

    @Test
    fun theChipsAppearInTheOrderPeopleAskAboutFormats() = runTest {
        val frame = open(mixed())
        settle()
        val seen = frame.chipText()
        val order = listOf("Commander", "Legacy", "Vintage", "Standard")
        assertEquals(
            order,
            seen.map { it.split(" ")[1] },
            "the chips came out as $seen",
        )
    }

    @Test
    fun aBannedChipSaysTheWordBannedOnThePage() = runTest {
        val frame = open(mixed())
        settle()
        val banned = frame.chipText().first { "Legacy" in it }
        assertTrue("banned" in banned, "the Legacy chip reads \"$banned\"")
    }

    @Test
    fun aBannedChipIsTellableFromALegalOneWithTheColourThrownAway() = runTest {
        // Strip every chip down to the characters in it. If banned
        // and legal still differ, colour was never the only signal.
        val frame = open(mixed())
        settle()
        val legal = frame.chipText().first { "Commander" in it }
        val banned = frame.chipText().first { "Legacy" in it }
        assertTrue(legal != banned, "both chips read \"$legal\"")
        assertTrue(legal.first() != banned.first(), "the chips open with the same mark")
        assertTrue("legal" in legal && "banned" in banned, "$legal / $banned")
    }

    @Test
    fun aFormatTheCardWasNeverInIsNotPaintedAsBanned() = runTest {
        val frame = open(mixed())
        settle()
        val standard = frame.chips().first { it.textContent.orEmpty().contains("Standard") }
        val legacy = frame.chips().first { it.textContent.orEmpty().contains("Legacy") }
        assertTrue(legacy.classList.contains("bad"), "a banned chip is not marked bad")
        assertTrue(
            !standard.classList.contains("bad"),
            "\"not legal in Standard\" is painted the same alarm as a ban",
        )
    }

    @Test
    fun eachStatusGetsItsOwnClassOnThePage() = runTest {
        val frame = open(mixed())
        settle()
        fun toneOf(format: String) = frame.chips()
            .first { it.textContent.orEmpty().contains(format) }
            .className.split(" ").filter { it !in listOf("chip", "mini", "") }
        assertEquals(listOf("ok"), toneOf("Commander"))
        assertEquals(listOf("bad"), toneOf("Legacy"))
        assertEquals(listOf("warn"), toneOf("Vintage"))
        assertEquals(listOf("off"), toneOf("Standard"))
    }

    @Test
    fun everyChipSpellsOutTheFormatAndStatusInItsTitle() = runTest {
        val frame = open(mixed())
        settle()
        assertEquals(
            "Legacy: banned",
            frame.chips().first { it.textContent.orEmpty().contains("Legacy") }
                .getAttribute("title"),
        )
    }

    @Test
    fun aCardWithNoLegalityDataSaysNothingRecordedAndDrawsNoChips() = runTest {
        val frame = open(CardDetail(name = "Sol Ring"))
        settle()
        assertTrue(frame.chips().isEmpty(), "chips on a card with no legality data")
        assertTrue(frame.all("div.legalities").isEmpty(), "an empty chip row was drawn anyway")
        val text = frame.all("div.card-page").first().textContent.orEmpty()
        assertTrue("Nothing recorded." in text, "nothing is said about the missing data")
    }

    @Test
    fun aCardLegalNowhereSaysSoUnderTheHeading() = runTest {
        // The heading says "Legal in". When the answer is nowhere,
        // that has to be written down, not counted off the chips.
        val frame = open(card("commander" to "banned", "standard" to "not_legal"))
        settle()
        val text = frame.all("div.card-page").first().textContent.orEmpty()
        assertTrue("Legal nowhere." in text, "the page never says the card is legal nowhere")
        assertEquals(2, frame.chips().size, "the chips vanished with the legality")
    }

    @Test
    fun aCardLegalSomewhereDoesNotClaimItIsLegalNowhere() = runTest {
        val frame = open(mixed())
        settle()
        val text = frame.all("div.card-page").first().textContent.orEmpty()
        assertTrue("Legal nowhere." !in text, "a Commander-legal card is told it is legal nowhere")
    }

    @Test
    fun theSameFormatTwiceOnlyDrawsOneChip() = runTest {
        val frame = open(card("commander" to "legal", "commander" to "banned"))
        settle()
        assertEquals(1, frame.chips().size, "the row repeats itself: ${frame.chipText()}")
    }

    @Test
    fun aFormatNobodyHasHeardOfStillGetsAChip() = runTest {
        val frame = open(card("commander" to "legal", "timeless" to "not_legal"))
        settle()
        val seen = frame.chipText()
        assertEquals(2, seen.size, seen.toString())
        assertTrue("Timeless" in seen[1], "the new format came out as ${seen[1]}")
    }

    /**
     * `.chip.warn` and `.chip.off` did not exist in `app.css` — only
     * `.chip.ok` and `.chip.bad` were defined — so "restricted" and
     * "not legal" both fell back to the plain, untoned chip and
     * resolved to the exact same computed colour and border as each
     * other. `eachStatusGetsItsOwnClassOnThePage` above checks the
     * class name only, which is exactly what let that through; this
     * checks what the browser actually painted.
     */
    @Test
    fun everyToneIsPaintedDifferentlyFromEveryOtherTone() = runTest {
        val frame = open(mixed())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        fun chipFor(format: String) = frame.chips().first { it.textContent.orEmpty().contains(format) }
        val ok = chipFor("Commander") // legal
        val bad = chipFor("Legacy") // banned
        val warn = chipFor("Vintage") // restricted
        val off = chipFor("Standard") // not_legal
        val tones = mapOf("ok" to ok, "bad" to bad, "warn" to warn, "off" to off)
        fun look(el: HTMLElement): String {
            val cs = window.getComputedStyle(el)
            return listOf(cs.color, cs.borderColor, cs.borderStyle).joinToString("|")
        }
        val looks = tones.mapValues { (_, el) -> look(el) }
        for ((nameA, lookA) in looks) {
            for ((nameB, lookB) in looks) {
                if (nameA == nameB) continue
                assertNotEquals(lookA, lookB, "$nameA and $nameB chips resolve to the same look ($lookA)")
            }
        }
    }

    /** `.chip.off` reads as dimmed/dashed rather than any particular hue. */
    @Test
    fun aNotLegalChipIsNotPaintedAsPlainAsAnUntonedChip() = runTest {
        val frame = open(mixed())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        val off = frame.chips().first { it.textContent.orEmpty().contains("Standard") }
        assertEquals("dashed", window.getComputedStyle(off).borderStyle, "a not-legal chip has no shape cue of its own")
    }

    @Test
    fun theChipRowStaysInsideAPhone() = runTest {
        val frame = open(
            card(
                "commander" to "legal", "modern" to "legal", "legacy" to "banned",
                "vintage" to "restricted", "standard" to "not_legal", "pauper" to "not_legal",
                "paupercommander" to "not_legal", "oathbreaker" to "legal",
            ),
        )
        settle()
        if (!Stylesheet.applied()) return@runTest
        val page = frame.all("div.card-page").first()
        page.style.width = "340px"
        settle()
        val limit = page.getBoundingClientRect().right + 1
        val over = frame.chips().filter { it.getBoundingClientRect().right > limit }
            .map { it.textContent.orEmpty() }
        assertTrue(over.isEmpty(), "chips hanging off a phone: $over")
    }
}
