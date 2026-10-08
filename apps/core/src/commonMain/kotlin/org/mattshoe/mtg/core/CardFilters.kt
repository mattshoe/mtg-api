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

/** What a land can tap for: the five, plus colourless. */
private val PRODUCIBLE = COLOR_LETTERS + "C"

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
enum class Sort(val slug: String, val label: String, val keys: List<String>) {
    NAME("name", "Name", listOf("c.name_norm")),
    CMC("cmc", "Mana value", listOf("MIN(c.cmc)")),
    QTY("qty", "Quantity", listOf("qty")),
    FREE("free", "Free copies", listOf("free")),
    EDHREC("edhrec", "EDHREC rank", listOf("MIN(c.edhrec_rank)")),

    // Aggregates, not bare columns. A bare column in a grouped query
    // takes its value from the `MIN(c.id)` row — the printing that
    // happened to be imported first — so "newest first" sorted a card
    // you own a 2026 printing of by its 1993 one, and a Sol Ring owned
    // in LEA, C21 and M3C sorted as uncommon.
    RELEASED("released", "Released", listOf("MAX(c.released_at)")),
    RARITY(
        "rarity",
        "Rarity",
        listOf("MAX(instr('common uncommon rare mythic special bonus', c.rarity))"),
    ),
    SET("set", "Set", listOf("MIN(c.setcode)")),
    POWER("power", "Power", listOf("MAX(CASE WHEN c.power GLOB '[0-9]*' THEN CAST(c.power AS INTEGER) END)")),
    TOUGHNESS(
        "toughness",
        "Toughness",
        listOf("MAX(CASE WHEN c.toughness GLOB '[0-9]*' THEN CAST(c.toughness AS INTEGER) END)"),
    ),

    // Two keys, which is why this is a list. As one comma-separated
    // string it became `ORDER BY (a, b) DESC` — a row value, which
    // SQLite refuses outside a comparison, so picking this sort
    // returned `row value misused` and emptied the grid.
    COLOR("color", "Colour identity", listOf("MIN(c.color_identity_count)", "MIN(c.color_identity)")),

    ARTIST("artist", "Artist", listOf("MIN(c.artist)")),
    PRICE("price", "Price", listOf("price")),
    VALUE("value", "Stack value", listOf("value")),
    ;

    /**
     * True when a low value is the good end. A rank is: rank 1 is the
     * card everybody plays. `buildQuery` flips the SQL for these, so
     * `descending` means "best first" on every column and the arrow
     * pointing down never puts the cards nobody plays at the top.
     */
    val ascendingIsBetter: Boolean get() = this == EDHREC

    /**
     * What the direction arrow means for this column.
     *
     * "Largest first" is nothing to a Name or an Artist, and the
     * tooltip said it for all fourteen.
     */
    fun directionLabel(descending: Boolean): String = when (this) {
        NAME, ARTIST, SET -> if (descending) "Z to A" else "A to Z"
        RELEASED -> if (descending) "Newest first" else "Oldest first"
        EDHREC -> if (descending) "Most played first" else "Least played first"
        else -> if (descending) "Largest first" else "Smallest first"
    }

    companion object {
        /** The default, so a typo in a link behaves like no sort at all. */
        val DEFAULT = PRICE

        fun of(slug: String) = entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}

/**
 * Free copies come from the card_usage view, joined once rather than
 * correlated per row. As a subquery it re-evaluated the view for every
 * candidate row and blew D1's CPU limit on a whole-collection query. The
 * join is 1:1 on (owner_id, name_norm), so it cannot fan rows out.
 */
const val USAGE_JOIN = "LEFT JOIN card_usage u ON u.owner_id = c.owner_id AND u.name_norm = c.name_norm"

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

private const val SELECT_COLS = """(SELECT key FROM users WHERE id = c.owner_id) AS owner, c.name, c.name_norm, c.face2, c.layout,
       c.scryfall_id, c.mana_cost, c.cmc, c.type_line,
       c.color_identity, c.rarity, c.setcode, c.set_name, c.collector_number,
       c.edhrec_rank, c.released_at, c.finish, c.power, c.toughness, c.artist"""

private val NUM_OPS = setOf(">=", "<=", "=", ">", "<", "!=")

/** Everything the panel and the query box can set. */
data class Filters(
    // who and how many. Everything, both collections, unless narrowed.
    // `owner` is the owning account's public key, never its id.
    // One row per card, not per owner. Grouping by (owner, name_norm)
    // was defended as "the truth rather than a total", but the tile
    // does not say whose it is, so a card they both own was the same
    // picture and the same name twice with no way to tell them apart.
    // Whose copies they are is on the card's own page, which has room
    // to say it.
    val owner: String = "both",
    val qtyMin: String = "", val qtyMax: String = "",
    val pool: Pool = Pool.ALL,
    val freeMin: String = "",
    val deck: String = "",
    val finish: String = "",

    // words
    val q: String = "",
    val text: String = "",
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

/**
 * What the user typed, as one FTS5 phrase.
 *
 * FTS5 parses its argument as a query expression, so a colon makes it
 * a column filter, a hyphen a negation, and `+1/+1` a syntax error —
 * all of which came back as `fts5: syntax error` over an empty grid.
 * Quoting the whole thing makes it a phrase, which is what somebody
 * typing into a "rules text" box means. Internal quotes are doubled,
 * which is how FTS5 escapes them.
 */
internal fun ftsPhrase(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

/**
 * The oracle-text box, as two FTS5 lookups.
 *
 * Positive terms are ANDed into one `MATCH`; negative terms are ORed
 * into a second and subtracted. Two clauses rather than FTS5's own
 * `NOT`, which is a binary operator and therefore has nothing to
 * subtract from when every term is negative — `!token` on its own was
 * the case that made the one-expression version impossible.
 */
/**
 * The column the rules-text box searches.
 *
 * `card_search` indexes six fields in one row and a bare `MATCH` is
 * happy with any of them, which made an ANDed search look like an OR.
 */
private const val ORACLE = "oracle_text"

internal fun ftsClause(raw: String, c: Clauses) {
    val terms = TextQuery.parse(raw)
    if (terms.isEmpty()) return
    val (no, yes) = terms.partition { it.negated }
    if (yes.isNotEmpty()) {
        c.add(
            "c.id IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)",
            TextQuery.fts(yes, "AND", ORACLE),
        )
    }
    if (no.isNotEmpty()) {
        c.add(
            "c.id NOT IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)",
            TextQuery.fts(no, "OR", ORACLE),
        )
    }
}

/**
 * A card carrying an oracle tag the way Scryfall's `otag:` means it: the
 * tag itself, or any tag Tagger files beneath it, or the tag an alias
 * names. `otag:blink` is an alias of `flicker`, and `flicker` is mostly
 * carried by `flicker-creature` and its siblings. `tag_names` holds that
 * tree, written by `scripts/tags.mjs`; the bare `ct.tag = ?` keeps an
 * exact tag working while that table is empty. Binds the tag twice.
 */
internal const val TAGGED =
    "EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND " +
        "(ct.tag = ? OR ct.tag IN (SELECT tn.tag FROM tag_names tn WHERE tn.name = ?)))"

internal class Clauses {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any?>()

    /** A LIKE whose pattern was built by `like`, so the escape holds. */
    fun addLike(column: String, value: String) {
        where += "$column LIKE ? ESCAPE '\\'"
        params += like(value)
    }

    /**
     * A search box, over one or more columns.
     *
     * Every word has to match somewhere; a quoted run has to match as
     * written; a `!` word must match nowhere. Across several columns a
     * positive term is an OR — "bolt" may be in the front face or the
     * back — and a negative term has to be absent from all of them,
     * which is the same OR with a NOT in front.
     *
     * `COALESCE` because a NULL column is not "does not contain": in
     * SQL `NULL NOT LIKE '%x%'` is NULL, which is not true, so a card
     * with no flavour text would be filtered out by `!goblin`.
     */
    fun search(columns: List<String>, raw: String) {
        // One parameter per word, not one per word per column.
        //
        // D1 refuses a statement with more than a hundred bound
        // parameters, and binding the same pattern once for the name
        // and once for each face spent three of them on every word
        // typed — so a long enough search came back "too many SQL
        // variables" rather than coming back at all. The columns are
        // joined into one haystack instead, which is the same
        // question asked once.
        val haystack = columns.joinToString(" || ' ' || ") { "COALESCE($it, '')" }
        TextQuery.parse(raw).forEach { term ->
            val clause = "$haystack LIKE ? ESCAPE '\\'"
            where += if (term.negated) "NOT ($clause)" else "($clause)"
            params += like(term.text)
        }
    }

    fun add(clause: String, vararg values: Any?) {
        where += clause
        params.addAll(values)
    }

    /**
     * A substring match on what was typed, with LIKE's own wildcards
     * neutered — `%` and `_` are ordinary characters to someone
     * searching for "50%" or "Chandra_", and treating them otherwise
     * silently matched everything.
     */
    fun like(v: String) = "%" + v.lowercase().replace("\\", "\\\\")
        .replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * A finite number, or nothing at all.
     *
     * `toDoubleOrNull` also accepts "NaN" and "Infinity", and both bind
     * as SQL NULL — so a box holding either produced a comparison that
     * is never true and an empty grid with no explanation. Unparseable
     * and un-comparable get the same answer here: add no clause.
     */
    fun number(value: String): Double? = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    fun numeric(column: String, op: String, value: String) {
        val n = number(value) ?: return
        add("$column ${if (op in NUM_OPS) op else ">="} ?", n)
    }

    /** Power and toughness are text: '*', '1+*', '3'. Compare real numbers only. */
    fun pt(column: String, op: String, value: String) {
        val n = number(value) ?: return
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

    // An empty owner is "no collection known", which is not "every
    // collection": `1=0` rather than no clause, because a search with
    // nothing to scope to returning everybody's cards is how somebody
    // else's collection ended up on the screen. `both` is still the
    // pooled read, for the places that genuinely want one.
    if (s.owner.isBlank()) c.add("1=0")
    else if (s.owner != "both") c.add(Owners.owns("c.owner_id"), s.owner)

    // Both faces, so "bolt" finds a card whose back is the bolt.
    // `name_norm` is stored lowercased; the other two are lowered here.
    c.search(listOf("c.name_norm", "lower(c.face1)", "lower(c.face2)"), s.q)
    ftsClause(s.text, c)
    c.search(listOf("lower(c.flavor_text)"), s.flavor)
    c.search(listOf("lower(c.artist)"), s.artist)
    c.search(listOf("lower(c.watermark)"), s.watermark)
    c.search(listOf("lower(c.type_line)"), s.typeLine)
    // `addLike`, not a hand-rolled LIKE: `like()` escapes `%`, `_` and
    // `\` with a backslash, and without the matching `ESCAPE` clause
    // SQLite reads that backslash as an ordinary character — so a cost
    // typed with an underscore in it matched nothing at all.
    s.manaCost.trim().takeIf { it.isNotEmpty() }
        ?.let { c.addLike("replace(c.mana_cost, ' ', '')", it.replace(Regex("\\s"), "")) }
    s.collnum.trim().takeIf { it.isNotEmpty() }?.let { c.add("c.collector_number = ?", it) }

    colorClause(s.colorTarget.column, s.colorMode, s.colors, c)
    c.numeric("c.color_identity_count", ">=", s.ciMin)
    c.numeric("c.color_identity_count", "<=", s.ciMax)
    // Only the six pips the panel offers. The value goes straight into
    // a LIKE pattern, so `produces=%` from a hand-edited link was a
    // bare `%%%` — every card that taps for anything.
    s.produces.filter { it in PRODUCIBLE }.forEach { c.add("c.produced_mana LIKE ?", "%$it%") }

    c.numeric("c.cmc", ">=", s.cmcMin)
    c.numeric("c.cmc", "<=", s.cmcMax)
    c.pt("c.power", s.powOp, s.pow)
    c.pt("c.toughness", s.touOp, s.tou)
    c.pt("c.loyalty", s.loyOp, s.loy)

    // qtyMin/qtyMax are a HAVING, not a WHERE — see `having`.
    c.numeric("c.edhrec_rank", ">=", s.edhrecMin)
    c.numeric("c.edhrec_rank", "<=", s.edhrecMax)

    s.types.forEach {
        c.add("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)", it)
    }
    s.keywords.forEach {
        c.add("EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)", it.lowercase())
    }
    s.tags.forEach { c.add(TAGGED, it, it) }

    c.inList("c.rarity", s.rarities)
    c.inList("lower(c.setcode)", s.sets.map { it.lowercase() })
    c.inList("c.set_type", s.setTypes)
    c.inList("c.layout", s.layouts)
    c.inList("c.frame", s.frames)
    c.inList("c.border_color", s.borders)
    if (s.finish.isNotBlank()) c.add("c.finish = ?", s.finish)
    if (s.games.isNotEmpty()) {
        val holes = s.games.joinToString(",") { "?" }
        c.where += "EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game IN ($holes))"
        c.params.addAll(s.games)
    }

    // A year has to be a number. `yearMin = "soon"` used to bind
    // 'soon-01-01', which no release date is ever >= — a typo that
    // emptied the grid instead of being ignored like every other
    // unparseable number here.
    s.yearMin.trim().toIntOrNull()?.let { c.add("c.released_at >= ?", "$it-01-01") }
    s.yearMax.trim().toIntOrNull()?.let { c.add("c.released_at <= ?", "$it-12-31") }

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

    c.number(s.priceMin)?.let { c.add("($PRICE_EXPR) >= ?", it) }
    c.number(s.priceMax)?.let { c.add("($PRICE_EXPR) <= ?", it) }

    when (s.pool) {
        Pool.FREE -> c.where += "COALESCE(u.free, 0) > 0"
        Pool.COMMITTED -> c.where += "COALESCE(u.free, 0) <= 0"
        Pool.ALL -> Unit
    }
    c.numeric("COALESCE(u.free, 0)", ">=", s.freeMin)

    when {
        s.deck == "_any" -> c.where +=
            "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
            "WHERE dc.name_norm = c.name_norm AND d.owner_id = c.owner_id)"
        s.deck == "_none" -> c.where +=
            "NOT EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
            "WHERE dc.name_norm = c.name_norm AND d.owner_id = c.owner_id)"
        s.deck.isNotBlank() -> c.add(
            "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
                "WHERE dc.name_norm = c.name_norm AND d.key = ?)",
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

/**
 * Filters on the whole stack rather than on one printing.
 *
 * `qty` is displayed as `SUM(c.qty)` over every printing owned, so
 * asking for it in the WHERE asks a different question than the
 * control does: "copies owned, at least 4" on a card held as four
 * separate singles returned nothing while the grid printed `4` beside
 * it. These belong after the grouping.
 */
fun having(s: Filters): Sql {
    val c = Clauses()
    c.numeric("SUM(c.qty)", ">=", s.qtyMin)
    c.numeric("SUM(c.qty)", "<=", s.qtyMax)
    return Sql(c.where.joinToString("\n  AND "), c.params)
}

/** The page of cards, or the count of them. */
fun buildQuery(s: Filters, countOnly: Boolean = false): Sql {
    val conds = conditions(s)
    val clause = if (conds.sql.isEmpty()) "" else "WHERE ${conds.sql}"
    val post = having(s)
    val havingClause = if (post.sql.isEmpty()) "" else "HAVING ${post.sql}"
    val params = conds.params + post.params

    if (countOnly) {
        val inner = "SELECT 1 FROM cards c $USAGE_JOIN $PRICE_JOIN $clause " +
            "GROUP BY c.name_norm $havingClause"
        return Sql("SELECT COUNT(*) FROM ($inner)", params)
    }

    val dir = if (s.descending != s.sort.ascendingIsBetter) "DESC" else "ASC"
    val order = "ORDER BY " +
        s.sort.keys.joinToString(", ") { "($it) IS NULL, ($it) $dir" } +
        ", c.name_norm ASC"
    val size = if (s.size > 0) s.size else PAGE_SIZE
    // Long, because `(page - 1) * size` overflowed Int: page 30000000
    // came out as a negative offset, which SQLite reads as zero — so a
    // page number past about 21 million quietly showed page one.
    val offset = (s.page.toLong() - 1L).coerceAtLeast(0L) * size.toLong()

    // One row per card, never one per printing. MIN(c.id) makes SQLite
    // take the other bare columns from that same row, which is the
    // representative printing shown.
    // Every number on a tile describes the same set of printings: the
    // ones that survived the filter and were grouped into this row.
    //
    //   price  the best copy owned, not whichever printing SQLite
    //          happened to hand a bare expression — that produced
    //          "3 copies · $2.00 each · $45.00 total" on one tile.
    //   free   never more than are owned. `card_usage.free` counts the
    //          whole collection, so filtering to one finish showed
    //          "1 owned, 4 free".
    //   unpriced  how many copies have no market price, so a partial
    //          `value` can say it is partial instead of reading as
    //          the whole answer.
    val select = """SELECT MIN(c.id) AS id, $SELECT_COLS,
              SUM(c.qty) AS qty, COUNT(*) AS printings,
              MIN(COALESCE(u.free, 0), SUM(c.qty)) AS free,
              MAX($PRICE_EXPR) AS price,
              ROUND(SUM(c.qty * ($PRICE_EXPR)), 2) AS value,
              SUM(CASE WHEN ($PRICE_EXPR) IS NULL THEN c.qty ELSE 0 END) AS unpriced"""

    return Sql(
        "$select\nFROM cards c\n$USAGE_JOIN\n$PRICE_JOIN\n$clause" +
            "\nGROUP BY c.name_norm\n$havingClause\n$order\nLIMIT $size OFFSET $offset",
        params,
    )
}
