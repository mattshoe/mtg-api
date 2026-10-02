package org.mattshoe.mtg.web

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A server that answers according to what was actually asked.
 *
 * The suites used to share a stub that returned the same rows for
 * every query. That is worse than no server at all: a search that
 * stopped running, a filter that stopped being applied and a page
 * that stopped turning all produce the identical screen, so the test
 * sees nothing wrong. Every bug in the Library that reached a phone
 * in the last fortnight was invisible here for that one reason.
 *
 * This does not implement SQL. It implements enough of it that the
 * screen changes when the question changes: the bound parameters are
 * matched against a small collection, and `LIMIT`/`OFFSET` are
 * honoured. A query the app stops sending, or sends with the wrong
 * parameters, now shows up as different cards on the page.
 */
object FakeServer {

    /**
     * Twelve cards, chosen for the shapes that break things: two
     * owners, a card they both own, two artists, a foil, something
     * with no price, and names that overlap so a substring filter
     * has to actually discriminate.
     */
    data class Card(
        val id: Long,
        val owner: String,
        val name: String,
        val artist: String,
        val text: String,
        val typeLine: String,
        val flavor: String,
        val watermark: String,
        val rarity: String,
        val setCode: String,
        val qty: Int,
        val price: Double?,
    ) {
        val nameNorm: String get() = name.lowercase()
    }

    val collection = listOf(
        Card(1, "matt", "Lightning Bolt", "Christopher Rush", "deals 3 damage", "Instant", "kicks like a mule", "boros", "rare", "2x2", 4, 2.50),
        Card(2, "matt", "Lightning Greaves", "Steve Ellis", "haste and shroud", "Artifact", "swift of foot", "none", "uncommon", "cmm", 1, 3.10),
        Card(3, "matt", "Sol Ring", "Mike Bierek", "adds two colourless", "Artifact", "a ring of power", "none", "uncommon", "m3c", 3, 1.75),
        Card(4, "kayla", "Sol Ring", "Mike Bierek", "adds two colourless", "Artifact", "a ring of power", "none", "uncommon", "m3c", 1, 1.75),
        Card(5, "matt", "Vesuva", "John Avon", "enters as a copy", "Land", "the mountain remembers", "none", "rare", "slc", 2, 9.00),
        Card(6, "matt", "Vesuvan Mist", "Rebecca Guay", "turn all creatures", "Sorcery", "a fog of shapes", "none", "rare", "tsp", 1, 0.40),
        Card(7, "kayla", "Alela, Cunning Conqueror", "Wylie Beckert", "whenever you cast", "Creature", "the faerie queen", "none", "mythic", "woe", 1, 4.20),
        Card(8, "matt", "Abundance", "Steve Prescott", "choose land or nonland", "Enchantment", "the forest provides", "none", "rare", "cmm", 1, 7.60),
        Card(9, "kayla", "Krosan Grip", "Thomas M. Baxa", "destroy target artifact", "Instant", "roots take hold", "none", "uncommon", "c21", 1, 1.20),
        Card(10, "matt", "Blasphemous Act", "Daarken", "destroy all creatures", "Sorcery", "the sky went dark", "none", "rare", "tmc", 1, null),
        Card(11, "matt", "Chocobo Racetrack", "Yoshitaka Amano", "whenever a creature", "Land", "kweh", "none", "rare", "fin", 3, 0.90),
        Card(12, "kayla", "Planar Bridge", "Svetlin Velinov", "put a permanent", "Artifact", "a door between worlds", "none", "mythic", "2x2", 1, 5.00),
    )

    /** One row per card name, the way the real query groups it. */
    private fun grouped(cards: List<Card>): List<Pair<Card, Int>> =
        cards.groupBy { it.nameNorm }
            .map { (_, same) -> same.first() to same.sumOf { it.qty } }
            .sortedBy { it.first.nameNorm }

    /**
     * Every `%thing%` parameter has to match the card somewhere, and
     * every bare parameter has to match a field exactly.
     *
     * Deliberately not clever. It is enough that a different question
     * gets a different answer, which is the whole property the old
     * stub did not have.
     */
    private fun matching(params: List<String>): List<Card> = collection.filter { c ->
        val haystack = listOf(
            c.nameNorm, c.text, c.artist, c.typeLine, c.setCode, c.rarity, c.flavor, c.watermark,
        ).joinToString(" ") { it.lowercase() }
        params.all { raw ->
            val p = raw.lowercase()
            when {
                // Full-text search arrives as `oracle_text : "shroud"`,
                // which is a sentence about a word rather than the
                // word. Take the word.
                '"' in p -> p.substringAfter('"').substringBefore('"').let { it.isEmpty() || it in haystack }
                p.startsWith("%") || p.endsWith("%") -> p.trim('%').let { it.isEmpty() || it in haystack }
                p == "matt" || p == "kayla" -> c.owner == p
                else -> p in haystack || p.toDoubleOrNull() != null
            }
        }
    }

    private fun limitOf(sql: String, word: String, fallback: Int): Int =
        Regex("""$word\s+(\d+)""", RegexOption.IGNORE_CASE).find(sql)?.groupValues?.get(1)?.toInt() ?: fallback

    /** The `{cols, rows, n}` the app decodes, for a page of the Library. */
    fun libraryPage(sql: String, params: List<String>): String {
        val rows = grouped(matching(params))
        val size = limitOf(sql, "LIMIT", 100)
        val offset = limitOf(sql, "OFFSET", 0)
        val page = rows.drop(offset).take(size)
        val cols = listOf(
            "id", "owner", "name", "name_norm", "scryfall_id", "type_line",
            "rarity", "setcode", "artist", "qty", "printings", "free", "price",
        )
        val body = page.joinToString(",") { (c, qty) ->
            """[${c.id},"${c.owner}","${c.name}","${c.nameNorm}",null,"${c.typeLine}",""" +
                """"${c.rarity}","${c.setCode}","${c.artist}",$qty,1,$qty,${c.price ?: "null"}]"""
        }
        return """{"cols":${cols.joinToString(",", "[", "]") { "\"$it\"" }},"rows":[$body],"n":${page.size}}"""
    }

    fun libraryCount(params: List<String>): String =
        """{"cols":["n"],"rows":[[${grouped(matching(params)).size}]],"n":1}"""

    /** How many cards the whole fake collection shows unfiltered. */
    val total: Int get() = grouped(collection).size

    /** The bound parameters of a `/query` request body. */
    fun paramsOf(body: String): List<String> =
        runCatching {
            Json.parseToJsonElement(body).jsonObject["params"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())

    fun sqlOf(body: String): String =
        runCatching {
            Json.parseToJsonElement(body).jsonObject["sql"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
}
