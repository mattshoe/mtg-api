package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decks: the tiles, the detail, and the queries behind both.
 *
 * A port of the read side of `frontend/js/decks.js`. The writes — edit,
 * disassemble — go through the API's own endpoints and live in
 * `core-net`, because a deck edit moves real cards and is not something
 * a screen should be assembling SQL for.
 */
data class Deck(
    val slug: String,
    val name: String,
    val owner: String,
    val commander: String?,
    /** Alphabetical, as the database stores it: 'UW', never 'WU'. */
    val colors: String?,
    val bracket: Int?,
    /** The commander's art, for the banner across the top of a tile. */
    val artId: String?,
) {
    /**
     * The deck's colour identity as WUBRG letters.
     *
     * `decks.colors` was written by hand and is free text — "Simic
     * (Green/Blue)", "{W}{U}{B}{R} (Breya's identity)", "Five-color
     * (WUBRG)" — so reading it a character at a time turns a deck name
     * into a row of nonsense pips. Symbols first, then colour words,
     * then the five-colour shorthand.
     */
    val identity: String
        get() {
            val raw = colors.orEmpty()
            // Some rows are already just the letters, which is what the
            // database stores when a commander was looked up properly.
            val bare = raw.trim()
            if (bare.isNotEmpty() && LETTERS.matches(bare)) {
                return dedupe(bare.uppercase().map { it.toString() })
            }
            val syms = SYMBOL.findAll(raw).map { it.groupValues[1].uppercase() }.toList()
            if (syms.isNotEmpty()) return dedupe(syms)
            val words = WORD_RE.findAll(raw)
                .mapNotNull { WORDS[it.groupValues[1].lowercase()] }.toList()
            if (words.isNotEmpty()) return dedupe(words)
            if (FIVE.containsMatchIn(raw)) return "BGRUW"
            return ""
        }

    val colorPips: List<String> get() = identity.map { it.toString() }

    /** "Explorers of the Deep — ... Precon" is a tile-width name plus prose. */
    val title: String
        get() = name.split(DASH).firstOrNull()?.trim()?.ifEmpty { null } ?: name

    /**
     * The commander without its set annotation.
     *
     * Stored as "Alela, Artful Provocateur (ELD) 324" often enough that
     * matching on the whole string finds nothing.
     */
    val commanderName: String?
        get() = commander?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val SYMBOL = Regex("""\{([WUBRG])\}""", RegexOption.IGNORE_CASE)
        val WORD_RE = Regex("""\b(white|blue|black|red|green)\b""", RegexOption.IGNORE_CASE)
        val FIVE = Regex("""wubrg|five.?colou?r""", RegexOption.IGNORE_CASE)
        val DASH = Regex("""\s+\u2014\s+""")
        val LETTERS = Regex("""[WUBRGwubrg]{1,5}""")
        val WORDS = mapOf(
            "white" to "W", "blue" to "U", "black" to "B", "red" to "R", "green" to "G",
        )

        /** Alphabetical, the order the database stores identity in. */
        fun dedupe(cs: List<String>) = cs.distinct().sorted().joinToString("")
    }
}

data class DeckCard(
    val name: String,
    val qty: Int,
    val role: String?,
    val owned: Int,
)

object DeckQueries {

    /**
     * Every deck, with its commander's art.
     *
     * The join collapses printings to one row per name first — a
     * commander with nine printings would otherwise multiply the deck
     * row by nine, which is how a tile list once showed the same deck
     * repeatedly.
     */
    fun all() = Sql(
        """SELECT d.slug, d.name, d.owner, d.commander, d.colors, d.bracket,
                  c.scryfall_id AS art_id
             FROM decks d
             LEFT JOIN (SELECT name_norm, MIN(id) AS id, scryfall_id
                          FROM cards GROUP BY name_norm) c
               ON c.name_norm = lower(trim(CASE
                    WHEN instr(d.commander, ' (') > 0
                    THEN substr(d.commander, 1, instr(d.commander, ' (') - 1)
                    ELSE d.commander END))
            ORDER BY d.owner, d.name""",
        emptyList(),
    )

    /** One deck's list, with how many of each the owner actually has. */
    fun cards(slug: String) = Sql(
        """SELECT dc.name, dc.qty, dc.role,
                  COALESCE((SELECT SUM(t.qty) FROM totals t
                             WHERE t.name_norm = dc.name_norm AND t.owner = d.owner), 0) AS owned
             FROM deck_cards dc
             JOIN decks d ON d.id = dc.deck_id
            WHERE d.slug = ?
            ORDER BY dc.role IS NULL, dc.role, dc.name""",
        listOf(slug),
    )

    fun decode(cols: List<String>, rows: List<JsonArray>): List<Deck> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        fun JsonArray.str(n: String): String? {
            val v = at[n]?.let { getOrNull(it) } ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }
        return rows.map {
            Deck(
                slug = it.str("slug").orEmpty(),
                name = it.str("name").orEmpty(),
                owner = it.str("owner").orEmpty(),
                commander = it.str("commander"),
                colors = it.str("colors"),
                bracket = it.str("bracket")?.toIntOrNull(),
                artId = it.str("art_id"),
            )
        }
    }

    fun decodeCards(cols: List<String>, rows: List<JsonArray>): List<DeckCard> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        fun JsonArray.str(n: String): String? {
            val v = at[n]?.let { getOrNull(it) } ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }
        return rows.map {
            DeckCard(
                name = it.str("name").orEmpty(),
                qty = it.str("qty")?.toIntOrNull() ?: 0,
                role = it.str("role"),
                owned = it.str("owned")?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** The decks screen: a list, or one deck opened. */
data class DecksState(
    val decks: List<Deck> = emptyList(),
    val openSlug: String? = null,
    val cards: List<DeckCard> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val open: Deck? get() = decks.firstOrNull { it.slug == openSlug }

    /** Grouped the way the page shows them, owners in a stable order. */
    val byOwner: List<Pair<String, List<Deck>>>
        get() = decks.groupBy { it.owner }.toList().sortedBy { it.first }

    /** A card the deck wants more of than its owner has. */
    val gaps: List<DeckCard> get() = cards.filter { it.owned < it.qty }

    val totalCards: Int get() = cards.sumOf { it.qty }

    fun loading() = copy(busy = true, error = null)
    fun loaded(decks: List<Deck>) = copy(decks = decks, busy = false, error = null)
    fun opened(slug: String, cards: List<DeckCard>) =
        copy(openSlug = slug, cards = cards, busy = false, error = null)

    fun close() = copy(openSlug = null, cards = emptyList())
    fun failed(message: String) = copy(busy = false, error = message)
}
