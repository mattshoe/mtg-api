package org.mattshoe.mtg.core

/**
 * The query box.
 *
 * Scryfall-ish on purpose — it is the syntax Magic players already have
 * in their fingers:
 *
 *     c<=wu  t:creature  mv<=3  pow>=4  -is:reprint  o:"draw a card"
 *
 * A port of `parseAdvanced` in `frontend/js/filters.js`. Every value is
 * bound; only identifiers and operators this file recognises are ever
 * interpolated, and an operator it does not recognise falls back rather
 * than being passed through.
 */

/** Thrown with the words the box shows, not a stack trace. */
class QuerySyntax(message: String) : Exception(message)

private data class Token(
    val neg: Boolean,
    val key: String? = null,
    val op: String? = null,
    val value: String = "",
    val bare: String? = null,
)

private val CMP = mapOf(
    ":" to ">=", "=" to "=", ">=" to ">=", "<=" to "<=", ">" to ">", "<" to "<", "!=" to "!=",
)

private val ALIASES = mapOf(
    "n" to "name", "name" to "name",
    "o" to "oracle", "oracle" to "oracle", "text" to "oracle",
    "t" to "type", "type" to "type",
    "c" to "color", "color" to "color", "colors" to "color",
    "id" to "identity", "identity" to "identity", "ci" to "identity",
    "mv" to "mv", "cmc" to "mv",
    "pow" to "pow", "power" to "pow",
    "tou" to "tou", "toughness" to "tou",
    "loy" to "loy", "loyalty" to "loy",
    "r" to "rarity", "rarity" to "rarity",
    "s" to "set", "set" to "set", "e" to "set", "edition" to "set",
    "st" to "settype", "settype" to "settype",
    "a" to "artist", "artist" to "artist",
    "kw" to "keyword", "keyword" to "keyword",
    "tag" to "tag", "otag" to "tag",
    "f" to "format", "format" to "format", "legal" to "format",
    "banned" to "banned", "restricted" to "restricted",
    "is" to "is", "not" to "not",
    "qty" to "qty", "have" to "qty",
    "free" to "free",
    "usd" to "price", "price" to "price",
    "deck" to "deck",
    "owner" to "owner",
    "year" to "year",
    "produces" to "produces", "prod" to "produces",
    "layout" to "layout",
    "game" to "game",
    "ft" to "flavor", "flavor" to "flavor",
    "wm" to "watermark", "watermark" to "watermark",
    "cn" to "cn", "number" to "cn",
    "edhrec" to "edhrec", "rank" to "edhrec",
    "m" to "manacost", "mana" to "manacost",
)

private val IS_SHAPES = mapOf(
    "dfc" to "c.layout IN (${Flip.LAYOUTS.joinToString(",") { "'$it'" }})",
    "transform" to "c.layout = 'transform'",
    "mdfc" to "c.layout = 'modal_dfc'",
    "split" to "c.layout = 'split'",
    "adventure" to "c.layout = 'adventure'",
    "saga" to "c.layout = 'saga'",
    "token" to "c.layout IN ('token','double_faced_token')",
    "land" to "c.type_line LIKE '%Land%'",
    "creature" to "c.type_line LIKE '%Creature%'",
    "artifact" to "c.type_line LIKE '%Artifact%'",
    "enchantment" to "c.type_line LIKE '%Enchantment%'",
    "instant" to "c.type_line LIKE '%Instant%'",
    "sorcery" to "c.type_line LIKE '%Sorcery%'",
    "planeswalker" to "c.type_line LIKE '%Planeswalker%'",
    "battle" to "c.type_line LIKE '%Battle%'",
    "legendary" to "c.type_line LIKE '%Legendary%'",
    "basic" to "c.type_line LIKE '%Basic%'",
    "snow" to "c.type_line LIKE '%Snow%'",
    "permanent" to "(c.type_line LIKE '%Creature%' OR c.type_line LIKE '%Artifact%' " +
        "OR c.type_line LIKE '%Enchantment%' OR c.type_line LIKE '%Land%' " +
        "OR c.type_line LIKE '%Planeswalker%' OR c.type_line LIKE '%Battle%')",
    "spell" to "(c.type_line LIKE '%Instant%' OR c.type_line LIKE '%Sorcery%')",
    "commander" to "(c.type_line LIKE '%Legendary%' AND c.type_line LIKE '%Creature%')",
    "vanilla" to "(c.oracle_text IS NULL OR c.oracle_text = '')",
    "colorless" to "COALESCE(c.color_identity,'') = ''",
    "multicolor" to "c.color_identity_count > 1",
    "gold" to "c.color_identity_count > 1",
    "mono" to "c.color_identity_count = 1",
    "reserved" to "c.reserved = 1",
    "gamechanger" to "c.game_changer = 1",
    "fullart" to "c.full_art = 1",
    "textless" to "c.textless = 1",
    "promo" to "c.promo = 1",
    "reprint" to "c.reprint = 1",
    "firstprint" to "COALESCE(c.reprint, 0) = 0",
    "storyspotlight" to "c.story_spotlight = 1",
    "booster" to "c.booster = 1",
    "oversized" to "c.oversized = 1",
    "free" to "COALESCE(u.free, 0) > 0",
    "indeck" to "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
        "WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)",
    "hasrulings" to "EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)",
    "priced" to "pr.usd IS NOT NULL",
    "unpriced" to "pr.usd IS NULL",
)

/**
 * Every `is:` shape the box understands.
 *
 * Exposed so the cheatsheet lists what the parser actually accepts
 * rather than a second copy of the list that goes stale.
 */
val IS_VALUES: List<String> get() = IS_SHAPES.keys.sorted()

/** Split on whitespace, keeping quoted runs together. */
private val TOKEN = Regex(
    """(-?)([A-Za-z]+)(>=|<=|!=|[:=<>])("([^"]*)"|'([^']*)'|[^\s]*)|(-?)"([^"]*)"|(-?)(\S+)""",
)

private fun tokenize(input: String): List<Token> = TOKEN.findAll(input).mapNotNull { m ->
    val g = m.groupValues
    when {
        g[2].isNotEmpty() -> Token(
            neg = g[1] == "-",
            key = g[2].lowercase(),
            op = g[3],
            value = g[5].ifEmpty { g[6].ifEmpty { g[4] } },
        )
        m.groups[8] != null -> Token(neg = g[7] == "-", bare = g[8])
        g[10].isNotEmpty() -> Token(neg = g[9] == "-", bare = g[10])
        else -> null
    }
}.toList()

/**
 * Parse the box into clauses.
 *
 * Anything it does not recognise is collected and thrown at the end
 * rather than silently dropped — a typo that quietly matches everything
 * is worse than one that says so.
 */
fun parseQueryBox(input: String): Sql {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any?>()
    val unknown = linkedSetOf<String>()

    fun push(sql: String, args: List<Any?> = emptyList(), neg: Boolean = false) {
        where += if (neg) "NOT ($sql)" else sql
        params.addAll(args)
    }

    /**
     * A contains-match on a value the person typed, not a pattern.
     *
     * `%` and `_` are ordinary characters to somebody searching for
     * "50%" or "Chandra_", and left alone SQLite reads them as its own
     * wildcards: the search silently widens instead of narrowing, with
     * no error and nothing on screen to say the answer is wrong.
     * `Clauses.like` in `CardFilters.kt` has escaped them from the
     * start for this reason and this box did not, so the same typed
     * text meant two different things depending on which box it went
     * into. Every `LIKE` built from this has to carry `ESCAPE` with
     * it, which is what `LIKE_ESC` is for.
     */
    fun like(v: String) = "%" + v.lowercase().replace("\\", "\\\\")
        .replace("%", "\\%").replace("_", "\\_") + "%"

    for (tok in tokenize(input)) {
        if (tok.bare != null) {
            if (tok.bare.isBlank()) continue
            push(
                "(c.name_norm LIKE ? ESCAPE '\\' OR lower(c.face1) LIKE ? ESCAPE '\\' " +
                    "OR lower(c.face2) LIKE ? ESCAPE '\\')",
                listOf(like(tok.bare), like(tok.bare), like(tok.bare)),
                tok.neg,
            )
            continue
        }

        val key = ALIASES[tok.key]
        if (key == null) { unknown += tok.key.orEmpty(); continue }
        val v = tok.value
        val op = CMP[tok.op] ?: ">="

        when (key) {
            "name" -> push(
                "(c.name_norm LIKE ? ESCAPE '\\' OR lower(c.face1) LIKE ? ESCAPE '\\' " +
                    "OR lower(c.face2) LIKE ? ESCAPE '\\')",
                listOf(like(v), like(v), like(v)), tok.neg,
            )
            "oracle" -> push("lower(c.oracle_text) LIKE ? ESCAPE '\\'", listOf(like(v)), tok.neg)
            "flavor" -> push("lower(c.flavor_text) LIKE ? ESCAPE '\\'", listOf(like(v)), tok.neg)
            "type" -> push("lower(c.type_line) LIKE ? ESCAPE '\\'", listOf(like(v)), tok.neg)
            "manacost" -> push(
                "replace(c.mana_cost, ' ', '') LIKE ? ESCAPE '\\'",
                listOf(like(v.replace(Regex("\\s"), ""))), tok.neg,
            )

            "color", "identity" -> {
                val column = if (key == "color") "c.colors" else "c.color_identity"
                // `c:wu` reads as "at least" the way Scryfall does; the
                // comparison operators carry the precise meanings.
                val mode = when (tok.op) {
                    "=" -> ColorMode.EXACTLY
                    "<=", "<" -> ColorMode.ATMOST
                    else -> ColorMode.ATLEAST
                }
                val letters = v.uppercase().filter { it in "WUBRGC" }.map { it.toString() }
                val sub = Clauses()
                colorClause(column, mode, letters, sub)
                if (sub.where.isNotEmpty()) push(sub.where.joinToString(" AND "), sub.params, tok.neg)
            }

            "produces" -> v.uppercase().filter { it in "WUBRGC" }.forEach {
                push("c.produced_mana LIKE ?", listOf("%$it%"), tok.neg)
            }

            "mv" -> push("c.cmc $op ?", listOf(v.toDoubleOrNull() ?: 0.0), tok.neg)
            "pow" -> push(
                "(c.power GLOB '[0-9]*' AND CAST(c.power AS INTEGER) $op ?)",
                listOf(v.toDoubleOrNull() ?: 0.0), tok.neg,
            )
            "tou" -> push(
                "(c.toughness GLOB '[0-9]*' AND CAST(c.toughness AS INTEGER) $op ?)",
                listOf(v.toDoubleOrNull() ?: 0.0), tok.neg,
            )
            "loy" -> push(
                "(c.loyalty GLOB '[0-9]*' AND CAST(c.loyalty AS INTEGER) $op ?)",
                listOf(v.toDoubleOrNull() ?: 0.0), tok.neg,
            )
            "qty" -> push("c.qty $op ?", listOf(v.toDoubleOrNull() ?: 0.0), tok.neg)
            "free" -> push("COALESCE(u.free, 0) $op ?", listOf(v.toDoubleOrNull() ?: 0.0), tok.neg)
            "price" -> push("($PRICE_EXPR) $op ?", listOf(v.toDoubleOrNull() ?: 0.0), tok.neg)

            // A bare `edhrec:100` means "ranked at least that well",
            // which is a smaller number, not a bigger one.
            "edhrec" -> push(
                "c.edhrec_rank ${if (tok.op == ":") "<=" else op} ?",
                listOf(v.toDoubleOrNull() ?: 0.0), tok.neg,
            )
            "year" -> push(
                "substr(c.released_at, 1, 4) ${if (tok.op == ":") "=" else op} ?",
                listOf((v.toIntOrNull() ?: 0).toString()), tok.neg,
            )

            "rarity" -> push("c.rarity = ?", listOf(v.lowercase()), tok.neg)
            "set" -> push("lower(c.setcode) = ?", listOf(v.lowercase()), tok.neg)
            "settype" -> push("c.set_type = ?", listOf(v.lowercase()), tok.neg)
            "layout" -> push("c.layout = ?", listOf(v.lowercase()), tok.neg)
            "cn" -> push("c.collector_number = ?", listOf(v), tok.neg)
            "artist" -> push("lower(c.artist) LIKE ? ESCAPE '\\'", listOf(like(v)), tok.neg)
            "watermark" -> push("lower(c.watermark) LIKE ? ESCAPE '\\'", listOf(like(v)), tok.neg)
            "owner" -> push("c.owner = ?", listOf(v.lowercase()), tok.neg)

            "game" -> push(
                "EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game = ?)",
                listOf(v.lowercase()), tok.neg,
            )
            "keyword" -> push(
                "EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)",
                listOf(v.lowercase()), tok.neg,
            )
            "tag" -> push(
                "EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND ct.tag_slug = ?)",
                listOf(v.lowercase()), tok.neg,
            )
            "format" -> push(
                "EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id " +
                    "AND l.format = ? AND l.status = 'legal')",
                listOf(v.lowercase()), tok.neg,
            )
            "banned" -> push(
                "EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id " +
                    "AND l.format = ? AND l.status = 'banned')",
                listOf(v.lowercase()), tok.neg,
            )
            "restricted" -> push(
                "EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id " +
                    "AND l.format = ? AND l.status = 'restricted')",
                listOf(v.lowercase()), tok.neg,
            )
            "deck" -> push(
                "EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id " +
                    "WHERE dc.name_norm = c.name_norm AND d.slug = ?)",
                listOf(v.lowercase()), tok.neg,
            )

            "is", "not" -> {
                val shape = IS_SHAPES[v.lowercase()]
                if (shape == null) unknown += "${tok.key}:$v"
                // `not:` is `is:` inverted, and a leading `-` inverts it
                // again — so `-not:reprint` means `is:reprint`.
                else push(shape, emptyList(), if (key == "not") !tok.neg else tok.neg)
            }

            else -> unknown += tok.key.orEmpty()
        }
    }

    if (unknown.isNotEmpty()) {
        throw QuerySyntax("don't know \"${unknown.joinToString("\", \"")}\" — try the cheatsheet")
    }
    return Sql(where.joinToString("\n  AND "), params)
}
