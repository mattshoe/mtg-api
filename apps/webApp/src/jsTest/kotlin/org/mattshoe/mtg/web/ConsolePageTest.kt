package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.ConsoleState
import org.mattshoe.mtg.core.LogLine
import org.mattshoe.mtg.core.LogsState
import org.mattshoe.mtg.core.Table
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console and the log viewer, in headless Chrome. */
class ConsolePageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var state: ConsoleState? = null
    private var logs: LogsState? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        state = null
        logs = null
    }

    private fun mount(block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { block() }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    @Test
    fun runIsRefusedWithNothingToRun() = runTest {
        val root = mount { ConsolePage(ConsoleState(), {}, {}) }
        settle()
        assertTrue(root.buttons().first { it.textContent == "Run" }.disabled)
    }

    @Test
    fun aResultRendersAsARealTable() = runTest {
        val s = ConsoleState(sql = "SELECT 1").ran(
            Table(listOf("name", "qty"), listOf(listOf("Sol Ring", "3"), listOf("Opt", null))),
            7,
        )
        val root = mount { ConsolePage(s, {}, {}) }
        settle()
        assertEquals(2, root.querySelectorAll("th").length)
        assertEquals(4, root.querySelectorAll("td").length)
        assertTrue(root.textContent!!.contains("Sol Ring"))
        assertTrue(root.textContent!!.contains("null"), "a null cell must not render as empty")
        assertTrue(root.textContent!!.contains("2 rows in 7ms"))
    }

    /** An old table under a new error reads as though the query worked. */
    @Test
    fun anErrorReplacesTheStaleResultRatherThanSittingAboveIt() = runTest {
        val s = ConsoleState(sql = "SELEC 1")
            .ran(Table(listOf("x"), listOf(listOf("1"))), 2)
            .failed("near \"SELEC\": syntax error")
        val root = mount { ConsolePage(s, {}, {}) }
        settle()
        assertTrue(root.textContent!!.contains("syntax error"))
        assertEquals(0, root.querySelectorAll("table").length)
    }

    @Test
    fun anEmptyResultSaysSoRatherThanShowingAnEmptyTable() = runTest {
        val root = mount { ConsolePage(ConsoleState(sql = "SELECT 1").ran(Table(listOf("x")), 1), {}, {}) }
        settle()
        assertTrue(root.textContent!!.contains("No rows"))
    }

    @Test
    fun theLogShowsItsLinesAndCountsTheFailures() = runTest {
        val s = LogsState().loaded(
            listOf(
                LogLine("2026-09-28T01:02:03Z", "info", null, "POST", "/query", 200, 12, null),
                LogLine("2026-09-28T01:02:04Z", "error", null, "POST", "/admin", 401, 8, null),
            ),
        )
        val root = mount { LogsPage(s) { logs = it } }
        settle()
        assertTrue(root.textContent!!.contains("Errors only (1)"))
        assertTrue(root.textContent!!.contains("/query"))
        assertTrue(root.textContent!!.contains("01:02:03"))
    }

    @Test
    fun narrowingToErrorsDoesNotThrowTheRestAway() = runTest {
        val s = LogsState().loaded(
            listOf(
                LogLine("t", "info", null, "GET", "/a", 200, 1, null),
                LogLine("t", "error", null, "GET", "/b", 500, 1, null),
            ),
        )
        val root = mount { LogsPage(s) { logs = it } }
        settle()
        root.buttons().first { it.textContent!!.startsWith("Errors only") }.click()
        settle()
        assertEquals(1, logs!!.shown.size)
        assertEquals(2, logs!!.lines.size)
        assertFalse(logs!!.toggleErrors().onlyErrors)
    }
}
