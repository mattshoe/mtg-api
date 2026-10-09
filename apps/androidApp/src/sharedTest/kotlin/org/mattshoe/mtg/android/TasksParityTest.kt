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

    @Test
    fun aRunningTaskSaysHowLongSinceItStarted() {
        val now = Tasks.epochMillis("2026-10-08T12:20:00Z")!!
        val running = Tasks().loaded(
            listOf(
                Task("request/b-2222222", "Building now", TaskStatus.IN_PROGRESS, null, startedAt = "2026-10-08T09:15:00Z"),
                Task("request/a-1111111", "Older fix", TaskStatus.MERGED, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z"),
            ),
        ).at(now).toggleDone()
        shell(adminSettings(running))
        val row = rows("task").single().says()
        assertTrue("3h 05m" in row, "the running task does not say how long it has been going: '$row'")
        val done = rows("task-done").single().says()
        assertTrue("2h 20m" !in done, "a finished task is still counting: '$done'")
    }

    /**
     * Matt watched four stopped agents all say `building`. A paused row
     * says why and for how long, and one in review names its pull
     * request, so nobody has to ask.
     */
    @Test
    fun aPausedTaskSaysWhyAndForHowLongAndOneInReviewNamesItsPullRequest() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        val live = Tasks().loaded(
            listOf(
                Task(
                    "k1", "Builder died", TaskStatus.PAUSED, null,
                    statusAt = "2026-10-08T09:55:00Z", note = "agent crashed",
                ),
                Task(
                    "k2", "Waiting on CI", TaskStatus.IN_REVIEW, null,
                    pr = "https://github.com/mattshoe/mtg-api/pull/142",
                ),
            ),
        ).at(now)
        shell(adminSettings(live))
        val rows = rows("task").map { it.says() }
        val paused = rows.singleOrNull { "Builder died" in it } ?: error("the paused task is not listed: $rows")
        assertTrue("paused" in paused, "the paused task does not say paused: '$paused'")
        assertTrue("for 2h 05m" in paused && "agent crashed" in paused, "the paused task does not say why or for how long: '$paused'")
        val review = rows.singleOrNull { "Waiting on CI" in it } ?: error("the task in review is not listed: $rows")
        assertTrue("PR #142" in review, "the task in review does not name its pull request: '$review'")
    }

    /** Matt: "Done tasks should show how long they took, not a UTC timestamp". */
    @Test
    fun aDoneTaskSaysHowLongItTookNotWhen() {
        val finished = Tasks().loaded(
            listOf(
                Task("request/a-1111111", "Older fix", TaskStatus.MERGED, "2026-10-08T13:05:00Z", startedAt = "2026-10-08T10:00:00Z"),
            ),
        ).toggleDone()
        shell(adminSettings(finished))
        val done = rows("task-done").single().says()
        assertTrue("took 3h 05m" in done, "the done task does not say how long it took: '$done'")
        assertTrue("UTC" !in done && "2026-10-08" !in done, "the done task still shows when it finished: '$done'")
    }
}
