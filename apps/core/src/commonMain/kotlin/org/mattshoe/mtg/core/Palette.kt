package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One row of the quick find list. */
data class Found(
    val id: Long,
    val name: String,
    val scryfallId: String?,
    val typeLine: String?,
    val qty: Int,
    val owner: String,
)

/**
 * Quick find: ⌘K, or `/`, or the Android search button.
 *
 * A port of the palette in `app.js`. Substring rather than prefix, and
 * across both faces, because the half of a double-faced card you
 * remember is rarely the front one.
 */
data class PaletteState(
    val term: String = "",
    val items: List<Found> = emptyList(),
    val active: Int = 0,
    val open: Boolean = false,
    val busy: Boolean = false,
) {
    val chosen: Found? get() = items.getOrNull(active)

    fun opened() = copy(open = true)

    /** Closing empties it, so it never reopens showing a stale answer. */
    fun closed() = PaletteState()

    fun typed(text: String) = copy(term = text, active = 0)

    fun found(rows: List<Found>) = copy(items = rows, active = 0, busy = false)

    fun down() = if (items.isEmpty()) this else copy(active = minOf(items.size - 1, active + 1))
    fun up() = if (items.isEmpty()) this else copy(active = maxOf(0, active - 1))
    fun highlight(i: Int) = if (i in items.indices) copy(active = i) else this

    /** Worth a round trip. Anything blank would match the whole table. */
    val worthAsking: Boolean get() = term.isNotBlank()

    fun query(): Sql = PaletteQueries.find(term)

    companion object {
        const val LIMIT = 12
    }
}

object PaletteQueries {

    /**
     * Shortest name first: typing "bolt" should offer Lightning Bolt
     * before Bolt Bend, and length is a better proxy for that than
     * anything the database knows.
     */
    fun find(term: String): Sql {
        val like = "%" + term.trim().lowercase() + "%"
        return Sql(
            """SELECT MIN(id) AS id, name, scryfall_id, type_line, SUM(qty) AS qty, owner
                 FROM cards
                WHERE name_norm LIKE ? OR lower(face1) LIKE ? OR lower(face2) LIKE ?
                GROUP BY owner, name_norm
                ORDER BY length(name), name
                LIMIT ${PaletteState.LIMIT}""",
            listOf(like, like, like),
        )
    }

    fun decode(cols: List<String>, rows: List<JsonArray>): List<Found> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        fun JsonArray.str(n: String): String? {
            val v = at[n]?.let { getOrNull(it) } ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }
        return rows.map {
            Found(
                id = it.str("id")?.toLongOrNull() ?: 0,
                name = it.str("name").orEmpty(),
                scryfallId = it.str("scryfall_id"),
                typeLine = it.str("type_line"),
                qty = it.str("qty")?.toIntOrNull() ?: 0,
                owner = it.str("owner").orEmpty(),
            )
        }
    }
}
