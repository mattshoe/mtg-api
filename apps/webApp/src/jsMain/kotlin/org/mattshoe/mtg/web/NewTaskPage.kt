package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.mattshoe.mtg.core.NewTask
import org.w3c.files.File

/**
 * Admin Settings' New task page, on the web. Sibling of Android's
 * `NewTaskScreen`.
 *
 * Matt: "I want to be able to tap a "new task" button and get a simple
 * but attractive new screen where i can enter the details and upload
 * files". A title, the details, files, and Send. The rules — what is
 * required, how many files, how big — are `NewTask`'s.
 */
@Composable
fun NewTaskPage(
    task: NewTask,
    onState: (NewTask) -> Unit,
    onFiles: (List<File>) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Div(attrs = { classes("page-head") }) {
        Button(attrs = {
            classes("btn", "sm", "ghost")
            onClick { onCancel() }
        }) { Text("← Admin Settings") }
    }

    Div(attrs = {
        classes("panel", "new-task")
        attr("data-new-task", "")
    }) {
        Div(attrs = { classes("panel-head") }) { H2 { Text("New task") } }
        Div(attrs = { classes("panel-body") }) {
            Div(attrs = { classes("muted", "small", "new-task-lede") }) {
                Text("Say what you want changed. It becomes a request and builds like any other.")
            }

            Div(attrs = { classes("field") }) {
                Label(forId = "new-task-title") { Text("Title") }
                Input(InputType.Text, attrs = {
                    id("new-task-title")
                    placeholder("What should change, in a few words")
                    value("")
                })
            }

            Div(attrs = { classes("field") }) {
                Label(forId = "new-task-details") { Text("Details") }
                TextArea(value = task.details, attrs = {
                    id("new-task-details")
                    classes("prose")
                    rows(8)
                    placeholder("What happens now, what you want instead, and where")
                    onInput { onState(task.described(it.value)) }
                })
            }

            Div(attrs = { classes("field") }) {
                Label { Text("Files") }
                FileDrop(onFiles)
                task.files.forEach { f ->
                    Div(attrs = {
                        classes("task-file")
                        attr("data-task-file", f.name)
                    }) {
                        Span(attrs = { classes("task-file-name") }) { Text(f.name) }
                        Span(attrs = { classes("muted", "small") }) { Text(f.shownSize) }
                        Button(attrs = {
                            classes("btn", "sm", "ghost")
                            attr("aria-label", "Remove ${f.name}")
                            if (task.busy) disabled()
                            onClick { onState(task.remove(f.name)) }
                        }) { Text("Remove") }
                    }
                }
                Div(attrs = { classes("muted", "small") }) {
                    Text("Up to ${NewTask.MAX_FILES} files, 1.5 MB each. Screenshots are the usual thing.")
                }
            }

            task.error?.let { Div(attrs = { classes("err") }) { Text(it) } }

            Div(attrs = { classes("flex-wrap", "new-task-actions") }) {
                Button(attrs = {
                    classes("btn", "primary")
                    if (!task.canSend) disabled()
                    onClick { onSend() }
                }) { Text(if (task.busy) "Sending…" else "Send task") }
                Button(attrs = {
                    classes("btn", "ghost")
                    if (task.busy) disabled()
                    onClick { onCancel() }
                }) { Text("Cancel") }
            }
        }
    }
}
