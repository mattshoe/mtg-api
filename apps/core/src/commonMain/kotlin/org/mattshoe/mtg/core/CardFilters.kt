package org.mattshoe.mtg.core

/**
 * The filter model: every column in the database, one place that turns it
 * into SQL.
 *
 * A port of `frontend/js/filters.js`, and the single most valuable thing
 * in this module. Everything the Library screen does is this, and having
 * it once means Android and the web cannot disagree about what "at most
 * these colours" returns — which is the sort of difference nobody notices
 * until a deck is built on it.
 *
 * Everything a person typed is bound. Only identifiers this file owns are
 * ever interpolated into SQL.
 */

val COLOR_LETTERS = listOf("W", "U", "B", "R", "G")

/** Fixed. One page size, and a page never shows printings separately. */
const val PAGE_SIZE = 100

enum class ColorMode(val slug: String, val label: String, val explains: String) {
    EXACTLY("exactly", "Exactly", "these colours and no others"),
    ATMOST("atmost", "At most", "nothing outside these colours — what fits a commander"),
    ATLEAST("atleast", "At least", "all of these, others allowed"),
    ANYOF("anyof", "Any of", "at least one of these"),
    ;

    companion object {
        fun of(slug: String) = entries.firstOrNull { it.slug == slug } ?: ATMOST
    }
}

enum class Pool(val slug: String) { ALL("all"), FREE("free"), COMMITTED("committed") }

enum class ColorTarget(val slug: String, val column: String) {
    IDENTITY("id", "c.color_identity"),
    PRINTED("card", "c.colors"),
}

/** Three-valued: unset means "either". */
enum class Tri { ANY, YES, NO }

enum class Flag(val slug: String, val column: String, val label: String) {
    RESERVED("reserved", "reserved", "Reserved list"),
    GAME_CHANGER("gameChanger", "game_changer", "Game Changer"),
    FULL_ART("fullArt", "full_art", "Full art"),
    TEXTLESS("textless", "textless", "Textless"),
    PROMO("promo", "promo", "Promo"),
    REPRINT("reprint", "reprint", "Reprint"),
    STORY_SPOTLIGHT("storySpotlight", "story_spotlight", "Story spotlight"),
    BOOSTER("booster", "booster", "In boosters"),
    OVERSIZED("oversized", "oversized", "Oversized"),
    VARIATION("variation", "variation", "Variation"),
}

/**
 * Columns are qualified with `c.` because card_usage joins in as `u` and
 * shares several names — an unqualified name_norm is ambiguous. `qty` and
 * `free` are output aliases, so they stay bare.
 */
enum class Sort(val slug: String, val label: String, val column: String) {
    NAME("name", "Name", "c.name_norm"),
    CMC("cmc", "Mana value", "c.cmc"),
    QTY("qty", "Quantity", "qty"),
    FREE("free", "Free copies", "free"),
    EDHREC("edhrec", "EDHREC rank", "c.edhrec_rank"),
    RELEASED("released", "Released", "c.released_at"),
    RARITY("rarity", "Rarity", "instr('common uncommon rare mythic special bonus', c.rarity)"),
    SET("set", "Set", "c.setcode"),
    POWER("power", "Power", "CASE WHEN c.power GLOB '[0-9]*' THEN CAST(c.power AS INTEGER) END"),
    TOUGHNESS("toughness", "Toughness", "CASE WHEN c.toughness GLOB '[0-9]*' THEN CAST(c.toughness AS INTEGER) END"),
    COLOR("color", "Colour identity", "c.color_identity_count, c.color_identity"),
    ARTIST("artist", "Artist", "c.artist"),
    PRICE("price", "Price", "price"),
    VALUE("value", "Stack value", "value"),
    ;

    companion object {
        fun of(slug: String) = entries.firstOrNull { it.slug == slug } ?: NAME
    }
}

/**
 * Free copies come from the card_usage view, joined once rather than
 * correlated per row. As a subquery it re-evaluated the view for every
 * candidate row and blew D1's CPU limit on a whole-collection query. The
 * join is 1:1 on (owner, name_norm), so it cannot fan rows out.
 */
const val USAGE_JOIN = "LEFT JOIN card_usage u ON u.owner = c.owner AND u.name_norm = c.name_norm"

/**
 * Prices live in their own table, refreshed nightly, so the price of a
 * stack is a plain SQL expression — which means sorting and filtering by
 * it happen in the database rather than by pulling everything into the
 * client.
 */
const val PRICE_JOIN = "LEFT JOIN prices pr ON pr.scryfall_id = c.scryfall_id"

const val PRICE_EXPR = """CASE c.finish
         WHEN 'foil'   THEN COALESCE(pr.usd_foil, pr.usd)
         WHEN 'etched' THEN COALESCE(pr.usd_etched, pr.usd_foil, pr.usd)
         ELSE pr.usd END"""

private const val SELECT_COLS = """c.owner, c.name, c.name_norm, c.face2, c.layout,
       c.scryfall_id, c.mana_cost, c.cmc, c.type_line,
       c.color_identity, c.rarity, c.setcode, c.set_name, c.collector_number,
       c.edhrec_rank, c.released_at, c.finish, c.power, c.toughness, c.artist,
       pr.tcg_url, pr.updated_at AS priced_at"""

private val NUM_OPS = setOf(">=", "<=", "=", ">", "<", "!=")

/** Everything the panel and the query box can set. */
data class Filters(
    // who and how many. Everything, both collections, unless narrowed.
    // Rows stay per owner — GROUP BY is (owner, name_norm) — so a card
    // they both own is two rows, which is the truth rather than a total.
    val owner: String = "both",
    val qtyMin: String = "", val qtyMax: String = "",
    val pool: Pool = Pool.ALL,
    val freeMin: String = "",
    val deck: String = "",
    val finish: String = "",

    // words
    val q: String = "",
    val text: String = "",
    val textLike: String = "",
    val flavor: String = "",
    val artist: String = "",
    val watermark: String = "",
    val typeLine: String = "",

    // colour
    val colorTarget: ColorTarget = ColorTarget.IDENTITY,
    val colorMode: ColorMode = ColorMode.ATMOST,
    val colors: List<String> = emptyList(),
    val ciMin: String = "", val ciMax: String = "",
    val produces: List<String> = emptyList(),

    // mana and stats
    val cmcMin: String = "", val cmcMax: String = "",
    val manaCost: String = "",
    val powOp: String = ">=", val pow: String = "",
    val touOp: String = ">=", val tou: String = "",
    val loyOp: String = ">=", val loy: String = "",

    // types
    val types: List<String> = emptyList(),
    val typesNot: List<String> = emptyList(),
    val supertypes: List<String> = emptyList(),
    val subtypes: List<String> = emptyList(),

    // printing
    val rarities: List<String> = emptyList(),
    val sets: List<String> = emptyList(),
    val setTypes: List<String> = emptyList(),
    val layouts: List<String> = emptyList(),
    val frames: List<String> = emptyList(),
    val borders: List<String> = emptyList(),
    val games: List<String> = emptyList(),
    val yearMin: String = "", val yearMax: String = "",
    val collnum: String = "",

    val flags: Map<Flag, Tri> = emptyMap(),

    val priceMin: String = "", val priceMax: String = "",

    val keywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val format: String = "", val legality: String = "legal",
    val edhrecMin: String = "", val edhrecMax: String = "",
    val hasRulings: Tri = Tri.ANY,

    /** The query box, ANDed on top of everything above. */
    val adv: String = "",

    // Sorted by what a card is worth, biggest first. It is the question
    // asked of a collection more often than any other.
    val sort: Sort = Sort.PRICE,
    val descending: Boolean = true,
    val page: Int = 1,
    val size: Int = PAGE_SIZE,
)

/** A statement and the values bound into it. */
data class Sql(val sql: String, val params: List<Any?>)

internal class Clauses {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any?>()

    fun add(clause: String, vararg values: Any?) {
        where += clause
        params.addAll(values)
    }

    fun like(v: String) = "%${v.lowercase()}%"

    fun numeric(column: String, op: String, value: String) {
        val n = value.trim().toDoubleOrNull() ?: return
        add("$column ${if (op in NUM_OPS) op else ">="} ?", n)
    }

    /** Power and toughness are text: '*', '1+*', '3'. Compare real numbers only. */
    fun pt(column: String, op: String, value: String) {
        val n = value.trim().toDoubleOrNull() ?: return
        add("$column GLOB '[0-9]*' AND CAST($column AS INTEGER) ${if (op in NUM_OPS) op else ">="} ?", n)
    }

    fun inList(column: String, values: List<String>) {
        if (values.isEmpty()) return
        where += "$column IN (${values.joinToString(",") { "?" }})"
        params.addAll(values)
    }
}

/**
 * Colour strings in this database are stored ALPHABETICALLY, not in WUBRG
 * order — 'UW' for Azorius, 'BG' for Golgari. An exact match has to sort
 * the same way or it silently matches nothing.
 */
private fun sortedColors(list: List<String>) =
    COLOR_LETTERS.filter { it in list }.sorted().joinToString("")

/**
 * The colour clause, which matters more than any other for Commander and
 * so lives in one function used by every caller.
 *
 * Colourless (C) means an empty colour string, and is handled per mode
 * rather than pretended to be a sixth colour.
 */
internal fun colorClause(column: String, mode: ColorMode, selected: List<String>, c: Clauses) {
    val chosen = selected.filter { it in COLOR_LETTERS }
    val colorless = "C" in selected
    if (chosen.isEmpty() && !colorless) return

    val col = "COALESCE($column, '')"

    when (mode) {
        ColorMode.EXACTLY ->
            if (chosen.isEmpty()) c.add("$col = ''") else c.add("$col = ?", sortedColors(chosen))

        ColorMode.ATLEAST ->
            if (chosen.isEmpty()) c.add("$col = ''")
            else chosen.forEach { c.add("$col LIKE ?", "%$it%") }

        ColorMode.ANYOF -> {
            val ors = mutableListOf<String>()
            chosen.forEach { ors += "$col LIKE ?"; c.params += "%$it%" }
            if (colorless) ors += "$col = ''"
            c.where += "(${ors.joinToString(" OR ")})"
        }

        // Nothing outside the chosen set. A colourless card always fits,
        // which is why "at most WU" correctly returns Sol Ring.
        ColorMode.ATMOST -> {
            COLOR_LETTERS.filterNot { it in chosen }.forEach { c.add("$col NOT LIKE ?", "%$it%") }
            if (colorless && chosen.isEmpty()) c.add("$col = ''")
        }
    }
}

/** State to WHERE. */
fun conditions(s: Filters): Sql {
    val c = Clauses()

    if (s.owner.isNotBlank() && s.owner != "both") c.add("c.owner = ?", s.owner)

    s.q.trim().takeIf { it.isNotEmpty() }?.let {
        c.add(
            "(c.name_norm LIKE ? OR lower(c.face1) LIKE ? OR lower(c.face2) LIKE ?)",
            c.like(it), c.like(it), c.like(it),
        )
    }
    s.text.trim().takeIf { it.isNotEmpty() }
        ?.let { c.add("c.id IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)", it) }
    s.textLike.trim().takeIf { it.isNotEmpty() }?.let { c.add("lower(c.oracle_text) LIKE ?", c.like(it)) }
    s.flavor.trim().takeIf { it.isNotEmpty() }?.let { c.add("lower(c.flavor_text) LIKE ?", c.like(it)) }
    s.artist.trim().takeIf { it.isNotEmpty() }?.let { c.add("lower(c.artist) LIKE ?", c.like(it)) }
    s.watermark.trim().takeIf { it.isNotEmpty() }?.let { c.add("lower(c.watermark) LIKE ?", c.like(it)) }
    s.typeLine.trim().takeIf { it.isNotEmpty() }?.let { c.add("lower(c.type_line) LIKE ?", c.like(it)) }
    s.manaCost.trim().takeIf { it.isNotEmpty() }
        ?.let { c.add("replace(c.mana_cost, ' ', '') LIKE ?", c.like(it.replace(Regex("\\s"), ""))) }
    s.collnum.trim().takeIf { it.isNotEmpty() }?.let { c.add("c.collector_number = ?", it) }

    colorClause(s.colorTarget.column, s.colorMode, s.colors, c)
    c.numeric("c.color_identity_count", ">=", s.ciMin)
    c.numeric("c.color_identity_count", "<=", s.ciMax)
    s.produces.forEach { c.add("c.produced_mana LIKE ?", "%$it%") }

    c.numeric("c.cmc", ">=", s.cmcMin)
    c.numeric("c.cmc", "<=", s.cmcMax)
    c.pt("c.power", s.powOp, s.pow)
    c.pt("c.toughness", s.touOp, s.tou)
    c.pt("c.loyalty", s.loyOp, s.loy)

    c.numeric("c.qty", ">=", s.qtyMin)
    c.numeric("c.qty", "<=", s.qtyMax)
    c.numeric("c.edhrec_rank", ">=", s.edhrecMin)
    c.numeric("c.edhrec_rank", "<=", s.edhrecMax)

    s.types.forEach {
        c.add("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)", it)
    }
    s.typesNot.forEach {
        c.add("NOT EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)", it)
    }
    s.supertypes.forEach {
        c.add("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'supertype' AND ct.type = ?)", it)
    }
    s.subtypes.forEach {
        c.add(
            "EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'subtype' AND lower(ct.type) = ?)",
            it.lowercase(),
        )
    }
    s.keywords.forEach {
        c.add("EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)", it.lowercase())
    }
    s.tags.forEach {
        c.add("EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND ct.tag_slug = ?)", it)
    }

    c.inList("c.rarity", s.rarities)
    c.inList("lower(c.setcode)", s.sets.map { it.lowercase() })
    c.inList("c.set_type", s.setTypes)
    c.inList("c.layout", s.layouts)
    c.inList("c.frame", s.frames)
    c.inList("c.border_color", s.borders)
    if (s.finish.isNotBlank()) c.add("c.finish = ?", s.finish)
    s.games.forEach { c.add("EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game = ?)", it) }

    if (s.yearMin.isNotBlank()) c.add("c.released_at >= ?", "${s.yearMin}-01-01")
    if (s.yearMax.isNotBlank()) c.add("c.released_at <= ?", "${s.yearMax}-12-31")

    Flag.entries.forEach { flag ->
        when (s.flags[flag]) {
            Tri.YES -> c.where += "c.${flag.column} = 1"
            Tri.NO -> c.where += "COALESCE(c.${flag.column}, 0) = 0"
            else -> Unit
        }
    }

    if (s.format.isNotBlank()) {
        if (s.legality == "not_legal") {
            c.add(
                "NOT EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ?)",
                s.format,
            )
        } else {
            c.add(
                "EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = ?)",
                s.format, s.legality,
            )
        }
    }
    when (s.hasRulings) {
        Tri.YES -> c.where += "EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)"
        Tri.NO -> c.where += "NOT EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)"
        else -> Unit
    }

    if (s.priceMin.isNotBlank()) s.priceMin.toDoubleOrNull()?.let { c.add("($PRICE_EXPR) >= ?", it) }
    if (s.priceMax.isNotBlank()) s.priceMax.toDoubleOrNull()?.let { c.add("($PRICE_EXPR) <= ?", it) }

    when (s.pool) {
        Pool.FREE -> c.where += "COALESCE(u.free, 0) > 0"
        Pool.COMMITTED -> c.where += "COALESCE(u.free, 0) <= 0"
        Pool.ALL -> Unit
    }
    c.numeric("COALESCE(u.free, 0)", ">=", s.freeMin)

    when {
        s.deck == "_any" -> c.where +=
            "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
            "WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)"
        s.deck == "_none" -> c.where +=
            "NOT EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
            "WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)"
        s.deck.isNotBlank() -> c.add(
            "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
                "WHERE dc.name_norm = c.name_norm AND d.slug = ?)",
            s.deck,
        )
    }

    // The query box last, so its clauses read after the structured ones.
    // A syntax error there is the caller's to show, not this function's
    // to swallow — a box that silently matches everything is worse than
    // one that says what it did not understand.
    if (s.adv.isNotBlank()) {
        val adv = parseQueryBox(s.adv)
        if (adv.sql.isNotEmpty()) {
            c.where += adv.sql
            c.params.addAll(adv.params)
        }
    }

    return Sql(c.where.joinToString("\n  AND "), c.params)
}

/** The page of cards, or the count of them. */
fun buildQuery(s: Filters, countOnly: Boolean = false): Sql {
    val conds = conditions(s)
    val clause = if (conds.sql.isEmpty()) "" else "WHERE ${conds.sql}"

    if (countOnly) {
        val inner = "SELECT 1 FROM cards c $USAGE_JOIN $PRICE_JOIN $clause GROUP BY c.owner, c.name_norm"
        return Sql("SELECT COUNT(*) FROM ($inner)", conds.params)
    }

    val dir = if (s.descending) "DESC" else "ASC"
    val order = "ORDER BY (${s.sort.column}) IS NULL, (${s.sort.column}) $dir, c.name_norm ASC"
    val size = if (s.size > 0) s.size else PAGE_SIZE
    val offset = (s.page - 1).coerceAtLeast(0) * size

    // One row per card, never one per printing. MIN(c.id) makes SQLite
    // take the other bare columns from that same row, which is the
    // representative printing shown.
    val select = """SELECT MIN(c.id) AS id, $SELECT_COLS,
              SUM(c.qty) AS qty, COUNT(*) AS printings, u.free AS free,
              ($PRICE_EXPR) AS price,
              ROUND(SUM(c.qty * ($PRICE_EXPR)), 2) AS value"""

    return Sql(
        "$select\nFROM cards c\n$USAGE_JOIN\n$PRICE_JOIN\n$clause\nGROUP BY c.owner, c.name_norm\n$order\nLIMIT $size OFFSET $offset",
        conds.params,
    )
}
