package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.TaskFile
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Admin Settings' New task, through `AppShell`.
 *
 * Matt: "I want to be able to tap a "new task" button and get a simple
 * but attractive new screen where i can enter the details and upload
 * files". Sibling of the web's `AppDriverTest` New task journey;
 * `NewTaskSendTest` drives the reading and the sending through the real
 * `MainActivity`.
 */
@RunWith(AndroidJUnit4::class)
class NewTaskParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private var held: androidx.compose.runtime.MutableState<AppState>? = null
    private var sends = 0
    private var picks = 0

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
                        onPickFile = { picks++ },
                        onSendTask = { sends++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private val admin = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { it.says() }

    private fun SemanticsNode.says(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test
    fun theNewTaskButtonOnAdminSettingsOpensItsOwnScreen() {
        shell(admin.navigate(View.ADMIN))
        rule.onNodeWithTag("new-task").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(held!!.value.writingTask, "New task did not open its screen: ${held!!.value.route}")
        assertTrue(
            rule.onAllNodes(hasTestTag("new-task-details")).fetchSemanticsNodes().isNotEmpty(),
            "the New task screen has no details box",
        )
        // Matt: "fuck the title field". The details are the only box.
        assertTrue(
            rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1,
            "the New task screen has more than the details to type in: " +
                rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.says() },
        )
    }

    @Test
    fun sendDoesNothingUntilThereAreDetailsAndThenGoesWithNothingElse() {
        shell(admin.startingATask())
        rule.onNodeWithTag("new-task-send").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, sends, "Send went with nothing typed")

        rule.onNodeWithTag("new-task-details").performTextInput("They are too small to hit.")
        rule.waitForIdle()
        assertEquals("They are too small to hit.", held!!.value.newTask.details)
        rule.onNodeWithTag("new-task-send").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, sends, "Send did not go with only the details typed")
    }

    @Test
    fun filesAreListedWithTheirSizeAndOneComesOffWithRemove() {
        val start = admin.startingATask().let {
            it.copy(
                newTask = it.newTask.attach(
                    listOf(
                        TaskFile("shot.png", "image/png", 820_000, "aGVsbG8="),
                        TaskFile("log.txt", "text/plain", 300, "aGVsbG8="),
                    ),
                ),
            )
        }
        shell(start)
        val rows = texts("task-file")
        assertEquals(2, rows.size, "the files are not listed: $rows")
        assertTrue("shot.png" in rows[0] && "820 KB" in rows[0], rows[0])

        rule.onNodeWithTag("task-file-remove-shot.png").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("log.txt"), held!!.value.newTask.files.map { it.name })
    }

    @Test
    fun addFilesOpensThePicker() {
        shell(admin.startingATask())
        rule.onNodeWithTag("new-task-pick").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, picks, "Add files did not open the picker")
    }
}
