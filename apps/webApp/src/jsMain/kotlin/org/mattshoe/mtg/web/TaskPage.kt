package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskDetail
import org.mattshoe.mtg.core.isImage

/**
 * One task's own page on Admin Settings, on the web. Sibling of
 * Android's `TaskScreen`.
 *
 * Matt: "I want to be able to tap on a task and be taken to a details
 * page that shows the whole request and all information about it,
 * including any attachments and whatnot". What it says is `TaskDetail`'s;
 * this only lays it out.
 */
@Composable
fun TaskPage(
    detail: TaskDetail,
    listed: Task?,
    now: Long,
    onState: (TaskDetail) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val task = detail.task ?: listed
    Div(attrs = {
        classes("panel", "task-detail")
        attr("data-task-detail", detail.key)
    }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text(task?.title ?: "Task") }
        }
        Div(attrs = { classes("panel-body") }) {
            detail.copy(task = task).facts(now) { ms -> -kotlin.js.Date(ms.toDouble()).getTimezoneOffset() }.forEach { (label, value) ->
                Div(attrs = {
                    classes("fact")
                    attr("data-task-detail-fact", "")
                }) {
                    Span(attrs = { classes("fact-label") }) { Text(label) }
                    Span(attrs = { classes("fact-value") }) { Text(value) }
                }
            }
            task?.pr?.let { url ->
                Div(attrs = { classes("fact") }) {
                    Span(attrs = { classes("fact-label") }) { Text("Pull request") }
                    A(href = url, attrs = {
                        classes("fact-value")
                        attr("data-task-detail-pull", "")
                        attr("target", "_blank")
                        attr("rel", "noopener")
                    }) { Text(detail.pull?.let { "#$it" } ?: url) }
                }
            }

            CancelTask(detail.copy(task = task), onState, onCancel)

            if (detail.busy) Div(attrs = { classes("empty") }) { Text("Loading…") }
            detail.error?.let { Div(attrs = { classes("err") }) { Text("Could not load the task: $it") } }

            detail.copy(task = task).result?.let { result ->
                H3 { Text("Summary") }
                Div(attrs = { attr("data-task-detail-result", "") }) { Text(result) }
                detail.summary?.let { summary ->
                    Div(attrs = {
                        classes("task-detail-text")
                        attr("data-task-detail-summary", "")
                    }) { Text(summary) }
                }
            }

            H3 { Text("Request") }
            val body = detail.body
            if (body != null) {
                Div(attrs = {
                    classes("task-detail-text")
                    attr("data-task-detail-body", "")
                }) { Text(body) }
            } else if (!detail.busy && detail.error == null) {
                Div(attrs = {
                    classes("muted", "small")
                    attr("data-task-detail-body", "")
                }) { Text(TaskDetail.NO_TEXT) }
            }

            if (detail.files.isNotEmpty()) {
                H3 { Text("Files") }
                detail.files.forEach { f ->
                    Div(attrs = {
                        classes("task-file")
                        attr("data-task-file", f.name)
                    }) {
                        A(href = "data:${f.type};base64,${f.data}", attrs = {
                            classes("task-file-name")
                            attr("download", f.name)
                        }) { Text(f.name) }
                        Span(attrs = { classes("muted", "small") }) { Text(f.shownSize) }
                    }
                    if (f.isImage) {
                        Img(src = "data:${f.type};base64,${f.data}", alt = f.name, attrs = {
                            classes("task-detail-image")
                            attr("data-task-detail-image", f.name)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Matt: "i want the ability to cancel/abort tasks and any running agent
 * should be immediately killed". Asks first; the laptop kills the agent
 * off the row this writes. Sibling of Android's `CancelTask`.
 */
@Composable
private fun CancelTask(detail: TaskDetail, onState: (TaskDetail) -> Unit, onCancel: () -> Unit) {
    if (!detail.canCancel) return
    Div(attrs = { classes("task-cancel") }) {
        if (detail.confirming) {
            Div(attrs = {
                classes("small")
                attr("data-task-cancel-ask", "")
            }) { Text(TaskDetail.ASK) }
            Div(attrs = { classes("task-cancel-buttons") }) {
                Button(attrs = {
                    classes("btn", "sm", "danger")
                    onClick { onCancel() }
                }) { Text(TaskDetail.CONFIRM) }
                Button(attrs = {
                    classes("btn", "sm", "ghost")
                    onClick { onState(detail.keep()) }
                }) { Text(TaskDetail.KEEP) }
            }
        } else {
            Button(attrs = {
                classes("btn", "sm", "danger")
                if (detail.cancelling) attr("disabled", "")
                onClick { onState(detail.askToCancel()) }
            }) { Text(if (detail.cancelling) TaskDetail.CANCELLING else TaskDetail.CANCEL) }
        }
    }
}
