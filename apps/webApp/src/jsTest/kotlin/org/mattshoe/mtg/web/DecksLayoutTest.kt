package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Decks page, measured against the real stylesheet.
 *
 * "No padding between anything" and "the two owners are barely
 * distinguishable" are both numbers: the gap between one person's
 * grid and the next person's heading, and whether that heading has
 * anything separating it from the cards under it. The port rendered a
 * bare `h2` between two grids, and every heading in this stylesheet
 * is `margin: 0`.
 */
class DecksLayoutTest {

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

    private fun mount(width: Int, block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
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

    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun twoOwners() = DecksState().loaded(
        listOf(
            deck("a", "kayla", "Bello"), deck("b", "kayla", "Chulane"),
            deck("c", "matt", "Alela"), deck("d", "matt", "Dihada"),
        ),
    )

    private fun opened() = DecksState()
        .loaded(listOf(deck("a", "matt", "Alela")))
        .opened(
            "a",
            listOf(
                DeckCard("Sol Ring", 1, "ramp", 1),
                DeckCard("Arcane Signet", 1, "ramp", 1),
                DeckCard("Rhystic Study", 1, "draw", 0),
            ),
        )

    // ------------------------------------------------- one owner, then the next

    @Test
    fun eachOwnerIsItsOwnGroup() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        assertEquals(2, frame.all("div.owner-group").size, "the owners are not grouped at all")
        assertEquals(2, frame.all("div.owner-head").size)
    }

    @Test
    fun thereIsRealAirBetweenOneOwnersDecksAndTheNextOwnersName() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val groups = frame.all("div.owner-group")
        val firstGridBottom = groups[0].all("div.deck-grid").first().getBoundingClientRect().bottom
        val secondHeadTop = groups[1].all("div.owner-head").first().getBoundingClientRect().top
        val gap = secondHeadTop - firstGridBottom
        assertTrue(gap >= 20, "only ${gap}px between one owner's decks and the next owner's name")
    }

    @Test
    fun anOwnerHeadingIsSeparatedFromTheDecksUnderIt() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val head = frame.all("div.owner-head").first()
        val grid = frame.all("div.deck-grid").first()
        val gap = grid.getBoundingClientRect().top - head.getBoundingClientRect().bottom
        assertTrue(gap >= 8, "the heading sits ${gap}px off the cards it labels")
        assertTrue(
            window.getComputedStyle(head).borderBottomWidth != "0px",
            "nothing draws the line under an owner's name",
        )
    }

    @Test
    fun theHeadingSaysHowManyDecksAreUnderIt() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        assertTrue(frame.textContent.orEmpty().contains("2 decks"), frame.textContent.orEmpty())
    }

    // ------------------------------------------------------- one deck, opened

    @Test
    fun theCardsInADeckAreRuledOffFromEachOther() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val lines = frame.all("div.deck-line")
        assertEquals(3, lines.size)
        val gap = lines[1].getBoundingClientRect().top - lines[0].getBoundingClientRect().bottom
        assertTrue(gap >= 0, "the rows overlap")
        assertTrue(
            lines[0].getBoundingClientRect().height >= 20,
            "a card line is only ${lines[0].getBoundingClientRect().height}px tall",
        )
        assertTrue(
            window.getComputedStyle(lines[1]).borderTopWidth != "0px",
            "nothing separates one card from the next",
        )
    }

    @Test
    fun nothingInAnOpenedDeckSitsFlushAgainstTheThingAboveIt() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val head = frame.all("div.page-head").first().getBoundingClientRect()
        val body = frame.all("div.stack").first().getBoundingClientRect()
        assertTrue(body.top - head.bottom >= 8, "only ${body.top - head.bottom}px under the header")

        val tags = frame.all("div.flex-wrap").first().getBoundingClientRect()
        val panel = frame.all("div.panel").first().getBoundingClientRect()
        assertTrue(panel.top - tags.bottom >= 8, "only ${panel.top - tags.bottom}px above the card list")
    }

    @Test
    fun theBackButtonIsInTheHeaderRatherThanFloatingAboveIt() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val head = frame.all("div.page-head").firstOrNull() ?: error("no page head")
        assertTrue(
            head.textContent.orEmpty().contains("← Decks"),
            "the back button is not in the header: ${head.textContent}",
        )
    }

    @Test
    fun nothingOverflowsTheDecksPageAtPhoneWidth() = runTest {
        val frame = mount(390) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.all("*")
            .filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
        assertTrue(over.isEmpty(), "hanging off the right edge: $over")
    }
}
