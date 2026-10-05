package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.LogLine
import org.mattshoe.mtg.core.LogsState
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
 * The log viewer, in headless Chrome.
 *
 * This was `ConsolePageTest` and covered the query console as well.
 * The console is gone; the four tests that drove it went with it.
 */
class LogsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var logs: LogsState? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
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
