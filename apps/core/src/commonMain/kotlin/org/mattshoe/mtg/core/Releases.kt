package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One build, as the release notes on Admin Settings list it.
 *
 * Matt: "I want versioned release notes for every build. It should be
 * accessible via admin settings". Every merge to main cuts a GitHub
 * release in `release.yml`, so the release is the build and its tag is
 * the version: semver, bumped by the note (`scripts/next-version.mjs`),
 * with the build number beside it.
 *
 * The note is written by hand, one file per pull request under
 * `release-notes/`, and `release.yml` puts it into the release body.
 * Not generated from the PR text: #35's described four features its
 * diff did not contain.
 */
data class Release(
    val tag: String,
    /** `published_at`, as it came. Sorts as text because it is ISO. */
    val publishedAt: String,
    val note: String?,
) {
    /** `android-v2.1.0-296` reads as `2.1.0 (296)`, the way the release is titled. */
    val version: String
        get() = TAG.matchEntire(tag)?.let { "${it.groupValues[1]} (${it.groupValues[2]})" } ?: tag

    val date: String get() = publishedAt.take(10)

    val shownNote: String get() = note ?: "No note was written for this build."

    private companion object {
        val TAG = Regex("""android-v(.+)-(\d+)""")
    }
}

/** The release notes panel on Admin Settings. */
data class Releases(
    val rows: List<Release> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    fun loading() = copy(busy = true, error = null)

    fun loaded(found: List<Release>) = copy(rows = found, busy = false, error = null)

    /** Nothing stale under an error, for the reason `People.failed` gives. */
    fun failed(message: String) = copy(rows = emptyList(), busy = false, error = message)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The line `release.yml` signs every body with, which nobody wrote. */
        private val BOILERPLATE = Regex("""^Built from \S+\..*$""")

        /**
         * GitHub's `/releases` array in, newest first out.
         *
         * Anything unreadable — a rate-limit message is an object, not
         * an array — is no releases rather than a crash.
         */
        fun decode(body: String): List<Release> = try {
            (json.parseToJsonElement(body) as? JsonArray).orEmpty().map { row ->
                val o = row.jsonObject
                fun str(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                Release(
                    tag = str("tag_name").orEmpty(),
                    publishedAt = str("published_at").orEmpty(),
                    note = noteIn(str("body").orEmpty()),
                )
            }.sortedByDescending { it.publishedAt }
        } catch (e: Exception) {
            emptyList()
        }

        private fun noteIn(body: String): String? = body
            .replace("\r\n", "\n")
            .lines()
            .filterNot { BOILERPLATE.matches(it.trim()) }
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
