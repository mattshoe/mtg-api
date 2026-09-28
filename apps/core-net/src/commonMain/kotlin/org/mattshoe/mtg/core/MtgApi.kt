package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The collection API.
 *
 * One client for every platform. Ktor picks the engine — OkHttp on
 * Android and the JVM, fetch on the web, NSURLSession on iOS when that
 * arrives — and nothing above this line has to know which.
 */
class MtgApi internal constructor(
    private val base: String,
    private val http: HttpClient,
) {

    /**
     * Ktor stays out of this signature on purpose.
     *
     * The engine is an implementation detail — OkHttp here, fetch there,
     * NSURLSession later — and putting `HttpClient` in the public
     * constructor would force every consumer of this module to depend on
     * Ktor to say the word. Tests reach the internal constructor because
     * they live in the same module.
     */
    constructor(base: String = DEFAULT_BASE) : this(base, defaultClient())

    @Serializable
    private data class UnlockRequest(val password: String)

    @Serializable
    private data class CardsRequest(
        val owner: String,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class ErrorBody(val error: String = "")

    /** Password in, token out. The password is never kept. */
    suspend fun unlock(password: String): String {
        val res = http.post("$base/admin") {
            contentType(ContentType.Application.Json)
            setBody(UnlockRequest(password))
        }
        val body: Unlocked = res.decode()
        if (body.token.isEmpty()) throw ApiFailure("the server sent back no token")
        return body.token
    }

    /**
     * Add or remove, previewed or committed.
     *
     * `dryRun` is the whole safety story: the server resolves the list
     * against Scryfall and reports what it would do without touching
     * anything. Whether it is allowed to be false is not decided here —
     * see `MassEntry.canApply`.
     */
    suspend fun cards(
        token: String,
        direction: Direction,
        owner: Owner,
        list: String,
        dryRun: Boolean,
    ): Applied {
        val res = http.post("$base${direction.path}") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(CardsRequest(owner.slug, list, dryRun))
        }
        return res.decode()
    }

    /**
     * The server's own words on a refusal, not a status code.
     *
     * Every endpoint answers a failure with `{"error": "..."}`, and that
     * sentence is almost always the useful thing — "wrong password",
     * "admin session expired", the SQLite message. Losing it behind
     * "HTTP 401" would be throwing away the only part worth reading.
     */
    private suspend inline fun <reified T> HttpResponse.decode(): T {
        val text = bodyAsText()
        if (status.isSuccess()) {
            return try {
                Companion.json.decodeFromString<T>(text)
            } catch (e: Exception) {
                throw ApiFailure("the reply could not be read: ${text.take(200)}")
            }
        }
        val said = try {
            Companion.json.decodeFromString<ErrorBody>(text).error
        } catch (e: Exception) {
            ""
        }
        throw ApiFailure(said.ifEmpty { "HTTP ${status.value}" })
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        const val DEFAULT_BASE = "https://mtg-api.mattshoe81.workers.dev"

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun defaultClient() = HttpClient {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                // Scryfall resolution on a long list is slow, and a
                // timeout firing mid-write would be worse than waiting.
                requestTimeoutMillis = 180_000
            }
        }
    }
}
