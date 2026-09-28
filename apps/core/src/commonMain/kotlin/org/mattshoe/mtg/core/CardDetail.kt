package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * One card, opened.
 *
 * A port of the read side of `frontend/js/card.js`: every printing owned,
 * which decks want it, and how many copies are spare. The art URL is
 * derived rather than stored, because Scryfall addresses images by the id
 * already on the row.
 */
data class Printing(
    val id: Long,
    val setCode: String,
    val setName: String?,
    val collectorNumber: String?,
    val finish: String,
    val qty: Int,
    val scryfallId: String?,
)

data class DeckUse(
    val slug: String,
    val name: String,
    val owner: String,
    val qty: Int,
    val role: String?,
    val isProxy: Boolean,
)

data class CardDetail(
    val name: String = "",
    val owner: String = "",
    val printings: List<Printing> = emptyList(),
    val usedIn: List<DeckUse> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val owned: Int get() = printings.sumOf { it.qty }

    /** Copies no deck has claimed. Proxies do not consume a real card. */
    val committed: Int get() = usedIn.filterNot { it.isProxy }.sumOf { it.qty }
    val free: Int get() = (owned - committed).coerceAtLeast(0)

    /** More decks want it than exist. Worth saying out loud. */
    val overCommitted: Boolean get() = committed > owned

    fun loading() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
}

object CardQueries {

    /** Scryfall addresses art by the id already on the row. */
    fun art(scryfallId: String?, size: String = "normal"): String? {
        if (scryfallId.isNullOrBlank() || scryfallId.length < 2) return null
        val a = scryfallId[0]
        val b = scryfallId[1]
        return "https://cards.scryfall.io/$size/front/$a/$b/$scryfallId.jpg"
    }

    fun printings(nameNorm: String, owner: String) = Sql(
        """SELECT c.id, c.setcode, c.set_name, c.collector_number, c.finish, c.qty, c.scryfall_id
             FROM cards c
            WHERE c.name_norm = ? AND c.owner = ?
            ORDER BY c.released_at DESC, c.setcode, c.collector_number""",
        listOf(nameNorm, owner),
    )

    fun usedIn(nameNorm: String, owner: String) = Sql(
        """SELECT d.slug, d.name, d.owner, d.is_proxy, dc.qty, dc.role
             FROM deck_cards dc
             JOIN decks d ON d.id = dc.deck_id
            WHERE dc.name_norm = ? AND d.owner = ?
            ORDER BY d.name""",
        listOf(nameNorm, owner),
    )

    private fun JsonArray.at(cols: Map<String, Int>, n: String): String? {
        val v = cols[n]?.let { getOrNull(it) } ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    fun decodePrintings(cols: List<String>, rows: List<JsonArray>): List<Printing> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map {
            Printing(
                id = it.at(at, "id")?.toLongOrNull() ?: 0,
                setCode = it.at(at, "setcode").orEmpty(),
                setName = it.at(at, "set_name"),
                collectorNumber = it.at(at, "collector_number"),
                finish = it.at(at, "finish") ?: "nonfoil",
                qty = it.at(at, "qty")?.toIntOrNull() ?: 0,
                scryfallId = it.at(at, "scryfall_id"),
            )
        }
    }

    fun decodeUses(cols: List<String>, rows: List<JsonArray>): List<DeckUse> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map {
            DeckUse(
                slug = it.at(at, "slug").orEmpty(),
                name = it.at(at, "name").orEmpty(),
                owner = it.at(at, "owner").orEmpty(),
                qty = it.at(at, "qty")?.toIntOrNull() ?: 0,
                role = it.at(at, "role"),
                // SQLite has no booleans; 1 and 0 arrive as numbers.
                isProxy = it.at(at, "is_proxy")?.let { v -> v == "1" || v.equals("true", true) } ?: false,
            )
        }
    }
}
