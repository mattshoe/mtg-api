package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
        /** The collection's public key. Which one, never whether you may. */
        val collection: String,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class DeckListRequest(
        val key: String,
        val commander: String,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class DisassembleRequest(
        val key: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class CreateDeckRequest(
        val name: String,
        val format: String,
        val collection: String,
        val commander: String?,
        val list: String,
        @SerialName("dry_run") val dryRun: Boolean,
    )

    @Serializable
    private data class ValidateRequest(val list: String)

    @Serializable
    private data class RoleRequest(val key: String, val role: String)

    @Serializable
    private data class ErrorBody(val error: String = "")

    /**
     * Whose collection an address names.
     *
     * The key in `#/c/<key>` is public: this says whether anybody has
     * it, and what they are called. No credentials, because every
     * collection is public to read — and holding the key is still not
     * permission to write to it.
     */
    suspend fun collection(key: String): CollectionRef? {
        val res = http.get("$base/c/$key")
        if (!res.status.isSuccess()) return null
        return res.decode<CollectionRef>()
    }

    @Serializable
    data class CollectionRef(
        val key: String,
        val name: String? = null,
        val avatar: String? = null,
    )

    /**
     * Where to send somebody to sign in.
     *
     * Only the address is shared. The session itself is an HttpOnly
     * cookie, and how a platform gets one sent — `credentials:
     * 'include'` in a browser, a cookie jar or a bearer header on a
     * phone — differs enough that a single client would be pretending.
     */
    fun signInUrl(returnTo: String = ""): String =
        "$base/auth/google" + if (returnTo.isEmpty()) "" else "?return=$returnTo"

    /**
     * A Google ID token in, a session out.
     *
     * The phone's way in. Credential Manager hands the app a token
     * directly, so there is no code to exchange — and the session
     * comes back in the body rather than a cookie, because a phone
     * has nowhere good to keep one and already sends a bearer header
     * for everything else.
     */
    suspend fun signInWithGoogle(idToken: String): Session {
        val res = http.post("$base/auth/google/token") {
            contentType(ContentType.Application.Json)
            setBody(IdTokenRequest(idToken))
        }
        return res.decode()
    }

    /** Who that session is, as the server sees it. */
    suspend fun me(session: String): Account? {
        val res = http.get("$base/auth/me") { header("Authorization", "Bearer $session") }
        if (!res.status.isSuccess()) return null
        val body: Profile = res.decode()
        return body.key?.takeIf { it.isNotEmpty() }?.let {
            Account(key = it, name = body.name, avatar = body.avatar, role = body.role ?: "user")
        }
    }

    /**
     * Every account and its role, for the Admin Settings screen.
     *
     * Admin only, server-side. The client asks and the server
     * refuses: a screen the app declines to draw is an affordance,
     * and the refusal is the rule.
     */
    suspend fun people(session: String): List<Person> {
        val res = http.get("$base/admin/users") { header("Authorization", "Bearer $session") }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) throw ApiFailure(errorIn(text, res.status))
        return People.decode(text)
    }

    /** Hand the admin role out, or take it back. */
    suspend fun setRole(session: String, key: String, role: String) {
        val res = http.post("$base/admin/role") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $session")
            setBody(RoleRequest(key, role))
        }
        if (!res.status.isSuccess()) throw ApiFailure(errorIn(res.bodyAsText(), res.status))
    }

    /** End it, server-side as well as locally. */
    suspend fun signOut(session: String) {
        runCatching {
            http.post("$base/auth/logout") { header("Authorization", "Bearer $session") }
        }
    }

    @Serializable
    private data class IdTokenRequest(@SerialName("id_token") val idToken: String)

    @Serializable
    data class Session(
        val token: String,
        val key: String? = null,
        val name: String? = null,
        val avatar: String? = null,
        val role: String? = null,
    ) {
        val account: Account?
            get() = key?.takeIf { it.isNotEmpty() }?.let { Account(it, name, avatar, role ?: "user") }
    }

    @Serializable
    private data class Profile(
        val key: String? = null,
        val name: String? = null,
        val email: String? = null,
        val avatar: String? = null,
        val role: String? = null,
    )

    // `unlock(password)` was here: one shared secret exchanged for a
    // token that could write to anybody's cards. Neither app has a
    // password box any more — you sign in with Google — and the
    // nightly scripts talk to the Worker over plain HTTP in Python,
    // so nothing in Kotlin needed it.


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
        /**
         * Which collection, as its public key.
         *
         * Whether this session may write to it is the server's call,
         * made from the session and never from this.
         */
        collection: String,
        list: String,
        dryRun: Boolean,
    ): Applied {
        val res = http.post("$base${direction.path}") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(CardsRequest(collection, list, dryRun))
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
        key: String,
        commander: String,
        list: String,
        dryRun: Boolean,
    ): DeckPlan {
        val res = http.post("$base/decks/list") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(DeckListRequest(key, commander, list, dryRun))
        }
        return res.decode()
    }

    /** Delete a deck; its cards go back to the owner's bulk. */
    suspend fun disassemble(token: String, key: String, dryRun: Boolean): Disassembly {
        val res = http.post("$base/decks/disassemble") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(DisassembleRequest(key, dryRun))
        }
        return res.decode()
    }

    /** Create a deck, pulling from bulk and buying what bulk cannot cover. */
    @Serializable
    private data class RenameDeckRequest(
        val key: String,
        val name: String,
        @SerialName("dry_run") val dryRun: Boolean = false,
    )

    @Serializable
    data class Renamed(
        val renamed: Boolean = false,
        val key: String = "",
        val name: String = "",
        val was: String = "",
    )

    /** Rename a deck. Its key, and so its address, stays where it is. */
    suspend fun renameDeck(token: String, key: String, name: String): Renamed {
        val res = http.post("$base/decks/rename") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(RenameDeckRequest(key, name))
        }
        return res.decode()
    }

    suspend fun createDeck(
        token: String,
        name: String,
        format: String,
        /** Whose deck, as the collection's public key. */
        collection: String,
        commander: String?,
        list: String,
        dryRun: Boolean,
    ): DeckPlan {
        val res = http.post("$base/decks/create") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header(Idempotency.HEADER, Idempotency.key())
            setBody(CreateDeckRequest(name, format, collection, commander, list, dryRun))
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

    /**
     * The server's own words out of a failed reply.
     *
     * `decode` does this for the calls that have a body worth
     * parsing; these two read the text themselves, so the refusal
     * has to be unwrapped here rather than thrown away as a number.
     */
    private fun errorIn(text: String, status: HttpStatusCode): String {
        val said = try {
            Companion.json.decodeFromString<ErrorBody>(text).error
        } catch (e: Exception) {
            ""
        }
        return said.ifEmpty { "HTTP ${status.value}" }
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
