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

    /**
     * A keystroke.
     *
     * Falling back under the minimum empties the list as well as the
     * box. Deleting down to one character used to leave the previous
     * answer sitting there under a term that was never asked about,
     * which reads as a list that has stopped responding.
     */
    fun typed(text: String): PaletteState {
        val worth = text.trim().length >= MIN
        return copy(
            term = text,
            items = if (worth) items else emptyList(),
            active = 0,
            busy = worth,
        )
    }

    /**
     * An answer from the server.
     *
     * `forTerm` is the term that was asked about. A slow answer to a
     * term that has since been typed over is dropped rather than
     * shown: the request is debounced and cancelled upstream, but a
     * cancel that loses the race still delivers, and the row list
     * then disagreed with the box above it.
     */
    fun found(rows: List<Found>, forTerm: String = term): PaletteState =
        if (forTerm.trim() != term.trim()) this
        else copy(items = rows.take(LIMIT), active = 0, busy = false)

    /**
     * The highlight stops at both ends rather than wrapping. The
     * palette is a short list you scan, and wrapping past the end
     * reads as a bug.
     */
    fun down() = if (items.isEmpty()) this else copy(active = minOf(items.size - 1, active + 1))
    fun up() = if (items.isEmpty()) this else copy(active = maxOf(0, active - 1))
    fun highlight(i: Int) = if (i in items.indices) copy(active = i) else this

    /**
     * Worth a round trip.
     *
     * Blank would match the whole table, and one character matches
     * most of it — twelve rows chosen by name length out of forty
     * thousand is not an answer to anything.
     */
    val worthAsking: Boolean get() = term.trim().length >= MIN

    fun query(): Sql = PaletteQueries.find(term)

    companion object {
        const val LIMIT = 12

        /** Characters before it is worth asking. The same as autocomplete. */
        const val MIN = 2
    }
}

object PaletteQueries {

    /**
     * Shortest name first: typing "bolt" should offer Lightning Bolt
     * before Bolt Bend, and length is a better proxy for that than
     * anything the database knows.
     *
     * One pattern, bound once per column. Three separate LIKEs rather
     * than the filter panel's concatenated haystack because this is a
     * substring match on the whole typed string, spaces and all —
     * joining the columns with a space would let "bolt chain" match
     * the end of one face and the start of the next.
     */
    fun find(term: String): Sql {
        // The filter panel's escaping, so `%`, `_` and `\` are the
        // characters somebody typed rather than LIKE's own wildcards.
        val like = Clauses().like(term.trim())
        return Sql(
            """SELECT MIN(id) AS id, name, scryfall_id, type_line, SUM(qty) AS qty,
                      ${Owners.nameOf("owner_id")} AS owner
                 FROM cards
                WHERE name_norm LIKE ? ESCAPE '\'
                   OR lower(face1) LIKE ? ESCAPE '\'
                   OR lower(face2) LIKE ? ESCAPE '\'
                GROUP BY owner_id, name_norm
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
