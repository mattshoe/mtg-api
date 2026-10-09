package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One task's own page, `#/admin/task/<key>`.
 *
 * Matt: "I want to be able to tap on a task and be taken to a details
 * page that shows the whole request and all information about it,
 * including any attachments and whatnot".
 *
 * All of it is the task's row in D1, `GET /tasks/<key>`: the status and
 * why, its pull request, its times, the text it was sent with (or the
 * request file the dispatcher sent, for one written on the laptop) and
 * the files. The Worker and nothing else, like the list.
 */
data class TaskDetail(
    /** The row's key in D1. */
    val key: String,
    val busy: Boolean = false,
    val error: String? = null,
    /** The row as the list has it: title, status, why, times, pull request. */
    val task: Task? = null,
    /** The request file's name, once the laptop has one. */
    val name: String? = null,
    /** What was typed into New task, or the request file whole. */
    val details: String? = null,
    val createdAt: String? = null,
    val files: List<TaskFile> = emptyList(),
) {
    /** The request as Matt wrote it: no frontmatter, no heading, no paths on the laptop. Null when there is none. */
    val body: String? get() = details?.let { text ->
        val out = mutableListOf<String>()
        var titled = false
        var inFiles = false
        for (line in FRONTMATTER.replace(text.replace("\r\n", "\n"), "").lines()) {
            when {
                !titled && line.startsWith("# ") -> titled = true
                line.trim() == "## Files" -> inFiles = true
                inFiles && line.startsWith("## ") -> { inFiles = false; out += line }
                inFiles -> {}
                COMMENT.matches(line.trim()) -> {}
                else -> out += line
            }
        }
        out.joinToString("\n").trim().ifEmpty { null }
    }

    /** The pull request's number, out of its address. */
    val pull: Int? get() = task?.pr?.substringAfterLast('/')?.toIntOrNull()

    /**
     * Label and value, in the order both shells list them. Nothing is
     * listed that the row does not have.
     */
    fun facts(now: Long): List<Pair<String, String>> {
        val t = task ?: return emptyList()
        return listOfNotNull(
            "Status" to t.status.word,
            t.note?.let { "Why" to it },
            t.took?.let { "Took" to it.removePrefix("took ") },
            t.elapsed(now)?.let { "Running" to it },
            if (t.status.finished) null
            else t.statusAt?.let { Tasks.epochMillis(it) }?.let { "In this status" to Tasks.span(it, now) },
            name?.let { "Request" to "requests/$it.md" },
            createdAt?.let { shown(it) }?.let { "Sent" to it },
        )
    }

    fun loading() = copy(busy = true, error = null)

    fun failed(message: String) = copy(busy = false, error = message)

    companion object {
        /** `#/admin/task/<key>`. */
        const val ROUTE = "task"

        /** Said in place of the request when the row carries no text. */
        const val NO_TEXT = "No text was kept for this task. It was written on the laptop before the app kept requests."

        const val UNREADABLE = "the Worker's answer was not a task"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val COMMENT = Regex("""<!--.*-->""")

        /** `^` without MULTILINE is the start of the text, on the JVM and in JS alike; JS has no `\A`. */
        private val FRONTMATTER = Regex("""^---\n[\s\S]*?\n---\n""")

        /** The Worker's `GET /tasks/<key>` in. */
        fun decode(key: String, body: String): TaskDetail = try {
            val o = json.parseToJsonElement(body).jsonObject
            val status = str(o, "status")?.let { TaskStatus.of(it) }
            TaskDetail(
                key = key,
                task = status?.let {
                    Task(
                        key = key,
                        title = str(o, "title").orEmpty(),
                        status = it,
                        finishedAt = str(o, "finished_at"),
                        startedAt = str(o, "started_at"),
                        statusAt = str(o, "status_at"),
                        note = str(o, "note"),
                        pr = str(o, "pr"),
                    )
                },
                name = str(o, "name"),
                details = str(o, "details"),
                createdAt = str(o, "created_at"),
                files = (o["files"] as? JsonArray).orEmpty().map { it.jsonObject }.map { f ->
                    val data = str(f, "data").orEmpty()
                    TaskFile(str(f, "name").orEmpty(), str(f, "type").orEmpty(), base64Bytes(data), data)
                },
            )
        } catch (e: Exception) {
            TaskDetail(key).failed(UNREADABLE)
        }

        /** `2026-10-08T10:00:00.000Z` as `2026-10-08 10:00 UTC`. */
        private fun shown(iso: String) =
            if (Tasks.epochMillis(iso) == null) null else iso.take(16).replace('T', ' ') + " UTC"

        private fun base64Bytes(data: String): Long {
            val clean = data.trim()
            if (clean.isEmpty()) return 0
            return clean.length / 4L * 3 - clean.takeLast(2).count { it == '=' }
        }

        private fun str(o: JsonObject, k: String) =
            o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    }
}

/** Shown in place on a task's page rather than only listed by name. */
val TaskFile.isImage: Boolean get() = type.startsWith("image/")

/** The key of the task whose page is open, out of the address. Anybody can type one. */
val AppState.openTaskKey: String?
    get() = if (view != View.ADMIN || !route.rest.startsWith("${TaskDetail.ROUTE}/")) null
    else route.rest.removePrefix("${TaskDetail.ROUTE}/").ifEmpty { null }

/** The open task as the list has it, once the list has loaded. */
val AppState.openTask: Task? get() = openTaskKey?.let { key -> tasks.rows.firstOrNull { it.key == key } }

/** A task tapped on Admin Settings: its row at once, the rest on its way. Landing still checks the role. */
fun AppState.openingTask(task: Task): AppState =
    copy(taskDetail = TaskDetail(task.key, task = task, busy = true)).navigate(Route(View.ADMIN, "${TaskDetail.ROUTE}/${task.key}"))
