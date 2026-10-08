package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The builds that shipped, straight from GitHub's releases.
 *
 * Not through the Worker, for the reason `Scryfall` gives: the
 * repository is public, the API sends CORS headers, and the release is
 * the record of a build — `release.yml` cuts one on every merge to main
 * and puts the hand-written note in it. Copying that into D1 would be
 * a second record that could disagree with the first.
 */
class GitHubReleases internal constructor(private val http: HttpClient) {

    constructor() : this(MtgApi.plainClient())

    /** Newest first. Throws with GitHub's own words when it refuses. */
    suspend fun releases(): List<Release> {
        val text = plainGet(http, URL, accept = "application/vnd.github+json", userAgent = USER_AGENT)
        if (!text.trimStart().startsWith("[")) error(refusal(text))
        return Releases.decode(text)
    }

    private fun refusal(text: String): String = try {
        (Json.parseToJsonElement(text) as JsonObject)["message"]!!.jsonPrimitive.content
    } catch (e: Exception) {
        "GitHub did not answer with a list of releases"
    }

    private companion object {
        const val URL = "https://api.github.com/repos/mattshoe/mtg-api/releases?per_page=50"
        const val USER_AGENT = "mtg-collection (mattshoe/mtg-api)"
    }
}
