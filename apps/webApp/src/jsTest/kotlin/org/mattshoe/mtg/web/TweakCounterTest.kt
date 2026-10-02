package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Tweak
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The −/number/+ on the tweak sheet, pressed in a real browser.
 *
 * Everything here is something the counter has to get right before the
 * rest of the sheet means anything: the box has to agree with the
 * state it came from, the ends have to be visibly shut rather than
 * quietly dead, a plan already on screen has to go the moment the
 * number it was about changes — and two taps inside one animation
 * frame have to be two cards, because Compose recomposes on a frame
 * and a thumb does not wait for one.
 */
class TweakCounterTest {

    private val roots = mutableListOf<HTMLElement>()

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

    private val deck = Deck("alela", "Alela", "matt", "Alela, Cunning Conqueror", "UB", 3, null)
    private val bolt = Found(1, "Lightning Bolt", null, "Instant", 4, "matt")

    private fun card(name: String, qty: Int) =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase())

    /** A count being changed on a card the deck already holds. */
    private fun counting(have: Int = 3) =
        DeckTweak.on(deck, "Alela", card("Swamp", have), Tweak.QUANTITY)

    /** A card being added, which is the other shape the counter has. */
    private fun adding() = DeckTweak.add(deck, "Alela").picked(bolt)

    private class Sheet(val root: HTMLElement, val state: () -> DeckTweak) {

        val counter: HTMLElement? get() = root.querySelector("div.counter") as? HTMLElement

        private fun buttons() = counter!!.querySelectorAll("button").let { n ->
            (0 until n.length).map { n[it] as HTMLButtonElement }
        }

        val minus: HTMLButtonElement get() = buttons().first()
        val plus: HTMLButtonElement get() = buttons().last()
        val box: HTMLInputElement get() = counter!!.querySelector("input") as HTMLInputElement

        /** What the box actually shows, which is the only number a person sees. */
        val shown: String get() = box.value

        val says: String get() = (root.querySelector("div.tweak-says") as? HTMLElement)?.textContent?.trim().orEmpty()

        val plan: HTMLElement? get() = root.querySelector("div.tally") as? HTMLElement

        val footLabels: List<String>
            get() = root.querySelectorAll("div.wiz-foot button").let { n ->
                (0 until n.length).mapNotNull { (n[it] as? HTMLElement)?.textContent?.trim() }
            }

        fun type(text: String) {
            box.value = text
            box.dispatchEvent(Event("input", js("({bubbles: true})")))
        }
    }

    private fun mount(start: DeckTweak, width: Int = 420): Sheet {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        var held = start
        renderComposable(root = frame) {
            var s by remember { mutableStateOf(start) }
            DeckTweakSheet(
                state = s,
                onState = { s = it; held = it },
                onFind = {},
                onPreview = {},
                onApply = {},
                onClose = {},
            )
        }
        return Sheet(frame) { held }
    }

    // ------------------------------------------- the box says the state

    @Test
    fun theNumberShownIsTheNumberInState() = runTest {
        val s = mount(counting(7))
        settle()
        assertNotNull(s.counter, "there is no counter on the sheet at all")
        assertEquals("7", s.shown)
        assertEquals(7, s.state().qty)
    }

    @Test
    fun plusAddsOneAndTheBoxFollows() = runTest {
        val s = mount(counting(3))
        settle()
        s.plus.click()
        settle()
        assertEquals(4, s.state().qty, "the plus did not count")
        assertEquals("4", s.shown, "the state moved and the box did not")
    }

    @Test
    fun minusTakesOneAndTheBoxFollows() = runTest {
        val s = mount(counting(3))
        settle()
        s.minus.click()
        settle()
        assertEquals(2, s.state().qty, "the minus did not count")
        assertEquals("2", s.shown)
    }

    @Test
    fun pressingOneOfThemFiveTimesIsFiveCards() = runTest {
        val s = mount(counting(3))
        settle()
        repeat(5) { s.plus.click(); settle() }
        assertEquals(8, s.state().qty)
        assertEquals("8", s.shown)
    }

    @Test
    fun threeTapsInsideOneFrameAreThreeCards() = runTest {
        // Compose recomposes on an animation frame, so every handler
        // installed at the last one holds the same state. Stepping off
        // that state loses every tap but the first — which is exactly
        // what holding the key down, or a fast thumb, produces.
        val s = mount(counting(3))
        settle()
        s.plus.click()
        s.plus.click()
        s.plus.click()
        settle()
        assertEquals(6, s.state().qty, "taps inside one frame collapsed into one")
        assertEquals("6", s.shown)
    }

    // -------------------------------------------------------- the ends

    @Test
    fun aCountCanGoToNoughtBecauseThatTakesTheCardOut() = runTest {
        val s = mount(counting(1))
        settle()
        assertFalse(s.minus.disabled, "nought is a real answer for a count and the minus was shut")
        s.minus.click()
        settle()
        assertEquals(0, s.state().qty)
        assertEquals("0", s.shown)
        assertTrue(s.says.endsWith("→ 0"), "the sheet did not say what nought means: ${s.says}")
    }

    @Test
    fun theMinusIsShutAtTheFloorRatherThanLiveAndDoingNothing() = runTest {
        val s = mount(counting(0))
        settle()
        assertTrue(s.minus.disabled, "the minus was live at nought")
        s.minus.click()
        settle()
        assertEquals(0, s.state().qty, "it went below nought")
        assertEquals("0", s.shown)
    }

    @Test
    fun addingNoughtCopiesIsNotOffered() = runTest {
        // An add of nought is not a change, so the sheet must not park
        // the number there under a Preview button that will not press.
        val s = mount(adding())
        settle()
        assertEquals("1", s.shown)
        assertTrue(s.minus.disabled, "an add offered to add nought copies")
        s.minus.click()
        settle()
        assertEquals(1, s.state().qty)
    }

    @Test
    fun thePlusIsShutAtTheCeiling() = runTest {
        val s = mount(counting(3).count(DeckTweak.MAX_QTY))
        settle()
        assertEquals("${DeckTweak.MAX_QTY}", s.shown)
        assertTrue(s.plus.disabled, "the plus was live at the ceiling")
        s.plus.click()
        settle()
        assertEquals(DeckTweak.MAX_QTY, s.state().qty, "it went past the ceiling")
        assertFalse(s.minus.disabled, "the other end went off with it")
    }

    // --------------------------------------------------- typed numbers

    @Test
    fun aTypedNumberPastTheCeilingComesBackClamped() = runTest {
        // The box takes typing, and a slipped thumb that plans a
        // 9999-card purchase is worse than one that cannot type it.
        val s = mount(counting(3))
        settle()
        s.type("9999")
        settle()
        assertEquals(DeckTweak.MAX_QTY, s.state().qty)
        assertEquals("${DeckTweak.MAX_QTY}", s.shown, "the box kept a number the state refused")
    }

    @Test
    fun aTypedMinusNumberDoesNotGetThrough() = runTest {
        val s = mount(counting(3))
        settle()
        s.type("-4")
        settle()
        assertEquals(0, s.state().qty)
        assertEquals("0", s.shown)
    }

    @Test
    fun backspacingTheBoxEmptyDoesNotEmptyTheDeck() = runTest {
        // An empty box used to read as nought, and nought on a count
        // takes the card out of the deck. Deleting a card is not what
        // "I am about to type a different number" means.
        val s = mount(counting(4))
        settle()
        s.type("")
        settle()
        assertEquals(4, s.state().qty, "clearing the box took the card out of the deck")
        assertEquals("4", s.shown)
    }

    @Test
    fun typingSomethingThatIsNotANumberLeavesTheNumberAlone() = runTest {
        val s = mount(counting(4))
        settle()
        s.type("lots")
        settle()
        assertEquals(4, s.state().qty)
        assertEquals("4", s.shown, "the box was left holding something that is not the number")
    }

    // ----------------------------------------------- and the plan

    @Test
    fun changingTheNumberThrowsAwayThePlan() = runTest {
        // The plan priced one number. Keeping it on screen next to a
        // different number means the Apply button writes a change
        // nobody was shown.
        val s = mount(counting(10).count(7).planned(DeckPlan(cardCount = 99)))
        settle()
        assertNotNull(s.plan, "the plan was never on screen, so this proves nothing")
        s.plus.click()
        settle()
        assertNull(s.plan, "the plan for 7 was still showing at 8")
        assertNull(s.state().plan)
        assertTrue(s.footLabels.any { it.startsWith("Preview") }, "it still offered to apply: ${s.footLabels}")
    }

    @Test
    fun aNumberThatDidNotMoveKeepsThePlan() = runTest {
        // Typing 999 at the ceiling leaves the number exactly where the
        // plan found it, so the plan still describes the change.
        val s = mount(counting(10).count(DeckTweak.MAX_QTY).planned(DeckPlan(cardCount = 99)))
        settle()
        assertNotNull(s.plan)
        s.type("999")
        settle()
        assertNotNull(s.plan, "a number that did not change killed the plan anyway")
        assertEquals(DeckTweak.MAX_QTY, s.state().qty)
        assertEquals("${DeckTweak.MAX_QTY}", s.shown)
    }

    // ------------------------------------------------- saying so

    @Test
    fun bothButtonsSayWhatTheyDo() = runTest {
        val s = mount(counting(3))
        settle()
        val less = s.minus.getAttribute("aria-label")
        val more = s.plus.getAttribute("aria-label")
        assertFalse(less.isNullOrBlank(), "the minus is a glyph and nothing else")
        assertFalse(more.isNullOrBlank(), "the plus is a glyph and nothing else")
        assertTrue(less != more, "both buttons read the same out loud")
        assertFalse(s.box.getAttribute("aria-label").isNullOrBlank(), "the number has no name")
        assertEquals("numeric", s.box.getAttribute("inputmode"), "a phone would open the letter keyboard")
    }

    @Test
    fun thereIsNoCounterWhenTheCardIsLeaving() = runTest {
        // Removing takes the whole row, so a number on screen would be
        // one the change ignores.
        val s = mount(DeckTweak.on(deck, "Alela", card("Swamp", 3), Tweak.REMOVE))
        settle()
        assertNull(s.counter, "a removal showed a number it does not use")
    }

    // -------------------------------------------------- and the size

    @Test
    fun bothButtonsAreThumbSizedAtPhoneWidth() = runTest {
        val s = mount(counting(3), width = 390)
        settle()
        if (!Stylesheet.applied()) return@runTest
        listOf("minus" to s.minus, "plus" to s.plus).forEach { (name, b) ->
            val r = b.getBoundingClientRect()
            assertTrue(r.height >= 44, "the $name is only ${r.height}px tall")
            assertTrue(r.width >= 44, "the $name is only ${r.width}px wide")
        }
    }

    @Test
    fun andStayThumbSizedUnderAFinger() = runTest {
        // `.btn.sm` is cut to 38px inside `@media (pointer: coarse)`,
        // at the same weight as `.counter .btn` and later in the file
        // — so the counter lost 6px on the only device that needed
        // them, and no desktop test could ever see it. This asks the
        // cascade which rule wins, ignoring the condition.
        val s = mount(counting(3), width = 390)
        settle()
        if (!Stylesheet.applied()) return@runTest
        listOf("minus" to s.minus, "plus" to s.plus).forEach { (name, b) ->
            val won = winningMinHeight(b)
            assertTrue(won >= 44, "under a coarse pointer the $name wins ${won}px, not 44")
        }
    }

    @Test
    fun theNumberIsBigEnoughToRead() = runTest {
        val s = mount(counting(3), width = 390)
        settle()
        if (!Stylesheet.applied()) return@runTest
        val size = window.getComputedStyle(s.box).fontSize.removeSuffix("px").toDouble()
        // Under 16px a phone zooms the whole page when the box is tapped.
        assertTrue(size >= 16, "the number is ${size}px")
        assertEquals("center", window.getComputedStyle(s.box).textAlign)
    }

    @Test
    fun aShutButtonIsNotOnlyADifferentColour() = runTest {
        // Whoever owns this cannot tell two hues apart, so "off" has to
        // be legible as something other than a hue.
        val s = mount(counting(0), width = 390)
        settle()
        if (!Stylesheet.applied()) return@runTest
        val off = window.getComputedStyle(s.minus)
        val on = window.getComputedStyle(s.plus)
        val faded = (off.opacity.toDoubleOrNull() ?: 1.0) < (on.opacity.toDoubleOrNull() ?: 1.0)
        assertTrue(faded, "the shut button is the same weight as the live one, only recoloured")
        assertEquals("not-allowed", off.cursor, "and the pointer does not say so either")
    }

    /**
     * Which `min-height` the cascade would actually hand this element,
     * media conditions ignored on purpose.
     *
     * The rule that shrinks the counter lives inside a media query
     * headless Chrome will never match, so measuring cannot find it.
     * This walks the sheet instead, keeps the rules that really match
     * the element, and picks the winner the way the cascade does: most
     * classes, then last declared.
     */
    private fun winningMinHeight(el: HTMLElement): Double {
        var best = -1.0
        var bestRank = -1
        val sheets = document.styleSheets

        fun walk(list: dynamic) {
            val n = list.length as Int
            for (k in 0 until n) {
                val rule = list[k]
                // A media query holds its rules here. So, since CSS
                // nesting, does an ordinary rule — an empty list — so
                // this cannot be the test for "is it a group".
                val nested = rule.cssRules
                if (nested != null && (nested.length as Int) > 0) walk(nested)
                val selector = rule.selectorText as? String ?: continue
                val height = (rule.style?.minHeight as? String).orEmpty()
                if (height.isBlank()) continue
                val matching = selector.split(",").map { it.trim() }
                    .filter { part -> try { el.matches(part) } catch (e: Throwable) { false } }
                if (matching.isEmpty()) continue
                val rank = matching.maxOf { part -> part.count { it == '.' } }
                if (rank >= bestRank) {
                    bestRank = rank
                    best = height.removeSuffix("px").toDoubleOrNull() ?: best
                }
            }
        }

        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) ?: continue
            if (sheet.href?.endsWith("app.css") != true) continue
            val rules = try { sheet.asDynamic().cssRules } catch (e: Throwable) { null } ?: continue
            walk(rules)
        }
        return best
    }
}
