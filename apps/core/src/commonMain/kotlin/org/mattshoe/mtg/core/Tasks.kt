package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where a task is, in Matt's words, as whoever caused it wrote it to D1
 * when it happened. Live ones list in this order, the ones that sit
 * forever if nobody looks first; `finished` is Done, collapsed by
 * default; `running` is whether the clock since the start is counting.
 * There is no `stopped` (that is `paused` with a reason) and no
 * `failing` (red CI mid-run is still `in review`).
 */
enum class TaskStatus(val word: String, val finished: Boolean, val running: Boolean = false) {
    BLOCKED("blocked", false),
    PAUSED("paused", false),
    IN_PROGRESS("in progress", false, running = true),
    IN_REVIEW("in review", false, running = true),
    PENDING("pending", false),
    MERGED("merged", true),
    DEPLOYED("deployed", true),
    CANCELLED("cancelled", true),
    ;

    companion object {
        fun of(word: String): TaskStatus? = entries.firstOrNull { it.word == word }
    }
}

/**
 * One task, as Admin Settings lists it.
 *
 * Matt: "I want to be able to see the status of ongoing tasks in the
 * app. Nothing too fancy just the literal status like hold or done or
 * whatever statuses you assign." It is a row in D1 (`task_inbox`) and
 * the dispatcher on the laptop writes its status as it changes — see
 * `scripts/intake/task-status.mjs`. It used to be inferred here from
 * GitHub's branch names, which could not carry a task sent and not yet
 * built, or one cancelled, and was asked unauthenticated from the phone.
 */
data class Task(
    /** The row's key in D1. */
    val key: String,
    val title: String,
    val status: TaskStatus,
    /** When it finished, as the Worker wrote it. Sorts as text because it is ISO. */
    val finishedAt: String?,
    /** When a builder first started on it. */
    val startedAt: String? = null,
    /** When it entered this status. */
    val statusAt: String? = null,
    /** The detail of this status: why it is paused or blocked, the last thing that happened. */
    val note: String? = null,
    /** Its pull request's address, once it has one. */
    val pr: String? = null,
) {
    /**
     * The row's second line, so it answers "what is going on with this"
     * without asking: how long in this status while it is live, why,
     * and which pull request.
     */
    fun detail(now: Long): String? = listOfNotNull(
        if (status.finished) null else statusAt?.let { Tasks.epochMillis(it) }?.let { "for " + between(it, now) },
        note,
        pr?.substringAfterLast('/')?.takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() } }?.let { "PR #$it" },
    ).joinToString(" · ").ifEmpty { null }

    /**
     * Matt: "Done tasks should show how long they took, not a UTC
     * timestamp". Start to finish; unknown for one never started.
     */
    val took: String? get() {
        if (!status.finished) return null
        val end = finishedAt?.let { Tasks.epochMillis(it) } ?: return null
        return span(end)?.let { "took $it" }
    }

    /**
     * Matt: "show the elapsed time since the task started". Only while
     * it is being worked on: a finished or paused one would count up
     * forever.
     */
    fun elapsed(now: Long): String? = if (status.running) span(now) else null

    private fun span(to: Long): String? {
        val start = startedAt?.let { Tasks.epochMillis(it) } ?: return null
        return between(start, to)
    }

    private fun between(start: Long, to: Long): String {
        val minutes = ((to - start) / 60_000).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just started"
            hours < 1 -> "${minutes}m"
            days < 1 -> "${hours}h ${(minutes % 60).toString().padStart(2, '0')}m"
            else -> "${days}d ${hours % 24}h"
        }
    }
}

/** The tasks panel on Admin Settings. */
data class Tasks(
    val rows: List<Task> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Matt: "I want the done ones minimized by default but still browsable". */
    val showDone: Boolean = false,
    /** Millis since the epoch, off the shell's clock, for `Task.elapsed` to count to. */
    val now: Long = 0,
    /**
     * Asked and answered during this visit to the list, so a reload that
     * lands on the same route (the one after sign-in) does not ask again.
     * Leaving the list or pulling to refresh makes it stale.
     */
    val fresh: Boolean = false,
) {
    val active: List<Task> get() = rows.filterNot { it.status.finished }
        .sortedWith(compareBy<Task> { it.status.ordinal }.thenBy { it.title.lowercase() })

    /** "ordered by the time which they completed, most recent first". */
    val done: List<Task> get() = rows.filter { it.status.finished }.sortedByDescending { it.finishedAt.orEmpty() }

    fun toggleDone() = copy(showDone = !showDone)

    fun at(now: Long) = copy(now = now)

    fun loading() = copy(busy = true, error = null, fresh = false)

    fun loaded(found: List<Task>) = copy(rows = found, busy = false, error = null, fresh = true)

    fun stale() = copy(fresh = false)

    /** Nothing stale under an error, for the reason `People.failed` gives. */
    fun failed(message: String) = copy(rows = emptyList(), busy = false, error = message, fresh = false)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The Worker's `GET /tasks` in, one task per row out. A status
         * this build does not know is left out rather than guessed at,
         * and anything unreadable is no tasks rather than a crash.
         */
        fun decode(body: String): List<Task> = try {
            ((json.parseToJsonElement(body) as? JsonObject)?.get("tasks") as? JsonArray).orEmpty().mapNotNull { row ->
                val o = row.jsonObject
                val status = str(o, "status")?.let { TaskStatus.of(it) } ?: return@mapNotNull null
                Task(
                    key = str(o, "key").orEmpty(),
                    title = str(o, "title").orEmpty(),
                    status = status,
                    finishedAt = str(o, "finished_at"),
                    startedAt = str(o, "started_at"),
                    statusAt = str(o, "status_at"),
                    note = str(o, "note"),
                    pr = str(o, "pr"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z""")

        /** `2026-10-08T09:15:00.123Z` as millis since the epoch, to the second. Always UTC, so no library. */
        fun epochMillis(iso: String): Long? {
            val f = ISO.matchEntire(iso)?.groupValues?.drop(1)?.map { it.toLong() } ?: return null
            val (y, mo, d) = f
            val (h, mi, se) = f.drop(3)
            // Days from civil, Howard Hinnant's algorithm.
            val yy = if (mo <= 2) y - 1 else y
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = era * 146097 + doe - 719468
            return ((days * 24 + h) * 60 + mi) * 60_000 + se * 1000
        }

        private fun str(o: JsonObject, k: String) =
            o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    }
}
