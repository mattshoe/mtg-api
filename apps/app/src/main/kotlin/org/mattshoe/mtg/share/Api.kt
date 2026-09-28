package org.mattshoe.mtg.share

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The MTG collection API.
 *
 * `HttpURLConnection` and `org.json`, both in the framework, so the app has
 * no dependencies to resolve and nothing that can rot.
 */
object Api {

    const val BASE_DEFAULT = "https://mtg-api.mattshoe81.workers.dev"

    /** Overridable so the instrumentation tests can point at a local stub. */
    @Volatile
    var base: String = BASE_DEFAULT

    class Failed(message: String) : Exception(message)

    /** One preview or one write, as the server described it. */
    data class Applied(
        val applied: Boolean,
        val dryRun: Boolean,
        val resolved: Int,
        val failed: Int,
        val changes: List<Change>,
        val errors: List<String>,
        val notes: List<String>,
    )

    data class Change(
        val name: String,
        val set: String,
        val collectorNumber: String,
        val finish: String,
        val before: Int,
        val after: Int,
    )

    /** Password in, token out. Throws with the server's own words on refusal. */
    fun unlock(password: String): String {
        val body = JSONObject().put("password", password)
        val res = post("/admin", body.toString(), null)
        return res.optString("token").ifEmpty {
            throw Failed("the server sent back no token")
        }
    }

    /**
     * Add a list to someone's collection.
     *
     * `dryRun` is how the preview is taken. Nothing here ever writes
     * without having been previewed first — that is enforced by the screen,
     * not by this.
     */
    fun addCards(token: String, owner: String, list: String, dryRun: Boolean): Applied {
        val body = JSONObject()
            .put("owner", owner)
            .put("list", list)
            .put("dry_run", dryRun)
        return parseApplied(post("/cards/add", body.toString(), token), dryRun)
    }

    private fun parseApplied(res: JSONObject, dryRun: Boolean) = Applied(
        applied = res.optBoolean("applied", false),
        dryRun = res.optBoolean("dry_run", dryRun),
        resolved = res.optInt("resolved", 0),
        failed = res.optInt("failed", 0),
        changes = res.optJSONArray("changes").toChanges(),
        errors = res.optJSONArray("errors").toStrings(),
        notes = res.optJSONArray("notes").toStrings(),
    )

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private fun JSONArray?.toChanges(): List<Change> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val row = optJSONArray(i) ?: return@mapNotNull null
            Change(
                name = row.optString(0),
                set = row.optString(1),
                collectorNumber = row.optString(2),
                finish = row.optString(3),
                before = row.optInt(4),
                after = row.optInt(5),
            )
        }
    }

    private fun post(path: String, body: String, token: String?): JSONObject {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Scryfall resolution on a long list is not fast, and a timeout
            // that fires mid-write would be worse than waiting.
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw Failed("HTTP $code, and the reply was not JSON: ${text.take(200)}")
            }

            if (code !in 200..299) {
                throw Failed(json.optString("error").ifEmpty { "HTTP $code" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}
