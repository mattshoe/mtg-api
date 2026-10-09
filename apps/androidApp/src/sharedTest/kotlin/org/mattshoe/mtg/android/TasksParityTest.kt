package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskStatus
import org.mattshoe.mtg.core.Tasks
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tasks on Admin Settings, through `AppShell`.
 *
 * Matt: "I want the done ones minimized by default but still
 * browsable, ordered by the time which they completed, most recent
 * first". Sibling of `TasksPanelTest` on the web.
 */
@RunWith(AndroidJUnit4::class)
class TasksParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private var held: androidx.compose.runtime.MutableState<AppState>? = null

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val state = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    held = state
                    AppShell(
                        state = state.value,
                        onState = { state.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun adminSettings(tasks: Tasks) = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        tasks = tasks,
    ).navigate(View.ADMIN)

    private val some = Tasks().loaded(
        listOf(
            Task("request/a-1111111", "Older fix", TaskStatus.MERGED, "2026-10-01T10:00:00Z"),
            Task("request/b-2222222", "Building now", TaskStatus.IN_PROGRESS, null),
            Task("request/c-3333333", "Newer fix", TaskStatus.MERGED, "2026-10-05T18:45:00Z"),
            Task("request/d-4444444", "Waiting on CI", TaskStatus.IN_REVIEW, null),
            Task("request/e-5555555", "Given up", TaskStatus.CANCELLED, "2026-10-03T09:00:00Z"),
        ),
    )

    private fun rows(tag: String): List<SemanticsNode> =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = false).fetchSemanticsNodes()

    private fun SemanticsNode.says(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test
    fun activeTasksShowWithTheirStatusAndDoneOnesWaitBehindAToggle() {
        shell(adminSettings(some))
        val active = rows("task").map { it.says() }
        assertEquals(2, active.size, "Admin Settings does not list the active tasks: $active")
        assertTrue("Building now" in active[0] && "in progress" in active[0], active[0])
        assertTrue("Waiting on CI" in active[1] && "in review" in active[1], active[1])
        assertTrue(rows("task-done").isEmpty(), "the done tasks are not minimised by default")

        rule.onNodeWithTag("tasks-done-toggle").performScrollTo().assert(hasText("Done (3)", substring = true))
        rule.onNodeWithTag("tasks-done-toggle").performClick()
        rule.waitForIdle()

        val done = rows("task-done").map { it.says() }
        assertEquals(3, done.size, "pressing Done did not show the finished tasks: $done")
        assertTrue("Newer fix" in done[0], "newest is not first: $done")
        assertTrue("Given up" in done[1] && "cancelled" in done[1], done[1])
        assertTrue("Older fix" in done[2] && "merged" in done[2], done[2])
        assertTrue(held!!.value.tasks.showDone, "the app does not hold that the done list is open")
    }

    @Test
    fun aLoadThatFailedSaysSo() {
        shell(adminSettings(Tasks().failed("API rate limit exceeded")))
        assertTrue(
            rule.onAllNodes(hasText("Could not load tasks: API rate limit exceeded"))
                .fetchSemanticsNodes().isNotEmpty(),
            "the failure is not on screen",
        )
    }

    private fun durations(row: String) = Regex("""(?<!\d)(\d+d \d+h|\d+h \d+m|\d+m)|just started""").findAll(row).map { it.value }.toList()

    /**
     * Matt: "Why does it say \"3m for 3m\" and \"17m for 7m\"?!?!?!" and
     * "just show the total fucking time". One duration, since it was
     * created; not since it started, not how long in this status.
     */
    @Test
    fun aLiveTaskShowsOneDurationTheTimeSinceItWasCreated() {
        val now = Tasks.epochMillis("2026-10-08T09:17:00Z")!!
        val running = Tasks().loaded(
            listOf(
                Task(
                    "k1", "reconcile tells the truth", TaskStatus.IN_PROGRESS, null,
                    startedAt = "2026-10-08T09:05:00Z", statusAt = "2026-10-08T09:10:00Z",
                    createdAt = "2026-10-08T09:00:00Z",
                ),
            ),
        ).at(now)
        shell(adminSettings(running))
        val row = rows("task").single().says()
        assertEquals(listOf("17m"), durations(row), "the row should say one duration, 17m since it was created: '$row'")
        assertTrue("for " !in row, "the row still says how long it has been in its status: '$row'")
    }

    /**
     * Matt: "drop the pr number and only show that on details!" A paused
     * row still says why, and how long since it was created.
     */
    @Test
    fun aPausedTaskSaysWhyAndNoRowNamesItsPullRequest() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        val live = Tasks().loaded(
            listOf(
                Task(
                    "k1", "Builder died", TaskStatus.PAUSED, null,
                    statusAt = "2026-10-08T11:30:00Z", note = "agent crashed", createdAt = "2026-10-08T09:55:00Z",
                ),
                Task(
                    "k2", "Waiting on CI", TaskStatus.IN_REVIEW, null,
                    pr = "https://github.com/mattshoe/mtg-api/pull/142", createdAt = "2026-10-08T11:00:00Z",
                ),
            ),
        ).at(now)
        shell(adminSettings(live))
        val rows = rows("task").map { it.says() }
        val paused = rows.singleOrNull { "Builder died" in it } ?: error("the paused task is not listed: $rows")
        assertTrue("paused" in paused && "agent crashed" in paused, "the paused task does not say why: '$paused'")
        assertEquals(listOf("2h 05m"), durations(paused), "the paused task should say one duration since it was created: '$paused'")
        val review = rows.singleOrNull { "Waiting on CI" in it } ?: error("the task in review is not listed: $rows")
        assertTrue("PR" !in review && "142" !in review, "the task in review still names its pull request on the row: '$review'")
    }

    /**
     * Matt: "Done tasks should show how long they took, not a UTC
     * timestamp", and "are the completed ones going to show it too???"
     * Created to finished, frozen, in the same place a live one counts.
     */
    @Test
    fun aDoneTaskSaysHowLongItTookFromCreatedToFinished() {
        val finished = Tasks().loaded(
            listOf(
                Task(
                    "request/a-1111111", "Older fix", TaskStatus.MERGED, "2026-10-08T13:05:00Z",
                    startedAt = "2026-10-08T11:00:00Z", createdAt = "2026-10-08T10:00:00Z",
                    pr = "https://github.com/mattshoe/mtg-api/pull/142",
                ),
            ),
        ).at(Tasks.epochMillis("2026-10-09T13:05:00Z")!!).toggleDone()
        shell(adminSettings(finished))
        val done = rows("task-done").single().says()
        assertTrue("took 3h 05m" in done, "the done task does not say how long it took from created to finished: '$done'")
        assertEquals(listOf("3h 05m"), durations(done), "the done task should say one duration: '$done'")
        assertTrue("UTC" !in done && "2026-10-08" !in done, "the done task still shows when it finished: '$done'")
        assertTrue("PR" !in done, "the done task still names its pull request on the row: '$done'")
    }
}
