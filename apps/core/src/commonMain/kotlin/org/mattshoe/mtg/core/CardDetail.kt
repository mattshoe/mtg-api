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
    /** Whose copy this is. A card is not one person's. */
    val owner: String = "",
    /**
     * Both faces, as the card is actually named. Last and defaulted
     * because every caller writes these positionally and the name is
     * only needed by a card that arrived from a link.
     */
    val cardName: String = "",
    /**
     * What this printing is worth, for the finish it is in — a foil
     * and a nonfoil of the same card are not the same price, and
     * `card_prices` is the view that already knows that.
     */
    val price: Double? = null,
    /** Where to buy this printing. Already in the database. */
    val tcgplayer: String? = null,
)

data class DeckUse(
    val slug: String,
    val name: String,
    val owner: String,
    val qty: Int,
    val role: String?,
    val isProxy: Boolean,
)

/** One line of a card's legality, as the format sheet shows it. */
data class Legality(val format: String, val status: String) {
    val legal: Boolean get() = status == "legal"
    val label: String get() = status.replace('_', ' ')
}

data class Ruling(val date: String, val text: String)

/**
 * What one person has of a card, and how much of it is spare.
 *
 * The page used to be scoped to a single owner — `#/card/matt:sol+ring`
 * — which meant Kayla's copies were invisible and the same card had
 * two different addresses. The card is the card; who owns how many is
 * something it says, not something it is.
 */
data class Holding(val owner: String, val owned: Int, val committed: Int) {
    /** Copies no deck of theirs has claimed. Never negative. */
    val free: Int get() = (owned - committed).coerceAtLeast(0)

    /** More of their decks want it than they own. */
    val short: Int get() = (committed - owned).coerceAtLeast(0)
}

data class CardDetail(
    val name: String = "",
    /**
     * What the queries are keyed by. Held rather than derived: the
     * display name lowercased is not `name_norm` for anything with an
     * accent or an em dash in it, and the drawer has to be able to
     * write its own URL.
     */
    val nameNorm: String = "",
    val printings: List<Printing> = emptyList(),
    val usedIn: List<DeckUse> = emptyList(),
    val legalities: List<Legality> = emptyList(),
    val rulings: List<Ruling> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val owned: Int get() = printings.sumOf { it.qty }

    /** Copies no deck has claimed. Proxies do not consume a real card. */
    val committed: Int get() = usedIn.filterNot { it.isProxy }.sumOf { it.qty }
    val free: Int get() = (owned - committed).coerceAtLeast(0)

    /** More decks want it than exist. Worth saying out loud. */
    val overCommitted: Boolean get() = committed > owned

    /**
     * Who has how many, most copies first.
     *
     * Anyone who owns none of it but has a deck asking for it still
     * gets a line: nought owned against two wanted is the most
     * useful thing this page can tell you.
     */
    val byOwner: List<Holding>
        get() {
            val owners = (printings.map { it.owner } + usedIn.map { it.owner })
                .filter { it.isNotBlank() }
                .distinct()
            return owners.map { who ->
                Holding(
                    owner = who,
                    owned = printings.filter { it.owner == who }.sumOf { it.qty },
                    committed = usedIn.filter { it.owner == who && !it.isProxy }.sumOf { it.qty },
                )
            }.sortedWith(compareByDescending<Holding> { it.owned }.thenBy { it.owner })
        }

    fun loading() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)

    /**
     * The real name, once a printing has told us one.
     *
     * A card opened from a link has only its `name_norm`, which is
     * lowercase. Showing that as the title is ugly, and title-casing
     * it is wrong for "Jötun Grunt" and every card with a // in it.
     *
     * A row carrying nothing but the norm back is skipped rather than
     * taken: it is the thing we were trying to get away from, and
     * promoting it would undo a good name the palette already gave us.
     * Whatever the printings say is believed otherwise — the query
     * that fetched them is keyed `WHERE name_norm = ?`, so the row is
     * this card by construction, and second-guessing it here would
     * mean reimplementing the database's normalisation.
     */
    fun named(printings: List<Printing>): CardDetail {
        val real = printings.asSequence()
            .map { it.cardName.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { it != nameNorm }
        // Nothing better to show: an untitled drawer is worse than a
        // lowercase one.
        val next = real ?: name.trim().ifBlank { nameNorm }
        return if (next == name) this else copy(name = next)
    }
}

object CardQueries {

    /** Scryfall addresses art by the id already on the row. */
    fun art(scryfallId: String?, size: String = "normal"): String? {
        if (scryfallId.isNullOrBlank() || scryfallId.length < 2) return null
        val a = scryfallId[0]
        val b = scryfallId[1]
        return "https://cards.scryfall.io/$size/front/$a/$b/$scryfallId.jpg"
    }

    /**
     * A commander's art, cropped, for the band across a deck tile.
     *
     * Falls back to Scryfall's named-card image endpoint when the
     * collection has no printing to take an id from — which happens
     * for a proxy deck whose commander nobody owns.
     */
    fun banner(artId: String?, commanderName: String?): String? {
        art(artId, "art_crop")?.let { return it }
        val name = commanderName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return "https://api.scryfall.com/cards/named?exact=" +
            percent(name) + "&format=image&version=art_crop"
    }

    /** No `encodeURIComponent` on Android or iOS, so by hand. */
    private fun percent(s: String): String = buildString {
        s.encodeToByteArray().forEach { b ->
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && b.toInt() in 0..127 || c in "-_.~") append(c)
            else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }

    /** Where a card may be played, in the order people ask about. */
    fun legalities(nameNorm: String) = Sql(
        """SELECT l.format, l.status
             FROM legalities l
            WHERE l.oracle_id = (SELECT oracle_id FROM cards WHERE name_norm = ? LIMIT 1)
            ORDER BY CASE l.format
                       WHEN 'commander' THEN 0 WHEN 'modern' THEN 1
                       WHEN 'legacy' THEN 2 WHEN 'vintage' THEN 3
                       WHEN 'standard' THEN 4 WHEN 'pauper' THEN 5
                       ELSE 9 END, l.format""",
        listOf(nameNorm),
    )

    fun rulings(nameNorm: String) = Sql(
        """SELECT r.published_at, r.comment
             FROM rulings r
            WHERE r.oracle_id = (SELECT oracle_id FROM cards WHERE name_norm = ? LIMIT 1)
            ORDER BY r.published_at""",
        listOf(nameNorm),
    )

    fun decodeLegalities(cols: List<String>, rows: List<JsonArray>): List<Legality> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map {
            Legality(it.at(at, "format").orEmpty(), it.at(at, "status").orEmpty())
        }
    }

    fun decodeRulings(cols: List<String>, rows: List<JsonArray>): List<Ruling> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map {
            Ruling(it.at(at, "published_at").orEmpty(), it.at(at, "comment").orEmpty())
        }
    }

    /**
     * Every printing of it anybody owns.
     *
     * Not scoped to one person: the page shows who has how many, so
     * filtering by owner here would be asking the question twice and
     * answering it wrong the second time.
     *
     * `card_prices` rather than `prices`: it already works out which
     * of usd, usd_foil and usd_etched applies to the finish this copy
     * is in, and it carries the shop link.
     */
    fun printings(nameNorm: String) = Sql(
        """SELECT c.id, c.name, c.face2, c.owner, c.setcode, c.set_name, c.collector_number,
                  c.finish, c.qty, c.scryfall_id,
                  cp.price, cp.tcg_url
             FROM cards c
             LEFT JOIN card_prices cp ON cp.card_id = c.id
            WHERE c.name_norm = ?
            ORDER BY c.owner, c.released_at DESC, c.setcode, c.collector_number""",
        listOf(nameNorm),
    )

    /** Every deck that wants it, whoever built it. */
    fun usedIn(nameNorm: String) = Sql(
        """SELECT d.slug, d.name, d.owner, d.is_proxy, dc.qty, dc.role
             FROM deck_cards dc
             JOIN decks d ON d.id = dc.deck_id
            WHERE dc.name_norm = ?
            ORDER BY d.owner, d.name""",
        listOf(nameNorm),
    )

    private fun JsonArray.at(cols: Map<String, Int>, n: String): String? {
        val v = cols[n]?.let { getOrNull(it) } ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    fun decodePrintings(cols: List<String>, rows: List<JsonArray>): List<Printing> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map { row ->
            val front = row.at(at, "name").orEmpty()
            val back = row.at(at, "face2")
            Printing(
                id = row.at(at, "id")?.toLongOrNull() ?: 0,
                // Both faces, the way `CardRow.fullName` spells it, so
                // the drawer's title does not change depending on
                // whether it was opened from the grid or from a link.
                cardName = if (front.isNotBlank() && !back.isNullOrBlank() && "//" !in front) {
                    "$front // $back"
                } else {
                    front
                },
                setCode = row.at(at, "setcode").orEmpty(),
                setName = row.at(at, "set_name"),
                collectorNumber = row.at(at, "collector_number"),
                finish = row.at(at, "finish") ?: "nonfoil",
                qty = row.at(at, "qty")?.toIntOrNull() ?: 0,
                scryfallId = row.at(at, "scryfall_id"),
                owner = row.at(at, "owner").orEmpty(),
                price = row.at(at, "price")?.toDoubleOrNull(),
                tcgplayer = row.at(at, "tcg_url"),
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
