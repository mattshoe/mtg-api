package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * One row of the Library grid.
 *
 * `/query` answers with `cols` once and `rows` as arrays, which is cheap
 * on the wire and unreadable in code, so it becomes something named here
 * and exactly once.
 */
data class CardRow(
    val id: Long,
    val owner: String,
    val name: String,
    val nameNorm: String,
    val face2: String?,
    val layout: String?,
    val scryfallId: String?,
    val manaCost: String?,
    val cmc: Double?,
    val typeLine: String?,
    val colorIdentity: String?,
    val rarity: String?,
    val setCode: String?,
    val setName: String?,
    val collectorNumber: String?,
    val edhrecRank: Long?,
    val releasedAt: String?,
    val finish: String?,
    val power: String?,
    val toughness: String?,
    val artist: String?,
    val qty: Int,
    val printings: Int,
    val free: Int?,
    val price: Double?,
    val value: Double?,
    /** Copies with no market price, so a partial total can say so. */
    val unpriced: Int = 0,
) {
    /** Both faces, the way the card is actually named. */
    val fullName: String get() = if (face2.isNullOrBlank()) name else "$name // $face2"

    val colors: List<String> get() = (colorIdentity ?: "").map { it.toString() }

    /** Copies not committed to a deck. Null means the view had nothing to say. */
    val isFree: Boolean get() = (free ?: 0) > 0

    /**
     * `value` sums only the copies that have a price, so when some do
     * not it is a floor rather than the total. Worth saying out loud
     * next to the number.
     */
    val valueIsPartial: Boolean get() = unpriced > 0 && value != null
}

/**
 * Decoding `{cols, rows}` into rows.
 *
 * Column order is whatever the SELECT said, so everything is looked up by
 * name. A query that stops returning a column yields nulls rather than
 * silently shifting every field one to the left, which is the failure
 * mode of reading these positionally.
 */
object Rows {

    fun cards(cols: List<String>, rows: List<JsonArray>): List<CardRow> {
        val at = cols.withIndex().associate { (i, name) -> name to i }

        fun JsonArray.str(name: String): String? {
            val i = at[name] ?: return null
            val v = getOrNull(i) ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }

        fun JsonArray.num(name: String): Double? = str(name)?.toDoubleOrNull()
        fun JsonArray.int(name: String): Int? = num(name)?.toInt()
        fun JsonArray.long(name: String): Long? = num(name)?.toLong()

        return rows.map { r ->
            CardRow(
                id = r.long("id") ?: 0,
                owner = r.str("owner").orEmpty(),
                name = r.str("name").orEmpty(),
                nameNorm = r.str("name_norm").orEmpty(),
                face2 = r.str("face2"),
                layout = r.str("layout"),
                scryfallId = r.str("scryfall_id"),
                manaCost = r.str("mana_cost"),
                cmc = r.num("cmc"),
                typeLine = r.str("type_line"),
                colorIdentity = r.str("color_identity"),
                rarity = r.str("rarity"),
                setCode = r.str("setcode"),
                setName = r.str("set_name"),
                collectorNumber = r.str("collector_number"),
                edhrecRank = r.long("edhrec_rank"),
                releasedAt = r.str("released_at"),
                finish = r.str("finish"),
                power = r.str("power"),
                toughness = r.str("toughness"),
                artist = r.str("artist"),
                qty = r.int("qty") ?: 0,
                printings = r.int("printings") ?: 0,
                free = r.int("free"),
                price = r.num("price"),
                value = r.num("value"),
                unpriced = r.int("unpriced") ?: 0,
            )
        }
    }

    /** The single number a COUNT(*) query answers with. */
    fun count(rows: List<JsonArray>): Int =
        rows.firstOrNull()?.getOrNull(0)?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0

    /** A one-column query — set codes, artists, the facet lists. */
    fun column(rows: List<JsonArray>): List<String> =
        rows.mapNotNull { (it.getOrNull(0) as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
}

/**
 * The Library screen's state.
 *
 * Same job as `MassEntry`: the platforms render it and neither of them
 * decides what it means. Paging maths in one place is the difference
 * between two implementations of "is there a next page" and one.
 */
data class Library(
    val filters: Filters = Filters(),
    val rows: List<CardRow> = emptyList(),
    val total: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    /**
     * The filters the rows on screen actually answer.
     *
     * Coming back from a card re-ran the whole search, because the
     * route changed and the route is what asks for a load. The grid
     * was replaced by "Searching…" while it did, the page lost its
     * height, and the scroll offset being restored into it had
     * nothing to hold on to — so back landed at the top of a list you
     * were two hundred cards into.
     */
    val loadedFor: Filters? = null,
) {
    /** These rows already answer what is on screen. Nothing to ask. */
    val fresh: Boolean get() = loadedFor == filters && error == null
    val page: Int get() = filters.page
    val size: Int get() = filters.size

    /** Never divides by zero: `size` is a field and 0 is constructible. */
    private val pageSize: Int get() = if (size > 0) size else PAGE_SIZE

    val pages: Int get() = if (total <= 0) 1 else ((total + pageSize - 1) / pageSize)
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page < pages

    /**
     * "101–200 of 6,607", the way a person reads it.
     *
     * Clamped to a page that exists: an out-of-range page produced a
     * backwards range, which rendered as "9801–250 of 250" over an
     * empty grid.
     */
    val showing: IntRange
        get() {
            if (total == 0) return IntRange.EMPTY
            val p = page.coerceIn(1, pages)
            return ((p - 1) * pageSize + 1)..minOf(p * pageSize, total)
        }

    /** The range as the page states it, or nothing when there is none. */
    val showingLabel: String
        get() = if (total == 0) "No cards" else "${showing.first}–${showing.last} of $total"

    val isEmpty: Boolean get() = !busy && error == null && rows.isEmpty()

    /** Changing a filter always returns to page one. Staying on page 9 of
     *  a result that now has two is how a search looks broken. */
    fun where(f: Filters) = copy(filters = f.copy(page = 1), error = null)

    fun goToPage(n: Int) = copy(filters = filters.copy(page = n.coerceIn(1, pages)), error = null)

    /**
     * A whole search arriving from a link.
     *
     * Not `where`: that resets to page one because it is the right
     * thing to do when somebody narrows a filter, and the wrong thing
     * when they were sent `?page=4` — which was ignored on every cold
     * load and every hashchange.
     */
    fun restoredFrom(f: Filters) = copy(filters = f, error = null)
    fun next() = if (hasNext) goToPage(page + 1) else this
    fun prev() = if (hasPrev) goToPage(page - 1) else this

    /**
     * Pick the column, leaving the direction alone.
     *
     * `sortBy` below flips when the same column is chosen again, which
     * is right for a row of buttons and wrong for a dropdown — picking
     * "Price" from a list should not silently reverse it.
     */
    fun sortedBy(sort: Sort) = where(filters.copy(sort = sort))

    fun sortBy(sort: Sort): Library {
        // Same column twice flips the direction, the way every table does.
        val flip = filters.sort == sort
        return where(filters.copy(sort = sort, descending = if (flip) !filters.descending else true))
    }

    fun loading() = copy(busy = true, error = null)
    fun loaded(rows: List<CardRow>, total: Int) =
        copy(rows = rows, total = total, busy = false, error = null, loadedFor = filters)
    fun failed(message: String) = copy(busy = false, error = message)

    /** The two statements a page of the Library needs. */
    fun queries(): Pair<Sql, Sql> = buildQuery(filters) to buildQuery(filters, countOnly = true)
}
