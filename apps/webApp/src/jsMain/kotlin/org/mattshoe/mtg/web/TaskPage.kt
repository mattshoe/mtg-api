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
fun TaskPage(detail: TaskDetail, listed: Task?, now: Long, onBack: () -> Unit) {
    val task = detail.task ?: listed
    Div(attrs = { classes("page-head") }) {
        Button(attrs = {
            classes("btn", "sm", "ghost")
            onClick { onBack() }
        }) { Text("← Admin Settings") }
    }

    Div(attrs = {
        classes("panel", "task-detail")
        attr("data-task-detail", detail.key)
    }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text(task?.title ?: "Task") }
        }
        Div(attrs = { classes("panel-body") }) {
            detail.copy(task = task).facts(now).forEach { (label, value) ->
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

            if (detail.busy) Div(attrs = { classes("empty") }) { Text("Loading…") }
            detail.error?.let { Div(attrs = { classes("err") }) { Text("Could not load the task: $it") } }

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
