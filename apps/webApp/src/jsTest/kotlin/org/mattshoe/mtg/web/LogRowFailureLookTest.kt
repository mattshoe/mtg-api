package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.LogLine
import org.mattshoe.mtg.core.LogsState
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTableRowElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `LogsPage`'s failed row, against the real stylesheet.
 *
 * `LogsPage.kt` puts `classes("bad")` on a failed row's `<tr>`, and
 * the only `bad` rules in `app.css` were `.chip.bad`, `.tag.bad` and
 * `.toast.bad` — none of them a bare row, so the class painted
 * nothing at all. A test asserting the class name was present would
 * have passed throughout; these assert the row is actually painted
 * differently, and in more than one way, because the owner of this
 * app cannot read hue as a signal on its own.
 */
class LogRowFailureLookTest {

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

    private fun mount(state: LogsState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { LogsPage(state) { } }
        return root
    }

    private fun HTMLElement.rows(): List<HTMLTableRowElement> {
        val n = querySelectorAll("tbody tr")
        return (0 until n.length).mapNotNull { n[it] as? HTMLTableRowElement }
    }

    private fun twoRows() = LogsState().loaded(
        listOf(
            LogLine("2026-09-28T01:02:03Z", "info", null, "GET", "/library", 200, 12, null),
            LogLine("2026-09-28T01:02:04Z", "error", null, "POST", "/admin", 401, 8, "nope"),
        ),
    )

    @Test
    fun theStylesheetActuallyLoadedForThisTest() = runTest {
        mount(twoRows())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied — this suite proves nothing unstyled")
    }

    @Test
    fun aFailedRowIsMarkedBadOnThePage() = runTest {
        val root = mount(twoRows())
        settle()
        val rows = root.rows()
        assertTrue(rows[0].className.split(" ").none { it == "bad" }, "the ok row is marked bad")
        assertTrue(rows[1].className.split(" ").any { it == "bad" }, "the failed row carries no `bad` class")
    }

    /**
     * The actual bug: `classes("bad")` with no `tr.bad` rule to answer
     * it. This compares resolved background colour, not class names —
     * reverting the CSS addition turns this red while the class-name
     * test above stays green.
     */
    @Test
    fun aFailedRowsBackgroundIsActuallyPaintedDifferently() = runTest {
        val root = mount(twoRows())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        val okCell = root.rows()[0].querySelector("td") as HTMLElement
        val badCell = root.rows()[1].querySelector("td") as HTMLElement
        val okBg = window.getComputedStyle(okCell).backgroundColor
        val badBg = window.getComputedStyle(badCell).backgroundColor
        assertNotEquals(okBg, badBg, "a failed row's cell resolves to the same background as a normal one")
    }

    /** The weight channel: bold is readable with no colour perception at all. */
    @Test
    fun aFailedRowIsBolderThanAnOkRow() = runTest {
        val root = mount(twoRows())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        val okCell = root.rows()[0].querySelector("td") as HTMLElement
        val badCell = root.rows()[1].querySelector("td") as HTMLElement
        val okWeight = window.getComputedStyle(okCell).fontWeight
        val badWeight = window.getComputedStyle(badCell).fontWeight
        assertNotEquals(okWeight, badWeight, "a failed row is not any bolder than an ok one")
    }

    /** The shape channel: a rule down the row's leading edge, Android's `leftRule()`. */
    @Test
    fun aFailedRowHasARuleDownItsLeadingEdgeThatAnOkRowDoesNotHave() = runTest {
        val root = mount(twoRows())
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        val okCell = root.rows()[0].querySelector("td") as HTMLElement
        val badCell = root.rows()[1].querySelector("td") as HTMLElement
        val okShadow = window.getComputedStyle(okCell).boxShadow
        val badShadow = window.getComputedStyle(badCell).boxShadow
        assertTrue(badShadow != "none" && badShadow.isNotEmpty(), "the failed row has no edge rule at all")
        assertNotEquals(okShadow, badShadow, "the ok row carries the same edge rule as the failed one")
    }
}
