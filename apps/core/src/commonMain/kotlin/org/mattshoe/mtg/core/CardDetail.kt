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
    /** Whose copy this is, as the owner's public key. A card is not one person's. */
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
    /** What to call the owner. */
    val ownerName: String = "",
)

data class DeckUse(
    val key: String,
    val name: String,
    val owner: String,
    val qty: Int,
    val role: String?,
    val isProxy: Boolean,
    val ownerName: String = "",
)

/**
 * One line of a card's legality, as the format sheet shows it.
 *
 * Statuses arrive from Scryfall as `legal`, `not_legal`, `banned` and
 * `restricted`. The chip used to know only the first one and paint
 * every other in alarm red, which said that a card merely absent from
 * Standard was as bad as one banned out of Legacy, and said it in
 * colour — the one channel the person reading this cannot use.
 */
data class Legality(val format: String, val status: String) {

    /** The status, however it was spelled, as the one token we compare. */
    private val key: String get() = status.trim().lowercase().replace(' ', '_')

    val legal: Boolean get() = key == "legal"
    val banned: Boolean get() = key == "banned"
    val restricted: Boolean get() = key == "restricted"

    /** The status as English. */
    val label: String get() = key.replace('_', ' ')

    /** The format as a name rather than a column value. */
    val formatLabel: String get() = format.trim().replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    /**
     * A shape in front of the word.
     *
     * Hue is not a channel this collection's owner has, so the chip
     * carries its status twice over — as a mark and as a word — and
     * the colour is only ever the third telling.
     */
    val mark: String get() = when {
        legal -> "✓"
        banned -> "✕"
        restricted -> "!"
        else -> "○"
    }

    /** Which of the stylesheet's chip tones paints it. */
    val tone: String get() = when {
        legal -> "ok"
        banned -> "bad"
        restricted -> "warn"
        else -> "off"
    }

    /** The whole chip, as it reads. */
    val chip: String get() = "$mark $formatLabel $label"

    /** Where it sits in the row. The formats people actually ask about, first. */
    val rank: Int get() = ORDER.indexOf(format.trim().lowercase()).let { if (it < 0) ORDER.size else it }

    companion object {
        /**
         * The order the row reads in, and the same order
         * `CardQueries.legalities` sorts by — so a chip row built from
         * anything else still comes out looking like the page.
         */
        val ORDER = listOf("commander", "modern", "legacy", "vintage", "standard", "pauper")
    }
}

/**
 * One ruling, as Gatherer publishes it: a day and a paragraph.
 *
 * The date arrives as whatever the import put in `published_at` —
 * usually an ISO day, sometimes a full timestamp, occasionally
 * nothing at all. Reading it is the type's job rather than every
 * screen's, so a phone and a browser cannot disagree about what a
 * malformed date looks like.
 */
data class Ruling(val date: String, val text: String) {

    /**
     * The day it was published, as a plain ISO date, or "" when the
     * stored value is not one.
     *
     * Blank rather than the raw string: "soon" or "0000-00-00" on
     * the front of a ruling reads like part of the ruling, and the
     * line is more useful with no date than with a wrong one.
     */
    val day: String
        get() {
            if (date.length < 10) return ""
            val d = date.substring(0, 10)
            if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) return ""
            val month = d.substring(5, 7).toInt()
            val dayOfMonth = d.substring(8, 10).toInt()
            if (month !in 1..12 || dayOfMonth !in 1..31) return ""
            return d
        }

    /** The ruling itself, without the whitespace the import left on it. */
    val body: String get() = text.trim()

    /** A ruling with no words in it is not a ruling. */
    val sayable: Boolean get() = body.isNotEmpty()
}

/**
 * What one person has of a card, and how much of it is spare.
 *
 * The page used to be scoped to a single owner — `#/card/matt:sol+ring`
 * — which meant Kayla's copies were invisible and the same card had
 * two different addresses. The card is the card; who owns how many is
 * something it says, not something it is.
 */
data class Holding(val owner: String, val owned: Int, val committed: Int, val ownerName: String = owner) {
    /** Copies no deck of theirs has claimed. Never negative. */
    val free: Int get() = (owned - committed).coerceAtLeast(0)

    /** More of their decks want it than they own. */
    val short: Int get() = (committed - owned).coerceAtLeast(0)
}

/**
 * The card itself: what is actually printed on it.
 *
 * Everything else in `CardDetail` is about the collection — which
 * printings are owned, who has them, which decks want them. None of
 * it is the card. Both platforms opened a card and showed its name,
 * its sets and its legality, and nowhere said what the card *does*:
 * no mana cost, no type line, no rules text, no power and toughness,
 * no flavour. The columns have been in `cards` since the first import
 * (`mana_cost`, `type_line`, `oracle_text`, `flavor_text`, `power`,
 * `toughness`, `loyalty`, `defense`) and nothing ever selected them.
 * `app.css` even still carried `.oracle` and `.flavor` rules with
 * nothing to style.
 *
 * One face. A double-faced card has a row per face in `card_faces`,
 * and [CardDetail.faces] holds them in printed order — index 0 is the
 * front. A normal card has exactly one.
 */
data class Face(
    val name: String = "",
    val manaCost: String = "",
    val typeLine: String = "",
    val oracleText: String = "",
    val flavorText: String = "",
    val power: String? = null,
    val toughness: String? = null,
    val loyalty: String? = null,
    val defense: String? = null,
) {
    /**
     * `3/4`, a Tarmogoyf's star over a star, `4` for a planeswalker,
     * `6` for a battle, or nothing at all.
     *
     * One string because the three are mutually exclusive on a real
     * card and every caller wants the same little box in the corner.
     * `*` is a legitimate power — Tarmogoyf's whole identity — which
     * is why this is text and not a number, and why the phone's
     * power box had to stop raising a digits-only keyboard (4.9).
     */
    val stats: String?
        get() = when {
            power != null && toughness != null -> "$power/$toughness"
            loyalty != null -> loyalty
            defense != null -> defense
            else -> null
        }

    /** Nothing printed on it at all, which means nothing to draw. */
    val blank: Boolean
        get() = manaCost.isBlank() && typeLine.isBlank() &&
            oracleText.isBlank() && flavorText.isBlank() && stats == null
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
    /**
     * What is printed on the card, front face first. Empty until the
     * face query comes back; a normal card has one, a double-faced
     * card has two.
     */
    val faces: List<Face> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** The front, which is the one a single-faced card is. */
    val face: Face? get() = faces.firstOrNull()
    val owned: Int get() = printings.sumOf { it.qty }

    /** Copies no deck has claimed. Proxies do not consume a real card. */
    val committed: Int get() = usedIn.filterNot { it.isProxy }.sumOf { it.qty }
    val free: Int get() = (owned - committed).coerceAtLeast(0)

    /** More decks want it than exist. Worth saying out loud. */
    val overCommitted: Boolean get() = committed > owned

    /**
     * The legality row, as it should be read.
     *
     * Ordered here rather than trusted from the query: the same list
     * reaches a phone, a browser and a test, and only one of those
     * three got it from `ORDER BY`. A format with nothing to say and
     * a format said twice both drop out, because a row that repeats
     * itself is a row nobody trusts.
     */
    val legalityChips: List<Legality>
        get() = legalities
            .filter { it.format.isNotBlank() && it.status.isNotBlank() }
            .distinctBy { it.format.trim().lowercase() }
            .sortedWith(compareBy({ it.rank }, { it.format.trim().lowercase() }))

    /** Playable somewhere. A card that is legal nowhere should say so. */
    val legalAnywhere: Boolean get() = legalityChips.any { it.legal }

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
            val names = (printings.map { it.owner to it.ownerName } + usedIn.map { it.owner to it.ownerName })
                .filter { it.second.isNotBlank() }
                .toMap()
            return owners.map { who ->
                Holding(
                    owner = who,
                    ownerName = names[who] ?: who,
                    owned = printings.filter { it.owner == who }.sumOf { it.qty },
                    committed = usedIn.filter { it.owner == who && !it.isProxy }.sumOf { it.qty },
                )
            }.sortedWith(compareByDescending<Holding> { it.owned }.thenBy { it.ownerName })
        }

    /**
     * The rulings as a person should read them: oldest first, each
     * one once, nothing blank.
     *
     * The query already orders by `published_at`, but the order on
     * the screen should not depend on which of four requests the
     * list arrived from, and the join through `oracle_id` hands back
     * the same ruling twice for a card with two faces. Undated ones
     * go last because there is nowhere else to put them.
     */
    val rulingsShown: List<Ruling>
        get() = rulings.filter { it.sayable }
            .distinctBy { it.day to it.body }
            .sortedWith(compareBy({ if (it.day.isEmpty()) 1 else 0 }, { it.day }))

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
        """SELECT c.id, c.name, c.face2, ${Owners.keyOf("c.owner_id")} AS owner,
                  ${Owners.nameOf("c.owner_id")} AS owner_name,
                  c.setcode, c.set_name, c.collector_number,
                  c.finish, c.qty, c.scryfall_id,
                  cp.price, cp.tcg_url
             FROM cards c
             LEFT JOIN card_prices cp ON cp.card_id = c.id
            WHERE c.name_norm = ?
            ORDER BY c.owner_id, c.released_at DESC, c.setcode, c.collector_number""",
        listOf(nameNorm),
    )

    /**
     * What is actually printed on the card.
     *
     * One row for a normal card, taken off any printing — the oracle
     * text, the type line and the mana cost belong to the `oracle_id`,
     * not to the particular copy somebody owns, so which printing it
     * comes from does not matter. The flavour text *does* vary by
     * printing, so this takes the newest, which is the one most likely
     * to be the copy in hand.
     *
     * Double-faced cards come from `card_faces`, which has a row per
     * face in printed order. The `UNION ALL` is so one query answers
     * both shapes: a DFC contributes its two face rows, and a normal
     * card contributes the `cards` row instead, guarded by
     * `NOT EXISTS` so a DFC never also emits the combined
     * "A // B" row that is not any one face.
     *
     * The `pick` CTE is load-bearing, not tidiness. Selecting
     * straight from `cards` returns one row per *printing owned* —
     * Sol Ring came back twice, the same text both times, because two
     * copies of it are in the collection. Narrowing to one printing
     * first is what makes this one card rather than a list.
     */
    fun face(nameNorm: String) = Sql(
        """WITH pick AS (
                SELECT id, name, mana_cost, type_line, oracle_text, flavor_text,
                       power, toughness, loyalty, defense
                  FROM cards WHERE name_norm = ?
                 ORDER BY released_at DESC LIMIT 1)
           SELECT cf.face_index, cf.name, cf.mana_cost, cf.type_line,
                  cf.oracle_text, cf.flavor_text, cf.power, cf.toughness,
                  cf.loyalty, cf.defense
             FROM card_faces cf JOIN pick ON cf.card_id = pick.id
            UNION ALL
           SELECT 99, pick.name, pick.mana_cost, pick.type_line,
                  pick.oracle_text, pick.flavor_text, pick.power, pick.toughness,
                  pick.loyalty, pick.defense
             FROM pick
            WHERE NOT EXISTS (SELECT 1 FROM card_faces f WHERE f.card_id = pick.id)
            ORDER BY 1
            LIMIT 2""",
        listOf(nameNorm),
    )

    fun decodeFaces(cols: List<String>, rows: List<JsonArray>): List<Face> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map { row ->
            Face(
                name = row.at(at, "name").orEmpty(),
                manaCost = row.at(at, "mana_cost").orEmpty(),
                typeLine = row.at(at, "type_line").orEmpty(),
                oracleText = row.at(at, "oracle_text").orEmpty(),
                flavorText = row.at(at, "flavor_text").orEmpty(),
                power = row.at(at, "power"),
                toughness = row.at(at, "toughness"),
                loyalty = row.at(at, "loyalty"),
                defense = row.at(at, "defense"),
            )
        }.filterNot { it.blank }
    }

    /** Every deck that wants it, whoever built it. */
    fun usedIn(nameNorm: String) = Sql(
        """SELECT d.key, d.name, ${Owners.keyOf("d.owner_id")} AS owner,
                  ${Owners.nameOf("d.owner_id")} AS owner_name, d.is_proxy, dc.qty, dc.role
             FROM deck_cards dc
             JOIN decks d ON d.id = dc.deck_id
            WHERE dc.name_norm = ?
            ORDER BY d.owner_id, d.name""",
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
                ownerName = row.at(at, "owner_name").orEmpty(),
                price = row.at(at, "price")?.toDoubleOrNull(),
                tcgplayer = row.at(at, "tcg_url"),
            )
        }
    }

    fun decodeUses(cols: List<String>, rows: List<JsonArray>): List<DeckUse> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map {
            DeckUse(
                key = it.at(at, "key").orEmpty(),
                name = it.at(at, "name").orEmpty(),
                owner = it.at(at, "owner").orEmpty(),
                ownerName = it.at(at, "owner_name").orEmpty(),
                qty = it.at(at, "qty")?.toIntOrNull() ?: 0,
                role = it.at(at, "role"),
                // SQLite has no booleans; 1 and 0 arrive as numbers.
                isProxy = it.at(at, "is_proxy")?.let { v -> v == "1" || v.equals("true", true) } ?: false,
            )
        }
    }
}
