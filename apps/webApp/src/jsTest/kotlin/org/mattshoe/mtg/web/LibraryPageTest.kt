package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Sort
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Library in a real browser, clicked.
 *
 * Headless Chrome via Karma, because Compose HTML recomposes on
 * requestAnimationFrame and a browser that is not painting never ticks
 * it. What these check is that the shared state reaches the DOM: the
 * range shown, which pager buttons are dead, which sort is marked.
 */
class LibraryPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var last: Library? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        last = null
    }

    private fun card(name: String, qty: Int = 1, price: Double? = null) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = qty, printings = 1, free = 1, price = price, value = price,
    )

    private fun mount(state: Library): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            LibraryPage(state = state, onState = { last = it }, onSearch = {}, onOpen = {})
        }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    private fun HTMLElement.button(label: String) =
        buttons().first { it.textContent?.trim() == label }

    @Test
    fun rendersRowsAsRealDom() = runTest {
        val root = mount(Library(total = 2).loaded(listOf(card("Sol Ring", 3, 2.5), card("Opt")), 2))
        settle()
        assertEquals(0, root.querySelectorAll("canvas").length)
        assertTrue(root.textContent!!.contains("Sol Ring"))
        assertTrue(root.textContent!!.contains("Opt"))
        // The tile is the picture, the name and a compact caption now,
        // the same markup the hand-written grid used.
        assertTrue(root.textContent!!.contains("×3"), root.textContent!!)
        assertEquals(2, root.querySelectorAll(".card").length)
        // No scryfall id on this fixture, so no picture — a missing id
        // has to leave the frame empty rather than emit a broken img.
        assertEquals(0, root.querySelectorAll(".card-img").length)
    }

    @Test
    fun showsTheRangeAndTotal() = runTest {
        val root = mount(
            Library(filters = Filters(page = 2)).loaded(listOf(card("Sol Ring")), 6607),
        )
        settle()
        assertTrue(root.textContent!!.contains("101–200 of 6607"), root.textContent!!)
        assertTrue(root.textContent!!.contains("Page 2 of 67"))
    }

    @Test
    fun previousIsDeadOnTheFirstPageAndNextOnTheLast() = runTest {
        val first = mount(Library().loaded(listOf(card("A")), 250))
        settle()
        assertTrue(first.button("← Previous").disabled)
        assertFalse(first.button("Next →").disabled)

        val last = mount(Library(filters = Filters(page = 3)).loaded(listOf(card("A")), 250))
        settle()
        assertFalse(last.button("← Previous").disabled)
        assertTrue(last.button("Next →").disabled, "there is no page four")
    }

    @Test
    fun bothPagerButtonsAreDeadOnASinglePage() = runTest {
        val root = mount(Library().loaded(listOf(card("A")), 3))
        settle()
        assertTrue(root.button("← Previous").disabled)
        assertTrue(root.button("Next →").disabled)
    }

    @Test
    fun theSortIsADropdownOfEverySortWithTheCurrentOneChosen() = runTest {
        // Fourteen sorts do not fit across a phone as buttons, and
        // four of them picked arbitrarily is a worse answer.
        val root = mount(Library().loaded(listOf(card("A")), 1))
        settle()
        val select = root.querySelector("select.sort") as org.w3c.dom.HTMLSelectElement
        assertEquals(Sort.entries.size, select.options.length)
        assertEquals("price", select.value, "price descending is the default")
        // And the arrow beside it is the direction.
        assertTrue(root.buttons().any { it.textContent?.trim() == "↓" }, root.textContent!!)
    }

    @Test
    fun pickingASortAsksForItThroughTheSharedState() = runTest {
        val root = mount(Library().loaded(listOf(card("A")), 1))
        settle()
        val select = root.querySelector("select.sort") as org.w3c.dom.HTMLSelectElement
        select.value = "name"
        select.dispatchEvent(org.w3c.dom.events.Event("change", js("({bubbles: true})")))
        settle()
        assertEquals(Sort.NAME, last?.filters?.sort)
        // Picking a column from a list must not silently reverse it.
        assertTrue(last!!.filters.descending)
    }

    @Test
    fun theArrowFlipsTheDirectionWithoutChangingTheColumn() = runTest {
        val root = mount(Library().loaded(listOf(card("A")), 1))
        settle()
        root.button("↓").click()
        settle()
        assertEquals(Sort.PRICE, last?.filters?.sort)
        assertFalse(last!!.filters.descending)
    }

    @Test
    fun changingTheOwnerGoesBackToPageOne() = runTest {
        // The owner control lives in the filter panel now, so drive
        // the rule directly: narrowing anything returns to page one.
        val nine = Library(filters = Filters(page = 9)).loaded(listOf(card("A")), 6607)
        val narrowed = nine.where(nine.filters.copy(owner = "kayla"))
        assertEquals("kayla", narrowed.filters.owner)
        assertEquals(1, narrowed.page, "page nine of a narrower search looks broken")
    }

    @Test
    fun emptyLoadingAndFailingLookDifferent() = runTest {
        assertTrue(mount(Library().loaded(emptyList(), 0)).also { settle() }
            .textContent!!.contains("Nothing matches"))
        assertTrue(mount(Library().loading()).also { settle() }
            .textContent!!.contains("Searching"))
        assertTrue(mount(Library().failed("HTTP 500")).also { settle() }
            .textContent!!.contains("HTTP 500"))
    }
}
