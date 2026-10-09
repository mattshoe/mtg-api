package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where a task is, in one word. Declared furthest first: a request
 * built twice is one task, at the furthest status any of its pull
 * requests reached.
 */
enum class TaskStatus(val word: String, val finished: Boolean) {
    DONE("done", true),
    IN_REVIEW("in review", false),
    CLOSED("closed", true),
    BUILDING("building", false),
}

/**
 * One intake request, as Admin Settings lists it.
 *
 * Matt: "I want to be able to see the status of ongoing tasks in the
 * app. Nothing too fancy just the literal status like hold or done or
 * whatever statuses you assign." A task is a `request/<slug>-<digest>`
 * branch (`branchFor` in `scripts/intake.mjs`) and the pull request its
 * builder opens from it. Held and queued requests live only on the
 * laptop that dispatches them, so GitHub cannot see them and neither
 * can this.
 */
data class Task(
    /** The branch, `request/...`. */
    val ref: String,
    val title: String,
    val status: TaskStatus,
    /** `merged_at` or `closed_at`, as it came. Sorts as text because it is ISO. */
    val finishedAt: String?,
    /**
     * The branch's first commit, as GitHub gave it. GitHub keeps no
     * record of when a branch was made, and a builder commits before
     * its first test run, so this is as near the start as it can see.
     */
    val startedAt: String? = null,
) {
    /**
     * Matt: "Done tasks should show how long they took, not a UTC
     * timestamp". First commit to merge or close; unknown for one that
     * finished before its start was kept and whose branch says nothing.
     */
    val took: String? get() {
        if (!status.finished) return null
        val end = finishedAt?.let { Tasks.epochMillis(it) } ?: return null
        return span(end)?.let { "took $it" }
    }

    /**
     * Matt: "show the elapsed time since the task started". Only while
     * it is still going: a finished one would count up forever.
     */
    fun elapsed(now: Long): String? = if (status.finished) null else span(now)

    private fun span(to: Long): String? {
        val start = startedAt?.let { Tasks.epochMillis(it) } ?: return null
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
) {
    val active: List<Task> get() = rows.filterNot { it.status.finished }
        .sortedWith(compareBy<Task> { it.status.ordinal }.thenBy { it.title.lowercase() })

    /** "ordered by the time which they completed, most recent first". */
    val done: List<Task> get() = rows.filter { it.status.finished }.sortedByDescending { it.finishedAt.orEmpty() }

    fun toggleDone() = copy(showDone = !showDone)

    fun at(now: Long) = copy(now = now)

    fun loading() = copy(busy = true, error = null)

    fun loaded(found: List<Task>) = copy(rows = found, busy = false, error = null)

    /** Nothing stale under an error, for the reason `People.failed` gives. */
    fun failed(message: String) = copy(rows = emptyList(), busy = false, error = message)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val PREFIX = "request/"

        /** The digest `branchFor` puts on the end. Branches from before it have none. */
        private val DIGEST = Regex("""-[0-9a-f]{7}$""")

        /**
         * GitHub's pull requests, `request/` refs and `requests/done/`
         * listing in; one task per branch out.
         *
         * A branch is never deleted on merge, so one whose pull request
         * has aged out of the list would read as building forever. A
         * request filed under `done/` is finished, so its branch with no
         * pull request left to say how is not shown at all.
         *
         * Anything unreadable — a rate-limit message is an object, not
         * an array — is no tasks rather than a crash.
         */
        fun decode(pulls: String, refs: String, done: String): List<Task> = try {
            val filed = array(done).mapNotNull { str(it.jsonObject, "name") }
                .filter { it.endsWith(".md") }
                .map { slugOf(it.removeSuffix(".md")) }
                .toSet()
            val fromPulls = array(pulls).mapNotNull { row ->
                val o = row.jsonObject
                val ref = (o["head"] as? JsonObject)?.let { str(it, "ref") } ?: return@mapNotNull null
                if (!ref.startsWith(PREFIX)) return@mapNotNull null
                val merged = str(o, "merged_at")
                val status = when {
                    merged != null -> TaskStatus.DONE
                    str(o, "state") == "open" -> TaskStatus.IN_REVIEW
                    else -> TaskStatus.CLOSED
                }
                Task(ref, str(o, "title") ?: titleOf(ref), status, merged ?: str(o, "closed_at"))
            }
                .groupBy { it.ref }
                .map { (_, tries) ->
                    tries.sortedWith(compareBy<Task> { it.status.ordinal }.thenByDescending { it.finishedAt.orEmpty() }).first()
                }
            val known = fromPulls.map { it.ref }.toSet()
            val building = array(refs).mapNotNull { str(it.jsonObject, "ref")?.removePrefix("refs/heads/") }
                .filter { it.startsWith(PREFIX) && it !in known }
                .filterNot { DIGEST.replace(it.removePrefix(PREFIX), "") in filed }
                .map { Task(it, titleOf(it), TaskStatus.BUILDING, null) }
            fromPulls + building
        } catch (e: Exception) {
            emptyList()
        }

        /**
         * When a branch's first commit was made, out of GitHub's compare
         * of `main...<branch>`, which lists commits oldest first.
         * Anything else — nothing ahead, a refusal — is not known.
         */
        fun firstCommitAt(compare: String): String? = try {
            val commits = json.parseToJsonElement(compare).jsonObject["commits"] as? JsonArray
            commits?.firstOrNull()?.jsonObject?.get("commit")?.jsonObject
                ?.get("author")?.jsonObject?.let { str(it, "date") }
        } catch (e: Exception) {
            null
        }

        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z""")

        /** GitHub's `2026-10-08T09:15:00Z` as millis since the epoch. Always UTC, so no library. */
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

        private const val STARTS = "task-starts"

        /**
         * Every finished task's start, kept between launches. It never
         * changes once the task is done, and GitHub allows sixty unsigned
         * asks an hour, so each is asked about once rather than on every
         * visit. A running one is asked again: it is one ask, not dozens.
         */
        fun saveStarts(store: Store, tasks: List<Task>) {
            val kept = knownStarts(store) + tasks.mapNotNull { t ->
                t.startedAt?.takeIf { t.status.finished }?.let { t.ref to it }
            }
            store.put(STARTS, JsonObject(kept.mapValues { JsonPrimitive(it.value) }).toString())
        }

        /** Branch to first commit, as kept. Nothing readable is nothing known. */
        fun knownStarts(store: Store): Map<String, String> = try {
            store.get(STARTS)?.let { json.parseToJsonElement(it).jsonObject }
                ?.mapNotNull { (ref, at) -> (at as? JsonPrimitive)?.content?.let { ref to it } }
                ?.toMap()
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        private fun array(body: String): JsonArray =
            (json.parseToJsonElement(body) as? JsonArray) ?: JsonArray(emptyList())

        private fun str(o: JsonObject, k: String) =
            o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

        private fun titleOf(ref: String) =
            DIGEST.replace(ref.removePrefix(PREFIX), "").replace('-', ' ')

        /** The slug half of `branchFor`, without the digest Kotlin has no sha1 for. */
        private fun slugOf(name: String): String {
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return if (slug.length > 84) slug.take(84).trimEnd('-') else slug
        }
    }
}
