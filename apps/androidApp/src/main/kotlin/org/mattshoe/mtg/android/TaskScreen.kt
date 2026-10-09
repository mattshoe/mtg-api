package org.mattshoe.mtg.android

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskDetail
import org.mattshoe.mtg.core.TaskFile
import org.mattshoe.mtg.core.isImage

/**
 * One task's own screen on Admin Settings, on Android. Sibling of the
 * web's `TaskPage`.
 *
 * Matt: "I want to be able to tap on a task and be taken to a details
 * page that shows the whole request and all information about it,
 * including any attachments and whatnot". What it says is `TaskDetail`'s;
 * this only lays it out.
 */
@Composable
fun TaskScreen(detail: TaskDetail, listed: Task?, now: Long, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val task = detail.task ?: listed
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("task-detail"),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Box(Modifier.testTag("task-detail-back")) { Ghost("← Admin Settings") { onBack() } }

        Panel(head = task?.title ?: "Task") {
            detail.copy(task = task).facts(now) { 0 }.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("task-detail-fact"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Line(label, Ink3, Design.MINI, modifier = Modifier.width(100.dp))
                    Line(value, Ink, Design.SMALL, modifier = Modifier.weight(1f))
                }
            }
            task?.pr?.let { url ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { uri.openUri(url) }
                        .semantics(mergeDescendants = true) {}
                        .testTag("task-detail-pull"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Line("Pull request", Ink3, Design.MINI, modifier = Modifier.width(100.dp))
                    Line(detail.pull?.let { "#$it" } ?: url, Accent, Design.SMALL)
                }
            }
            if (detail.busy) Line("Loading…", Ink3)
            detail.error?.let { Line("Could not load the task: $it", Bad, Design.SMALL) }
        }

        Panel(head = "Request") {
            val body = detail.body
            if (body != null) Line(body, Ink, Design.BODY, modifier = Modifier.testTag("task-detail-body"))
            else if (!detail.busy && detail.error == null) {
                Line(TaskDetail.NO_TEXT, Ink3, Design.SMALL, modifier = Modifier.testTag("task-detail-body"))
            }
        }

        if (detail.files.isNotEmpty()) {
            Panel(head = "Files") {
                detail.files.forEach { f -> FileRow(f) }
            }
        }
    }
}

@Composable
private fun FileRow(f: TaskFile) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {}.testTag("task-detail-file"),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(f.name, Ink, Design.BODY, maxLines = 1, modifier = Modifier.weight(1f))
        Line(f.shownSize, Ink3, Design.MINI)
    }
    if (f.isImage) {
        val picture = remember(f.data) {
            runCatching {
                val bytes = Base64.decode(f.data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        picture?.let {
            Image(
                bitmap = it,
                contentDescription = f.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp, max = 480.dp)
                    .border(1.dp, Line, RadiusSm)
                    .testTag("task-detail-image-${f.name}"),
            )
        }
    }
}
