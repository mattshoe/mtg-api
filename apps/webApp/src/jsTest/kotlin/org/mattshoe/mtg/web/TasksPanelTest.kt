package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskStatus
import org.mattshoe.mtg.core.Tasks
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tasks on Admin Settings, through the real shell, in a real browser.
 *
 * Matt: "I want the done ones minimized by default but still
 * browsable, ordered by the time which they completed, most recent
 * first". Sibling of `TasksParityTest` on the phone.
 */
class TasksPanelTest {

    private val roots = mutableListOf<HTMLElement>()
    private var held: MutableState<AppState>? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private fun mount(initial: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            held = s
            AppNav(s.value, onState = { s.value = it })
            AppShell(
                state = s.value,
                onState = { s.value = it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun adminSettings(tasks: Tasks) = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        tasks = tasks,
    ).navigate(View.ADMIN)

    private val some = Tasks().loaded(
        listOf(
            Task("request/a-1111111", "Older fix", TaskStatus.DONE, "2026-10-01T10:00:00Z"),
            Task("request/b-2222222", "Building now", TaskStatus.BUILDING, null),
            Task("request/c-3333333", "Newer fix", TaskStatus.DONE, "2026-10-05T18:45:00Z"),
            Task("request/d-4444444", "Waiting on CI", TaskStatus.IN_REVIEW, null),
            Task("request/e-5555555", "Given up", TaskStatus.CLOSED, "2026-10-03T09:00:00Z"),
        ),
    )

    private fun HTMLElement.all(selector: String): List<HTMLElement> {
        val found = querySelectorAll(selector)
        return (0 until found.length).map { found[it] as HTMLElement }
    }

    /** Laid out and visible, not merely in the DOM. */
    private fun HTMLElement.shown(): Boolean = getBoundingClientRect().height > 0

    @Test
    fun activeTasksShowWithTheirStatusAndDoneOnesWaitBehindAToggle() = runTest {
        val root = mount(adminSettings(some))
        settle()
        val active = root.all("[data-task]").filter { it.shown() }.map { it.textContent.orEmpty() }
        assertEquals(2, active.size, "Admin Settings does not list the active tasks: ${root.textContent}")
        assertTrue("Waiting on CI" in active[0] && "in review" in active[0], active[0])
        assertTrue("Building now" in active[1] && "building" in active[1], active[1])
        assertTrue(root.all("[data-task-done]").none { it.shown() }, "the done tasks are not minimised by default")

        val toggle = root.all("[data-tasks-toggle]").singleOrNull()
            ?: error("there is no Done toggle: ${root.textContent}")
        assertTrue("Done (3)" in toggle.textContent.orEmpty(), toggle.textContent)
        toggle.click()
        settle()

        val done = root.all("[data-task-done]").filter { it.shown() }.map { it.textContent.orEmpty() }
        assertEquals(3, done.size, "pressing Done did not show the finished tasks: $done")
        assertTrue("Newer fix" in done[0], "newest is not first: $done")
        assertTrue("Given up" in done[1] && "closed" in done[1], done[1])
        assertTrue("Older fix" in done[2] && "done" in done[2], done[2])
        assertTrue(held!!.value.tasks.showDone, "the app does not hold that the done list is open")
    }

    @Test
    fun aLoadThatFailedSaysSo() = runTest {
        val root = mount(adminSettings(Tasks().failed("API rate limit exceeded")))
        settle()
        val text = root.textContent.orEmpty()
        assertTrue("Could not load tasks: API rate limit exceeded" in text, text)
    }

    @Test
    fun aRunningTaskSaysHowLongSinceItStarted() = runTest {
        val now = Tasks.epochMillis("2026-10-08T12:20:00Z")!!
        val running = Tasks().loaded(
            listOf(
                Task("request/b-2222222", "Building now", TaskStatus.BUILDING, null, startedAt = "2026-10-08T09:15:00Z"),
                Task("request/a-1111111", "Older fix", TaskStatus.DONE, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z"),
            ),
        ).at(now).toggleDone()
        val root = mount(adminSettings(running))
        settle()
        val row = root.all("[data-task]").singleOrNull { it.shown() }?.textContent.orEmpty()
        assertTrue("3h 05m" in row, "the running task does not say how long it has been going: '$row'")
        val done = root.all("[data-task-done]").singleOrNull { it.shown() }?.textContent.orEmpty()
        assertTrue("2h 20m" !in done, "a finished task is still counting: '$done'")
    }

    /** Matt: "Done tasks should show how long they took, not a UTC timestamp". */
    @Test
    fun aDoneTaskSaysHowLongItTookNotWhen() = runTest {
        val finished = Tasks().loaded(
            listOf(
                Task("request/a-1111111", "Older fix", TaskStatus.DONE, "2026-10-08T13:05:00Z", startedAt = "2026-10-08T10:00:00Z"),
            ),
        ).toggleDone()
        val root = mount(adminSettings(finished))
        settle()
        val done = root.all("[data-task-done]").singleOrNull { it.shown() }?.textContent.orEmpty()
        assertTrue("took 3h 05m" in done, "the done task does not say how long it took: '$done'")
        assertTrue("UTC" !in done && "2026-10-08" !in done, "the done task still shows when it finished: '$done'")
    }
}
