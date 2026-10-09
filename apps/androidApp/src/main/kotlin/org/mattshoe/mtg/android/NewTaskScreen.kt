package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.NewTask

/**
 * Admin Settings' New task screen, on Android. Sibling of the web's
 * `NewTaskPage`.
 *
 * Matt: "I want to be able to tap a "new task" button and get a simple
 * but attractive new screen where i can enter the details and upload
 * files". The details, files, and Send, no title: the Worker makes one
 * from the details. The rules — what is required, how many files, how big — are `NewTask`'s.
 */
@Composable
fun NewTaskScreen(
    task: NewTask,
    onState: (NewTask) -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Ghost("← Admin Settings", enabled = !task.busy) { onCancel() }

        Panel(head = "New task", note = "Say what you want changed. It becomes a request and builds like any other.") {
            Label("Details")
            Field(
                value = task.details,
                onValueChange = { onState(task.described(it)) },
                placeholder = "What happens now, what you want instead, and where",
                modifier = Modifier.heightIn(min = 150.dp).testTag("new-task-details"),
                singleLine = false,
            )

            Label("Files")
            Box(Modifier.testTag("new-task-pick")) {
                Btn("Add files", enabled = !task.busy) { onPickFile() }
            }
            task.files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("task-file"),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Line(f.name, Ink, Design.BODY, maxLines = 1, modifier = Modifier.weight(1f))
                        Muted(f.shownSize)
                    }
                    Box(Modifier.testTag("task-file-remove-${f.name}")) {
                        Ghost("Remove", enabled = !task.busy) { onState(task.remove(f.name)) }
                    }
                }
            }
            Muted("Up to ${NewTask.MAX_FILES} files, 1.5 MB each. Screenshots are the usual thing.")

            task.error?.let { ErrBox(it) }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.testTag("new-task-send")) {
                    Primary(if (task.busy) "Sending…" else "Send task", enabled = task.canSend) { onSend() }
                }
                Ghost("Cancel", enabled = !task.busy) { onCancel() }
            }
        }
    }
}

/** `label`: the question a box is asking, above it. */
@Composable
private fun Label(text: String) {
    Line(text, Ink, Design.SMALL, FontWeight.SemiBold)
}
