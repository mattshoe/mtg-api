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
    private data class DeckListRequest(
        val slug: String,
        val commander: String,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class DisassembleRequest(
        val slug: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class CreateDeckRequest(
        val name: String,
        val format: String,
        val owner: String,
        val commander: String?,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class ValidateRequest(val list: String)

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
            header(Idempotency.HEADER, Idempotency.key())
            setBody(CardsRequest(owner.slug, list, dryRun))
        }
        return res.decode()
    }

    /** One read. No token: reads are open. */
    suspend fun queryRaw(sql: String, params: List<Any?>): QueryResult {
        val res = http.post("$base/query") {
            contentType(ContentType.Application.Json)
            setBody(QueryRequest(sql, bind(params)))
        }
        return res.decode()
    }

    /**
     * Replace a deck's list. Moves real cards, so it is gated like a write.
     *
     * The commander is its own field rather than a line in the list: the
     * stored value carries hand-written prose after the name, and the
     * server only rewrites it when the name itself actually changed.
     */
    suspend fun setDeckList(
        token: String,
        slug: String,
        commander: String,
        list: String,
        dryRun: Boolean,
    ): DeckPlan {
        val res = http.post("$base/decks/list") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(DeckListRequest(slug, commander, list, dryRun))
        }
        return res.decode()
    }

    /** Delete a deck; its cards go back to the owner's bulk. */
    suspend fun disassemble(token: String, slug: String, dryRun: Boolean): Disassembly {
        val res = http.post("$base/decks/disassemble") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(DisassembleRequest(slug, dryRun))
        }
        return res.decode()
    }

    /** Create a deck, pulling from bulk and buying what bulk cannot cover. */
    suspend fun createDeck(
        token: String,
        name: String,
        format: String,
        owner: Owner,
        commander: String?,
        list: String,
        dryRun: Boolean,
    ): DeckPlan {
        val res = http.post("$base/decks/create") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(CreateDeckRequest(name, format, owner.slug, commander, list, dryRun))
        }
        return res.decode()
    }

    /**
     * Check a list of names before anything is written.
     *
     * The Worker checks the collection first, which is free and needs no
     * network, and only asks Scryfall about what is left.
     */
    suspend fun validateRaw(list: String): Validation {
        val res = http.post("$base/cards/validate") {
            contentType(ContentType.Application.Json)
            setBody(ValidateRequest(list))
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

        /**
         * A client over a caller-supplied engine, for tests in other
         * modules.
         *
         * Ktor stays out of the ordinary constructor so nothing has
         * to depend on it to say the word; this names it on purpose,
         * because the alternative was that the web shell — the
         * address bar, the history stack, what Close does — could
         * only be exercised against the real database, which means
         * not at all.
         */
        fun withEngine(base: String, http: HttpClient): MtgApi = MtgApi(base, http)

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** A client with no JSON plugin, for callers that parse by hand. */
        internal fun plainClient() = HttpClient {
            retries()
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }

        private fun defaultClient() = HttpClient {
            retries()
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

// --------------------------------------------------------------- query

/**
 * `/query` answers `{cols, rows, n}` — columns once, rows as arrays.
 *
 * Everything in the app that reads rather than writes goes through it:
 * the Library, decks, stats, the console. Reads need no token.
 */
@Serializable
data class QueryResult(
    val cols: List<String> = emptyList(),
    val rows: List<kotlinx.serialization.json.JsonArray> = emptyList(),
    val n: Int = 0,
)

@Serializable
private data class QueryRequest(
    val sql: String,
    val params: List<kotlinx.serialization.json.JsonElement> = emptyList(),
)

/** Bound values, as JSON, without pretending numbers are strings. */
private fun bind(values: List<Any?>): List<kotlinx.serialization.json.JsonElement> = values.map {
    when (it) {
        null -> kotlinx.serialization.json.JsonNull
        is Number -> kotlinx.serialization.json.JsonPrimitive(it)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(it)
        else -> kotlinx.serialization.json.JsonPrimitive(it.toString())
    }
}

suspend fun MtgApi.query(statement: Sql): QueryResult = queryRaw(statement.sql, statement.params)
