package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everybody with a collection, as tiles to pick one from.
 *
 * Matt: "i need a way to browse any user's cards ... the landing page
 * should be a collection of 'cards' or 'tiles' or whatever, with a
 * profile Pic on it and stuff, stylish and attractive. kinda like how
 * the Guide page has things".
 *
 * `#/users` is the tiles and `#/users/<key>` is that person's Library,
 * so your own Library tab stays your own while you look at somebody
 * else's. Reading any collection is already open to anybody; a key is
 * an address, and nothing here decides who may write.
 */
data class Collector(
    /** The public key their collection is shared by. */
    val key: String,
    val name: String?,
    val avatar: String?,
    /** Copies, not distinct cards: what a person means by how many cards they have. */
    val cards: Int,
    val decks: Int,
) {
    /** What to call them: their name, or their key, as everywhere else an owner is named. */
    val shownName: String get() = name?.takeIf { it.isNotBlank() } ?: key

    /** The letter drawn where there is no picture. */
    val initial: String get() = shownName.first().uppercase()

    /** "1,234 cards · 5 decks". */
    val blurb: String
        get() = "${grouped(cards.toLong())} ${if (cards == 1) "card" else "cards"} · " +
            "$decks ${if (decks == 1) "deck" else "decks"}"
}

/** The Users tab: who there is to look at. */
data class UsersState(
    val rows: List<Collector> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** Nobody to show, and nothing on its way. */
    val isEmpty: Boolean get() = !busy && error == null && rows.isEmpty()

    fun loading() = copy(busy = true, error = null)
    fun loaded(found: List<Collector>) = copy(rows = found, busy = false, error = null)

    /** Nothing stale under an error, the same as Admin Settings' list. */
    fun failed(message: String) = copy(rows = emptyList(), busy = false, error = message)

    fun find(key: String): Collector? = rows.firstOrNull { it.key == key }

    /** The list with one account left out, or all of it when there is nobody to leave out. */
    fun without(key: String?): UsersState =
        if (key.isNullOrEmpty()) this else copy(rows = rows.filterNot { it.key == key })
}

object UserQueries {
    /**
     * Every account that is a person, biggest collection first.
     *
     * No email: this goes over the open `/query`, and a list of
     * collections is not a list of addresses. The intake agent's
     * service account is not a collection anybody wants to browse.
     */
    fun all() = Sql(
        """SELECT u.key, u.display_name AS name, u.avatar_url AS avatar,
                  (SELECT COALESCE(SUM(c.qty), 0) FROM cards c WHERE c.owner_id = u.id) AS cards,
                  (SELECT COUNT(*) FROM decks d WHERE d.owner_id = u.id) AS decks
             FROM users u
            WHERE u.role <> 'agent'
            ORDER BY cards DESC, COALESCE(u.display_name, u.key) COLLATE NOCASE""",
        emptyList(),
    )

    fun decode(cols: List<String>, rows: List<JsonArray>): List<Collector> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        fun JsonArray.str(n: String): String? {
            val v = at[n]?.let { getOrNull(it) } ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }
        return rows.mapNotNull {
            val key = it.str("key")?.takeIf { k -> k.isNotEmpty() } ?: return@mapNotNull null
            Collector(
                key = key,
                name = it.str("name"),
                avatar = it.str("avatar")?.takeIf { a -> a.isNotBlank() },
                cards = it.str("cards")?.toDoubleOrNull()?.toInt() ?: 0,
                decks = it.str("decks")?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }
    }
}
