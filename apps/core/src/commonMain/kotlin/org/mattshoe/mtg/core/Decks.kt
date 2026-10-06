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
        get() = commander?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }?.let(::oneName)

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

/** Which list the carousel is over. */
enum class PeekOf { DECK, LIBRARY }

/**
 * Which card of a run the carousel is showing, or none.
 *
 * A position rather than a card: see `AppState.peekAt`. And the run
 * it is a position *in*, because the carousel is over the Library as
 * well as over a deck and a deck left loaded from an earlier visit
 * would otherwise be what the Library's carousel showed.
 */
data class Peek(val at: Int = -1, val of: PeekOf = PeekOf.DECK) {
    val open: Boolean get() = at >= 0
}

/** One fact the carousel's sheet states, and whether it is a bad one. */
data class PeekTag(val text: String, val bad: Boolean = false)

/**
 * One card, as the carousel's sheet needs it.
 *
 * Both runs flatten to this so the sheet asks one shape its
 * questions rather than asking two and having to agree with itself.
 *
 * `inDeck` is the row when the carousel is over a deck and null when
 * it is over the Library — and it is what the Count, Swap and Remove
 * buttons hang off, rather than an `admin` flag, because being admin
 * in the Library still leaves nothing to count.
 */
data class PeekCard(
    val title: String,
    val nameNorm: String,
    val scryfallId: String?,
    val typeLine: String?,
    /** "M3C · 409", or null for a card with no printing to name. */
    val printing: String?,
    val price: Double?,
    val tags: List<PeekTag>,
    val inDeck: DeckCard? = null,
)

data class DeckCard(
    val name: String,
    val qty: Int,
    val role: String?,
    val owned: Int,
    /** What the card drawer is opened by. Not `name.lowercase()`. */
    val nameNorm: String = "",
    val typeLine: String? = null,
    val scryfallId: String? = null,
    // Everything the deck's own analysis reads. All nullable: a card
    // the deck wants that nobody owns has no printing to read from,
    // and it still has to appear in the list.
    val manaCost: String? = null,
    val cmc: Double? = null,
    val producedMana: String? = null,
    val oracleText: String? = null,
    val colorIdentity: String? = null,
    val rarity: String? = null,
    val price: Double? = null,
    // Which printing the collection holds, for the sheet under the
    // card carousel. Null for a card the deck wants that nobody
    // owns, which has no printing to read anything off.
    val setCode: String? = null,
    val setName: String? = null,
    val collectorNumber: String? = null,
) {
    val isCommander: Boolean get() = role == "commander"

    /** "M3C · 409", the way a collector writes a printing down. */
    val printing: String?
        get() {
            val set = setCode?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
            val number = collectorNumber?.takeIf { it.isNotBlank() } ?: return set
            return "$set · $number"
        }

    /**
     * The name to show.
     *
     * Some rows carry a double-faced name whose halves are identical
     * — "Jetmir, Nexus of Revels // Jetmir, Nexus of Revels" — which
     * is the same word twice and long enough to push a phone's whole
     * page sideways.
     */
    val shown: String get() = oneName(name)

    /** Cropped art, square in the page, or null when nobody owns a printing. */
    val art: String? get() = CardQueries.art(scryfallId, "art_crop")

    /**
     * The basic land this is, if it is one.
     *
     * Basics are missing from the collection because nobody counts
     * them, so the joins come back empty and the card arrives with no
     * type and no colour. Their names are the one thing that can be
     * looked up without a printing.
     */
    private val basic: Basic? get() = Basic.of(nameNorm.ifEmpty { name.lowercase() })

    /** The type line, or the one a basic land has by definition. */
    val knownTypeLine: String? get() = typeLine ?: basic?.typeLine

    /** What it taps for, including a basic nobody has a printing of. */
    val knownProducedMana: String? get() = producedMana ?: basic?.produces

    /** Its mana value. A land's is nought, printing or no printing. */
    val knownManaValue: Double? get() = cmc ?: basic?.let { 0.0 }

    val isBasicLand: Boolean get() = basic != null

    /**
     * Copies the owner is short of, and would have to go and get.
     *
     * Basics are never short. Nobody inventories them, so every deck
     * reads as owning none — Feather Storm's "18 not owned" was nine
     * Plains, four Snow-Covered Plains, three Forests and two
     * Mountains, and not one of them is something to go and buy.
     */
    val short: Int get() = if (isBasicLand) 0 else (qty - owned).coerceAtLeast(0)

    /**
     * Which section it belongs under.
     *
     * Read off the printed type line, most specific first — a card is
     * an Artifact Creature before it is an Artifact, and a land that
     * happens to be legendary is still a land.
     */
    val group: DeckGroup get() = DeckGroup.of(this)
}

/**
 * The sections a deck list is read in.
 *
 * Fixed order rather than alphabetical: this is the order every deck
 * list on every site is written in, and shuffling it because somebody
 * added a Battle would be wrong.
 */
enum class DeckGroup(val title: String) {
    COMMANDER("Commander"),
    CREATURES("Creatures"),
    PLANESWALKERS("Planeswalkers"),
    INSTANTS("Instants"),
    SORCERIES("Sorceries"),
    ARTIFACTS("Artifacts"),
    ENCHANTMENTS("Enchantments"),
    BATTLES("Battles"),
    LANDS("Lands"),
    OTHER("Other"),

    /**
     * A card in the list that no printing in the collection matches,
     * so nothing is known about it — not its type, not its cost.
     *
     * Its own section rather than "Other": eighteen cards nobody owns
     * were being drawn as eighteen nought-drops in the mana curve,
     * which is a lie about the deck rather than a gap in the data.
     */
    UNKNOWN("Not in the collection"),
    ;

    companion object {
        fun of(card: DeckCard): DeckGroup {
            if (card.isCommander) return COMMANDER
            // Only the front face decides. A creature whose back is a
            // land is a creature in the list.
            val t = card.knownTypeLine.orEmpty().substringBefore("//").lowercase()
            return when {
                t.isBlank() -> UNKNOWN
                "creature" in t -> CREATURES
                "planeswalker" in t -> PLANESWALKERS
                "instant" in t -> INSTANTS
                "sorcery" in t -> SORCERIES
                "battle" in t -> BATTLES
                "land" in t -> LANDS
                "artifact" in t -> ARTIFACTS
                "enchantment" in t -> ENCHANTMENTS
                else -> OTHER
            }
        }
    }
}

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

    /**
     * One deck's list, with how many of each the owner actually has,
     * its type line and a printing to take art from.
     *
     * Two joins rather than one: `mine` is the owner's own printing,
     * which is the art they should see, and `alt` is anybody's, so a
     * card the deck wants but nobody owns still has a picture and a
     * type. Both collapse to one row per name first — a card with nine
     * printings would otherwise appear nine times.
     */
    fun cards(slug: String) = Sql(
        """SELECT dc.name, dc.name_norm, dc.qty, dc.role,
                  -- `totals` is already one row per owner and name, and
                  -- the column is `total_qty`. `SUM(t.qty)` was neither,
                  -- so opening any deck answered "no such column".
                  COALESCE((SELECT t.total_qty FROM totals t
                             WHERE t.name_norm = dc.name_norm AND t.owner = d.owner), 0) AS owned,
                  COALESCE(mine.type_line, alt.type_line)         AS type_line,
                  COALESCE(mine.scryfall_id, alt.scryfall_id)     AS scryfall_id,
                  COALESCE(mine.mana_cost, alt.mana_cost)         AS mana_cost,
                  COALESCE(mine.cmc, alt.cmc)                     AS cmc,
                  COALESCE(mine.produced_mana, alt.produced_mana) AS produced_mana,
                  COALESCE(mine.oracle_text, alt.oracle_text)     AS oracle_text,
                  COALESCE(mine.color_identity, alt.color_identity) AS color_identity,
                  COALESCE(mine.rarity, alt.rarity)               AS rarity,
                  COALESCE(pm.usd, pa.usd)                        AS price,
                  -- For the sheet under the card carousel, which says
                  -- which printing of the card the collection holds.
                  COALESCE(mine.setcode, alt.setcode)             AS setcode,
                  COALESCE(mine.set_name, alt.set_name)           AS set_name,
                  COALESCE(mine.collector_number, alt.collector_number) AS collector_number
             FROM deck_cards dc
             JOIN decks d ON d.id = dc.deck_id
             LEFT JOIN (SELECT owner, name_norm, MIN(id) AS id, scryfall_id, type_line,
                               mana_cost, cmc, produced_mana, oracle_text, color_identity, rarity,
                               setcode, set_name, collector_number
                          FROM cards GROUP BY owner, name_norm) mine
               ON mine.name_norm = dc.name_norm AND mine.owner = d.owner
             LEFT JOIN (SELECT name_norm, MIN(id) AS id, scryfall_id, type_line,
                               mana_cost, cmc, produced_mana, oracle_text, color_identity, rarity,
                               setcode, set_name, collector_number
                          FROM cards GROUP BY name_norm) alt
               ON alt.name_norm = dc.name_norm
             LEFT JOIN prices pm ON pm.scryfall_id = mine.scryfall_id
             LEFT JOIN prices pa ON pa.scryfall_id = alt.scryfall_id
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
                nameNorm = it.str("name_norm").orEmpty(),
                typeLine = it.str("type_line"),
                scryfallId = it.str("scryfall_id"),
                manaCost = it.str("mana_cost"),
                cmc = it.str("cmc")?.toDoubleOrNull(),
                producedMana = it.str("produced_mana"),
                oracleText = it.str("oracle_text"),
                colorIdentity = it.str("color_identity"),
                rarity = it.str("rarity"),
                price = it.str("price")?.toDoubleOrNull(),
                setCode = it.str("setcode"),
                setName = it.str("set_name"),
                collectorNumber = it.str("collector_number"),
            )
        }
    }
}

/**
 * A double-faced name whose halves are the same is one name.
 *
 * "Jetmir, Nexus of Revels // Jetmir, Nexus of Revels" is the same
 * word twice, and long enough to fill a phone's header with it.
 */
internal fun oneName(raw: String): String {
    val halves = raw.split(" // ")
    return if (halves.size == 2 && halves[0].trim() == halves[1].trim()) halves[0].trim() else raw
}

/** The decks screen: a list, or one deck opened. */
data class DecksState(
    val decks: List<Deck> = emptyList(),
    val openSlug: String? = null,
    val cards: List<DeckCard> = emptyList(),
    /**
     * The real tokens the open deck makes, from Scryfall's own
     * `all_parts`. Loaded after the list, so the deck shows
     * immediately and the tokens arrive behind it.
     */
    val tokens: List<TokenCard> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val open: Deck? get() = decks.firstOrNull { it.slug == openSlug }

    /** Grouped the way the page shows them, owners in a stable order. */
    val byOwner: List<Pair<String, List<Deck>>>
        get() = decks.groupBy { it.owner }.toList().sortedBy { it.first }

    /** A card the deck wants more of than its owner has. Basics never count. */
    val gaps: List<DeckCard> get() = cards.filter { it.short > 0 }

    /** The one the deck is built around, for the banner. */
    val commander: DeckCard? get() = cards.firstOrNull { it.isCommander }

    /**
     * The list as it is read: by type, in the order deck lists are
     * always written, alphabetical inside each section. The sections
     * nothing falls into are not shown at all.
     */
    val byType: List<Pair<DeckGroup, List<DeckCard>>>
        get() = cards.groupBy { it.group }
            .toList()
            .sortedBy { (group, _) -> group.ordinal }
            .map { (group, list) -> group to list.sortedBy { it.name.lowercase() } }

    /**
     * Every card in the order the page draws it.
     *
     * Exactly `byType` flattened, so "the next card" means the next
     * one down the screen rather than the next one the database
     * happened to return.
     */
    val pageOrder: List<DeckCard> get() = byType.flatMap { (_, cards) -> cards }

    val totalCards: Int get() = cards.sumOf { it.qty }

    fun loading() = copy(busy = true, error = null)
    fun loaded(decks: List<Deck>) = copy(decks = decks, busy = false, error = null)
    fun opened(slug: String, cards: List<DeckCard>) =
        copy(openSlug = slug, cards = cards, tokens = emptyList(), busy = false, error = null)

    fun withTokens(t: List<TokenCard>) = copy(tokens = t)

    /** Every printing the open deck can ask Scryfall about. */
    val scryfallIds: List<String> get() = cards.mapNotNull { it.scryfallId }.distinct()

    fun close() = copy(openSlug = null, cards = emptyList(), tokens = emptyList())
    fun failed(message: String) = copy(busy = false, error = message)
}
