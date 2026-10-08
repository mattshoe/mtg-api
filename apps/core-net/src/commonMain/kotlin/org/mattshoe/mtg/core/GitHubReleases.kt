package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The builds that shipped, straight from GitHub's releases, and the
 * intake requests in flight, straight from its branches and pull
 * requests.
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
        return Releases.decode(list(URL))
    }

    /** Every intake request GitHub can see, and where it is. */
    suspend fun tasks(): List<Task> {
        val pulls = list(PULLS)
        val refs = list(REQUEST_REFS)
        val done = list(DONE)
        return Tasks.decode(pulls, refs, done)
    }

    private suspend fun list(url: String): String {
        val text = plainGet(http, url, accept = "application/vnd.github+json", userAgent = USER_AGENT)
        if (!text.trimStart().startsWith("[")) error(refusal(text))
        return text
    }

    private fun refusal(text: String): String = try {
        (Json.parseToJsonElement(text) as JsonObject)["message"]!!.jsonPrimitive.content
    } catch (e: Exception) {
        "GitHub did not answer with a list"
    }

    companion object {
        /** Over a caller-supplied engine, for the shells' own tests. */
        fun withEngine(http: HttpClient): GitHubReleases = GitHubReleases(http)

        private const val URL = "https://api.github.com/repos/mattshoe/mtg-api/releases?per_page=50"
        private const val REPO = "https://api.github.com/repos/mattshoe/mtg-api"

        /** Most recently updated first, so what falls off past 100 is old and finished. */
        private const val PULLS = "$REPO/pulls?state=all&sort=updated&direction=desc&per_page=100"

        /** Only the request branches, unpaginated. Merged ones are never deleted. */
        private const val REQUEST_REFS = "$REPO/git/matching-refs/heads/request/"

        private const val DONE = "$REPO/contents/requests/done"
        private const val USER_AGENT = "mtg-collection (mattshoe/mtg-api)"
    }
}
