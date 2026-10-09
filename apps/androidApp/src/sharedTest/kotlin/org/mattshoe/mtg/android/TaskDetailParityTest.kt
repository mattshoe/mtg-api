package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
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
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskDetail
import org.mattshoe.mtg.core.TaskStatus
import org.mattshoe.mtg.core.Tasks
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.openTaskKey
import org.mattshoe.mtg.core.openingTask
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A task's own page, through `AppShell`.
 *
 * Matt: "I want to be able to tap on a task and be taken to a details
 * page that shows the whole request and all information about it,
 * including any attachments and whatnot". Sibling of the web's
 * `AppDriverTest` task page journey; `TaskDetailLoadTest` drives the
 * loading through the real `MainActivity`.
 */
@RunWith(AndroidJUnit4::class)
class TaskDetailParityTest {

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

    private val task = Task(
        "ab12cd34", "Bigger buttons", TaskStatus.MERGED, "2026-10-08T12:00:00.000Z",
        startedAt = "2026-10-08T10:05:00.000Z",
        pr = "https://github.com/mattshoe/mtg-api/pull/72",
    )

    private val admin = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        tasks = Tasks().loaded(listOf(task)),
    )

    /** A real 1×1 PNG, so the image has something to decode. */
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    /** `GET /tasks/ab12cd34`, as the Worker answers it. */
    private val opened = admin.navigate(View.ADMIN).openingTask(task).copy(
        taskDetail = TaskDetail.decode(
            "ab12cd34",
            """{"key":"ab12cd34","name":"bigger-buttons","title":"Bigger buttons",
                "details":"The buttons are too small to hit.","status":"merged","note":null,
                "pr":"https://github.com/mattshoe/mtg-api/pull/72","created_at":"2026-10-08T10:00:00.000Z",
                "started_at":"2026-10-08T10:05:00.000Z","finished_at":"2026-10-08T12:00:00.000Z",
                "files":[{"name":"shot.png","type":"image/png","data":"$png"}]}""",
        ),
    )

    /** Merged, so a row's label and value read as one line. */
    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { it.says() }

    private fun SemanticsNode.says(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test
    fun tappingATaskOnAdminSettingsOpensItsOwnPage() {
        shell(admin.navigate(View.ADMIN).copy(tasks = admin.tasks.toggleDone()))
        rule.onNodeWithTag("task-done").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(task.key, held!!.value.openTaskKey, "tapping the task did not open it: ${held!!.value.route}")
        assertTrue(
            rule.onAllNodes(hasTestTag("task-detail")).fetchSemanticsNodes().isNotEmpty(),
            "the task's page is not on screen",
        )
    }

    @Test
    fun thePageShowsTheWholeRequestItsPullRequestAndItsFiles() {
        shell(opened)
        val body = texts("task-detail-body").joinToString(" ")
        assertTrue("The buttons are too small to hit." in body, "the request is not on the page: '$body'")
        val pull = texts("task-detail-pull").joinToString(" ")
        assertTrue("#72" in pull, "the pull request is not on the page: '$pull'")
        val facts = texts("task-detail-fact").joinToString(" | ")
        assertTrue("Status merged" in facts, "the status is not on the page: '$facts'")
        assertTrue("Took 2h 00m" in facts, "how long it took is not on the page: '$facts'")
        assertTrue("Request requests/bigger-buttons.md" in facts, "the request file is not named: '$facts'")
        val files = texts("task-detail-file").joinToString(" ")
        assertTrue("shot.png" in files, "the file is not listed: '$files'")
        val image = rule.onAllNodes(hasTestTag("task-detail-image-shot.png")).fetchSemanticsNodes()
        assertEquals(1, image.size, "the picture sent with the task is not shown")
        assertTrue(image.single().size.height > 0, "the picture is on the page with no height")
    }

    /** A task with no text kept says so, rather than an empty panel that reads as broken. */
    @Test
    fun aTaskWithNoTextSaysSo() {
        shell(
            opened.copy(
                taskDetail = TaskDetail.decode("ab12cd34", """{"key":"ab12cd34","title":"Old","status":"merged","details":"","files":[]}"""),
            ),
        )
        assertEquals(TaskDetail.NO_TEXT, texts("task-detail-body").joinToString(" "))
    }

    @Test
    fun backOnATasksPageIsAdminSettings() {
        shell(opened)
        rule.onNodeWithTag("task-detail-back").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Route(View.ADMIN), held!!.value.route)
    }
}
