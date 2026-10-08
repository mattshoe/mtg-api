package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
) {
    /** `2026-10-03 12:30 UTC`, as GitHub gave it, and said so. */
    val finished: String? get() = finishedAt?.take(16)?.replace('T', ' ')?.plus(" UTC")
}

/** The tasks panel on Admin Settings. */
data class Tasks(
    val rows: List<Task> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Matt: "I want the done ones minimized by default but still browsable". */
    val showDone: Boolean = false,
) {
    val active: List<Task> get() = rows.filterNot { it.status.finished }
        .sortedWith(compareBy<Task> { it.status.ordinal }.thenBy { it.title.lowercase() })

    /** "ordered by the time which they completed, most recent first". */
    val done: List<Task> get() = rows.filter { it.status.finished }.sortedByDescending { it.finishedAt.orEmpty() }

    fun toggleDone() = copy(showDone = !showDone)

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
