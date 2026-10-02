package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Ruling
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rulings section of the card page, on a screen.
 *
 * It had no test of any kind, which is how it kept a bug nobody
 * could see by reading it: with no rulings the whole section —
 * heading included — simply was not drawn, so a card the rules team
 * has never said anything about looked exactly like a card whose
 * rulings had failed to load.
 *
 * Everything here renders the real composable against the real
 * stylesheet, because "it wraps on a phone" is a measurement and not
 * an opinion.
 */
class CardRulingsTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    /** Compose HTML recomposes on a frame, so nothing is true until one has passed. */
    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun mount(width: Int, block: @Composable () -> Unit): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { block() }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun card(vararg rulings: Ruling) = CardDetail(
        name = "Anointed Procession",
        printings = listOf(
            Printing(
                id = 1, setCode = "akh", setName = "Amonkhet", collectorNumber = "2",
                finish = "nonfoil", qty = 1, scryfallId = null, owner = "matt",
            ),
        ),
        rulings = rulings.toList(),
    )

    private suspend fun open(card: CardDetail, width: Int = 900): HTMLElement {
        val frame = mount(width) { CardPage(card) }
        settle()
        return frame
    }

    /** The heading of the rulings section, wherever it has ended up. */
    private fun HTMLElement.rulingsHeading(): HTMLElement? =
        all("h3").firstOrNull { it.textContent?.trim() == "Rulings" }

    private fun HTMLElement.rulingLines(): List<HTMLElement> = all("div.ruling")

    // ------------------------------------------------- it is findable

    @Test
    fun theSectionHasAHeadingAPersonCanFind() = runTest {
        val frame = open(card(Ruling("2018-12-07", "It checks the battlefield.")))
        assertTrue(frame.rulingsHeading() != null, "no 'Rulings' heading on the page at all")
    }

    @Test
    fun aCardWithNoRulingsSaysSoRatherThanDroppingTheSection() = runTest {
        // Dropping it leaves "no rulings exist" looking identical to
        // "the rulings did not load", which is the worse of the two.
        val frame = open(card())
        assertTrue(frame.rulingsHeading() != null, "the heading vanished with the rulings")
        assertEquals(0, frame.rulingLines().size)
        val said = frame.all("div.muted").map { it.textContent?.trim() }
        assertTrue("No rulings." in said, "nothing on the page says there are none: $said")
    }

    @Test
    fun aRulingThatIsAllWhitespaceCountsAsNoRulingAtAll() = runTest {
        val frame = open(card(Ruling("2018-12-07", "   ")))
        assertEquals(0, frame.rulingLines().size, "a blank ruling was drawn as a line")
        assertTrue("No rulings." in frame.all("div.muted").map { it.textContent?.trim() })
    }

    // ------------------------------------------------- what is on it

    @Test
    fun oneRulingShowsItsDayAndItsWords() = runTest {
        val frame = open(card(Ruling("2018-12-07", "It checks the battlefield.")))
        val line = frame.rulingLines().singleOrNull() ?: error("expected exactly one ruling line")
        val text = line.textContent.orEmpty()
        assertTrue("2018-12-07" in text, "the date is missing: '$text'")
        assertTrue("It checks the battlefield." in text, "the ruling is missing: '$text'")
    }

    @Test
    fun everyRulingGetsItsOwnLine() = runTest {
        val frame = open(
            card(
                Ruling("2004-10-04", "One."), Ruling("2013-07-01", "Two."),
                Ruling("2018-12-07", "Three."), Ruling("2019-05-03", "Four."),
                Ruling("2021-02-05", "Five."),
            ),
        )
        assertEquals(5, frame.rulingLines().size)
    }

    @Test
    fun theyReadOldestFirstOnThePageWhateverOrderTheServerSentThem() = runTest {
        val frame = open(
            card(
                Ruling("2019-05-03", "Third."),
                Ruling("2004-10-04", "First."),
                Ruling("2018-12-07", "Second."),
            ),
        )
        val order = frame.rulingLines().map { it.textContent.orEmpty() }
        assertTrue(order.size == 3, "expected three lines, got ${order.size}")
        assertTrue("First." in order[0] && "Second." in order[1] && "Third." in order[2], "$order")
    }

    @Test
    fun theDayIsAPlainDateAndNotAWholeTimestamp() = runTest {
        val frame = open(card(Ruling("2018-12-07T00:00:00.000Z", "Timestamped.")))
        val line = frame.rulingLines().single()
        assertEquals("2018-12-07", line.all("span.muted").single().textContent?.trim())
        assertTrue("T00:00:00" !in line.textContent.orEmpty(), "the timestamp reached the screen")
    }

    @Test
    fun aRulingWithAnUnreadableDateShowsItsWordsAndNothingElse() = runTest {
        val frame = open(card(Ruling("not a date", "Still worth reading.")))
        val line = frame.rulingLines().single()
        assertTrue(line.all("span.muted").isEmpty(), "an unreadable date was printed anyway")
        assertEquals("Still worth reading.", line.textContent?.trim())
    }

    @Test
    fun theSameRulingTwiceAppearsOnce() = runTest {
        val frame = open(
            card(
                Ruling("2018-12-07", "It checks the battlefield."),
                Ruling("2018-12-07", "It checks the battlefield."),
            ),
        )
        assertEquals(1, frame.rulingLines().size, "the same ruling was printed twice")
    }

    // -------------------------------------------------------- safety

    @Test
    fun markupInARulingIsReadAndNotParsed() = runTest {
        val nasty = "If <i>Clue</i> & <script>alert('x')</script> is in play, nothing happens."
        val frame = open(card(Ruling("2018-12-07", nasty)))
        val line = frame.rulingLines().single()
        assertTrue(line.all("i").isEmpty(), "an <i> in a ruling became an element")
        assertTrue(line.querySelector("script") == null, "a <script> in a ruling became an element")
        assertTrue(nasty in line.textContent.orEmpty(), "the ruling did not survive as text")
    }

    // --------------------------------------------------- on a phone

    @Test
    fun aLongRulingWrapsInsteadOfRunningOffAPhone() = runTest {
        val long = "If a creature token would be created under your control, instead that many " +
            "plus one of those tokens are created, and this applies to every replacement effect " +
            "that would create one, however many of them are on the battlefield at the time."
        val frame = open(card(Ruling("2018-12-07", long)), width = 400)
        if (!Stylesheet.applied()) return@runTest
        val line = frame.rulingLines().single()
        val box = line.getBoundingClientRect()
        assertTrue(
            box.right <= frame.getBoundingClientRect().right + 1,
            "the ruling runs ${box.right - frame.getBoundingClientRect().right}px past the phone",
        )
        assertTrue(box.height > 30, "a ${long.length}-character ruling stayed on one ${box.height}px line")
    }

    @Test
    fun oneUnbrokenMonsterOfAWordStillFitsThePhone() = runTest {
        // No spaces to break at, which is what defeats plain wrapping.
        val frame = open(card(Ruling("2018-12-07", "x".repeat(300))), width = 400)
        if (!Stylesheet.applied()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.rulingLines().flatMap { listOf(it) + it.all("*") }
            .filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
        assertTrue(over.isEmpty(), "hanging off the right edge of a 400px phone: $over")
    }
}
