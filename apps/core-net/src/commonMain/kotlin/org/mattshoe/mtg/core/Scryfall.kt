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

    @Serializable
    private data class Part(
        val id: String = "",
        val name: String = "",
        val component: String = "",
        @SerialName("type_line") val typeLine: String = "",
    )

    @Serializable
    private data class Purchase(val tcgplayer: String? = null)

    @Serializable
    private data class Card(
        val id: String = "",
        val name: String = "",
        @SerialName("type_line") val typeLine: String = "",
        val power: String? = null,
        val toughness: String? = null,
        val colors: List<String> = emptyList(),
        @SerialName("all_parts") val allParts: List<Part> = emptyList(),
        @SerialName("purchase_uris") val purchase: Purchase? = null,
    )

    @Serializable
    private data class Collection(val data: List<Card> = emptyList())

    @Serializable
    private data class Identifier(val id: String)

    @Serializable
    private data class CollectionRequest(val identifiers: List<Identifier>)

    /**
     * The tokens a set of cards makes, as real cards.
     *
     * Scryfall names every card's token components in `all_parts`,
     * which is the authoritative answer — reading them out of the
     * rules text, which is what this did first, produced tokens
     * called "Or more" and could never find the art.
     *
     * Seventy-five identifiers per request is Scryfall's limit, so a
     * hundred-card deck is two calls. A failure is an empty list: a
     * deck page that loses its token row is worse than one that
     * never had it, and neither is worth an error.
     */
    suspend fun tokens(scryfallIds: List<String>): List<TokenCard> {
        val ids = scryfallIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList()
        // Which token ids the deck's cards refer to, and how many
        // cards refer to each.
        val refs = mutableMapOf<String, Int>()
        ids.chunked(COLLECTION_MAX).forEach { chunk ->
            fetch(chunk).forEach { card ->
                card.allParts
                    .filter { it.component == "token" && it.id.isNotBlank() }
                    .map { it.id }
                    .distinct()
                    .forEach { id -> refs[id] = (refs[id] ?: 0) + 1 }
            }
        }
        if (refs.isEmpty()) return emptyList()

        // And then the tokens themselves. `all_parts` gives a name
        // and a type line and nothing else, so without this a 1/1
        // white Bird and a 2/2 blue Bird are the same row — and the
        // same Bird printed in four sets is four of them.
        val byIdentity = mutableMapOf<String, TokenCard>()
        refs.keys.chunked(COLLECTION_MAX).forEach { chunk ->
            fetch(chunk).forEach { card ->
                val token = TokenCard(
                    id = card.id,
                    name = card.name,
                    typeLine = card.typeLine,
                    power = card.power,
                    toughness = card.toughness,
                    colors = card.colors.sorted().joinToString(""),
                    tcgplayer = card.purchase?.tcgplayer?.takeIf { it.isNotBlank() },
                    madeBy = refs[card.id] ?: 1,
                )
                val had = byIdentity[token.identity]
                byIdentity[token.identity] =
                    if (had == null) token else had.copy(madeBy = had.madeBy + token.madeBy)
            }
        }
        return byIdentity.values
            .sortedWith(compareByDescending<TokenCard> { it.madeBy }.thenBy { it.name })
    }

    private suspend fun fetch(ids: List<String>): List<Card> = try {
        val text = http.post("$BASE/cards/collection") {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
            setBody(
                json.encodeToString(
                    CollectionRequest.serializer(),
                    CollectionRequest(ids.map { Identifier(it) }),
                ),
            )
        }.bodyAsText()
        json.decodeFromString<Collection>(text).data
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Leaving the row out beats an error over a deck that
        // otherwise loaded.
        emptyList()
    }

    companion object {
        /** Scryfall takes seventy-five identifiers in one call. */
        const val COLLECTION_MAX = 75

        /** Over a caller-supplied engine. See `MtgApi.withEngine`. */
        fun withEngine(http: HttpClient): Scryfall = Scryfall(http)

        const val BASE = "https://api.scryfall.com"
        const val USER_AGENT = "mtg-collection/1.0 (+https://mtg.mattshoe.org)"
        const val MIN_TERM = 2
    }
}
