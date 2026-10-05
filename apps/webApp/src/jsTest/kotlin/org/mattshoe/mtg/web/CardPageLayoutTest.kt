package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Printing
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card page, measured.
 *
 * The art that ships is Scryfall's `normal` scan — 745 pixels wide,
 * larger than any phone. The page carried the grid tile's class,
 * which sets no width at all, so the picture rendered at its natural
 * size and ran off the side of the screen. Nothing caught it because
 * the card had never been measured, only read.
 */
class CardPageLayoutTest {

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

    /** A picture the size of a real card scan, without the network. */
    private val wideArt =
        "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' " +
            "width='745' height='1040'%3E%3Crect width='745' height='1040' fill='%23444'/%3E%3C/svg%3E"

    private fun open(card: CardDetail): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { CardPage(card) }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun detail() = CardDetail(
        name = "Anointed Procession",
        printings = listOf(
            Printing(
                id = 1,
                setCode = "akh", setName = "Amonkhet", collectorNumber = "2",
                finish = "nonfoil", qty = 1,
                scryfallId = "abcdef12-3456-7890-abcd-ef1234567890",
            ),
        ),
    )

    /** Swaps in the local picture and waits for the browser to lay it out. */
    private suspend fun artIn(frame: HTMLElement): HTMLImageElement {
        val img = frame.querySelector("img") as? HTMLImageElement
            ?: error("the card page has no picture in it at all")
        img.src = wideArt
        Promise<Unit> { resolve, _ ->
            if (img.complete) resolve(Unit) else img.onload = { resolve(Unit) }
        }.await()
        settle()
        return img
    }

    @Test
    fun theArtNeverGrowsWiderThanThePageHoldingIt() = runTest {
        val frame = open(detail())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val img = artIn(frame)
        val body = frame.all("div.card-page").firstOrNull() ?: error("no card page")
        val inner = body.clientWidth.toDouble()
        val shown = img.getBoundingClientRect().width
        assertTrue(
            shown <= inner,
            "the 745px scan rendered ${shown}px wide inside a ${inner}px page",
        )
    }

    @Test
    fun andNothingElseOnThePageHangsOffTheSide() = runTest {
        val frame = open(detail())
        settle()
        if (!Stylesheet.applied()) return@runTest
        artIn(frame)
        val page = frame.all("div.card-page").firstOrNull() ?: error("no card page")
        val limit = page.getBoundingClientRect().right + 1
        val over = page.all("*")
            .filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
        assertTrue(over.isEmpty(), "hanging off the right edge: $over")
    }

    @Test
    fun backLeavesTheCardForThePageItWasOpenedFrom() = runTest {
        // Through the shell, over a real AppState, because the button
        // itself was never the suspect.
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        var state = AppState().navigate(org.mattshoe.mtg.core.Route(org.mattshoe.mtg.core.View.DECKS, "alela"))
            .openCard(org.mattshoe.mtg.core.CardRef("sol ring"), "Sol Ring")
        renderComposable(root = root) {
            var s by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(state)
            }
            AppShell(s, { s = it; state = it }, {}, {}, {}, {}, {})
        }
        settle()
        assertTrue(root.all("div.card-page").isNotEmpty(), "the card page is not showing")
        root.all("button").first { it.textContent?.trim() == "← Back" }.click()
        settle()
        assertEquals(org.mattshoe.mtg.core.View.DECKS, state.view, "back went somewhere else")
        assertEquals("alela", state.route.rest)
    }

    /** Two people, one card, the numbers that differ between them. */
    private fun shared() = CardDetail(
        name = "Sol Ring",
        nameNorm = "sol ring",
        printings = listOf(
            Printing(1, "lcc", "The Lost Caverns of Ixalan Commander", "4", "nonfoil", 10, null, owner = "kayla"),
            Printing(2, "m3c", "Modern Horizons 3", "409", "nonfoil", 24, null, owner = "matt"),
        ),
        usedIn = listOf(
            org.mattshoe.mtg.core.DeckUse("a", "Alela", "kayla", 5, null, false),
            org.mattshoe.mtg.core.DeckUse("b", "Bello", "matt", 24, null, false),
        ),
    )

    @Test
    fun thePageSaysWhoOwnsHowMany() = runTest {
        // It used to be one person's page, so the other half of the
        // collection was simply not there.
        val frame = open(shared())
        settle()
        val rows = frame.all("div.owner-line")
        assertEquals(2, rows.size, "the page shows ${rows.size} owners")
        val matt = rows.first { it.textContent.orEmpty().contains("matt") }.textContent.orEmpty()
        val kayla = rows.first { it.textContent.orEmpty().contains("kayla") }.textContent.orEmpty()
        assertTrue("24 owned" in matt && "0 free" in matt, matt)
        assertTrue("10 owned" in kayla && "5 free" in kayla, kayla)
    }

    @Test
    fun aPrintingSaysWhoseCopyItIs() = runTest {
        val frame = open(shared())
        settle()
        val lines = frame.all("div.print-line, a.print-line").map { it.textContent.orEmpty() }
        assertTrue(lines.any { "kayla" in it }, lines.toString())
        assertTrue(lines.any { "matt" in it }, lines.toString())
    }

    @Test
    fun aDeckRowSaysWhoseDeckItIs() = runTest {
        val frame = open(shared())
        settle()
        val text = frame.all("div.card-page").first().textContent.orEmpty()
        assertTrue("Alela" in text && "Bello" in text, text)
    }

    @Test
    fun theOwnerLinesStayOnOneLineOnAPhone() = runTest {
        val frame = open(shared())
        settle()
        if (!Stylesheet.applied()) return@runTest
        frame.all("div.card-page").first().style.width = "340px"
        settle()
        frame.all("div.owner-line").forEach { row ->
            val tops = row.all("span").map { it.getBoundingClientRect().top }
            assertTrue((tops.max() - tops.min()) < 4, "an owner wrapped on a phone")
        }
    }

    @Test
    fun theCardPageOffersAShare() = runTest {
        var shared = 0
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            CardPage(detail(), onShare = { shared++ })
        }
        settle()
        // An icon, not the word. It says what it is through its
        // label, which is the part that has to keep working.
        val button = frame.all("button[aria-label='Share this card']").firstOrNull()
            ?: error("no share button on the card page")
        assertTrue(button.querySelector(".icon-share") != null, "the share button has no icon")
        button.click()
        settle()
        assertEquals(1, shared)
    }

    @Test
    fun theArtIsNotStretchedOutOfShape() = runTest {
        // A card is 488 by 680. Constraining the width without letting
        // the height follow is the other way to make it look wrong.
        val frame = open(detail())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val img = artIn(frame)
        val r = img.getBoundingClientRect()
        val ratio = r.height / r.width
        assertTrue(
            kotlin.math.abs(ratio - 1040.0 / 745.0) < 0.02,
            "the picture is ${r.width} by ${r.height}, a ratio of $ratio",
        )
    }

    /** Two printings: one with a listing, one nobody sells. */
    private fun shoppable() = detail().copy(
        printings = listOf(
            Printing(
                id = 1,
                setCode = "lcc", setName = "The Lost Caverns of Ixalan Commander", collectorNumber = "124",
                finish = "nonfoil", qty = 3,
                scryfallId = "abcdef12-3456-7890-abcd-ef1234567890",
                price = 5.36, tcgplayer = "https://tcg.example/anointed",
            ),
            Printing(
                id = 2,
                setCode = "sld", setName = "Secret Lair Drop Series: Artist Series", collectorNumber = "17",
                finish = "foil", qty = 1,
                scryfallId = "bbcdef12-3456-7890-abcd-ef1234567890",
            ),
        ),
    )

    @Test
    fun aPrintingYouCanBuyLinksToTcgplayer() = runTest {
        val frame = open(shoppable())
        settle()
        val link = frame.all("a.print-line").firstOrNull() ?: error("no printing links at all")
        assertEquals("https://tcg.example/anointed", link.getAttribute("href"))
        // It leaves the site, so it opens away and cannot reach back
        // through `window.opener`.
        assertEquals("_blank", link.getAttribute("target"))
        assertTrue(
            link.getAttribute("rel").orEmpty().contains("noopener"),
            "a new tab with no rel=noopener",
        )
    }

    @Test
    fun aPrintingSaysWhereTheLinkGoes() = runTest {
        // A row that is only subtly a link is a link nobody finds.
        val frame = open(shoppable())
        settle()
        val link = frame.all("a.print-line").first()
        assertTrue(
            link.textContent.orEmpty().contains("TCGplayer"),
            "nothing on the row names the shop: ${link.textContent}",
        )
        val plain = frame.all("div.print-line").first()
        assertTrue(
            !plain.textContent.orEmpty().contains("TCGplayer"),
            "a printing nobody sells offers a shop anyway",
        )
    }

    @Test
    fun aPrintingNobodySellsStaysARowRatherThanALinkToNowhere() = runTest {
        val frame = open(shoppable())
        settle()
        assertEquals(1, frame.all("a.print-line").size, "a link to nowhere")
        assertEquals(2, frame.all("div.print-line, a.print-line").size, "a printing went missing")
    }

    @Test
    fun aPrintingQuotesThePriceForTheFinishItIsIn() = runTest {
        val frame = open(shoppable())
        settle()
        val rows = frame.all("div.print-line, a.print-line")
        assertTrue(rows[0].textContent.orEmpty().contains("$5.36"), rows[0].textContent.orEmpty())
        // Nothing known is a dash, never a zero — a card is not free.
        assertTrue(rows[1].textContent.orEmpty().contains("\u2014"), rows[1].textContent.orEmpty())
    }

    @Test
    fun aPrintingLineSurvivesAPhone() = runTest {
        // Long set names are the common case, so on a narrow screen
        // the name gives way and the price stays put rather than the
        // row wrapping or the number sliding off the edge.
        val frame = open(shoppable())
        settle()
        if (!Stylesheet.applied()) return@runTest
        // Headless Chrome will not go below 500 pixels wide, so the
        // page itself is narrowed to a phone rather than the window.
        frame.all("div.card-page").first().style.width = "340px"
        settle()
        val rows = frame.all("div.print-line, a.print-line")
        assertTrue(rows.isNotEmpty(), "no printings to measure")
        rows.forEach { row ->
            val r = row.getBoundingClientRect()
            assertTrue(r.width < 350, "the page did not narrow: ${r.width}")
            val tops = row.all("span").map { it.getBoundingClientRect().top }
            assertTrue((tops.max() - tops.min()) < 4, "a printing wrapped on a phone")
            val price = row.all("span.num").first().getBoundingClientRect()
            assertTrue(price.right <= r.right + 1, "the price ran off a narrow row")
            // The set name is what gives way, and it gives way by being
            // cut short rather than by pushing the price off the row.
            val name = row.all("span.t-name").first().getBoundingClientRect()
            assertTrue(name.height < 22, "the set name wrapped to ${name.height} tall")
            assertTrue(r.height < 40, "the row grew to ${r.height} tall")
        }
    }

    @Test
    fun aPrintingLineStaysOnOneLine() = runTest {
        val frame = open(shoppable())
        settle()
        if (!Stylesheet.applied()) return@runTest
        frame.all("div.print-line, a.print-line").forEach { row ->
            val r = row.getBoundingClientRect()
            // Everything sits on the same line, so nothing has been
            // pushed onto a second row. Heights differ between a pill
            // and plain text, so tops are what to compare.
            val tops = row.all("span").map { it.getBoundingClientRect().top }
            assertTrue(
                (tops.max() - tops.min()) < 4,
                "a printing wrapped: tops span ${tops.max() - tops.min()}",
            )
            val price = row.all("span.num").first().getBoundingClientRect()
            assertTrue(price.right <= r.right + 1, "the price ran off the row")
        }
    }
}
