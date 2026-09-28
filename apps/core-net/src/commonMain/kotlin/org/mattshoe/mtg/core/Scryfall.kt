package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Card name autocomplete, straight to Scryfall.
 *
 * Not through the Worker on purpose. Scryfall's autocomplete endpoint
 * sends `access-control-allow-origin: *` and is built for exactly this,
 * and a keystroke's worth of traffic has no business going through
 * Cloudflare's shared egress — which Scryfall rate-limits, and which is
 * already why the nightly price refresh runs on the Mac instead.
 */
class Scryfall internal constructor(private val http: HttpClient) {

    /**
     * Ktor stays out of this signature for the same reason it stays out
     * of `MtgApi`'s: naming the engine here would force every caller to
     * depend on it.
     */
    constructor() : this(MtgApi.plainClient())

    @Serializable
    private data class Names(val data: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Names that start like this. Two characters is the useful minimum. */
    suspend fun complete(term: String, limit: Int = 10): List<String> {
        if (term.trim().length < MIN_TERM) return emptyList()
        return try {
            val text = http.get("$BASE/cards/autocomplete") {
                parameter("q", term.trim())
                header("Accept", "application/json")
                // Scryfall answers 400 to a bare client library user
                // agent, and asks callers to say who they are. Their
                // CDN does the same to the card images.
                header("User-Agent", USER_AGENT)
            }.bodyAsText()
            json.decodeFromString<Names>(text).data.take(limit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The next keystroke replacing this lookup. Swallowing it
            // let the dead job come back and close the live list.
            throw e
        } catch (e: Exception) {
            // Being offline is not worth an error in a convenience.
            emptyList()
        }
    }

    companion object {
        const val BASE = "https://api.scryfall.com"
        const val USER_AGENT = "mtg-collection/1.0 (+https://mtg.mattshoe.org)"
        const val MIN_TERM = 2
    }
}
