package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Collection totals, scoped to everyone or to one person.
 *
 * A port of the headline numbers in `frontend/js/stats.js`. The scope
 * is a WHERE fragment rather than a string the caller pastes in, so
 * there is one place that decides what "Matt's collection" means.
 */
data class Totals(
    val printings: Int = 0,
    val uniques: Int = 0,
    val physical: Int = 0,
    val decks: Int = 0,
    val free: Int = 0,
    val sets: Int = 0,
    val foils: Int = 0,
    val value: Double? = null,
    val pricedAt: String? = null,
)

/** Whose numbers. Null is everyone, and is not the same as a person. */
/**
 * Which collection the numbers are about.
 *
 * The owner's public key, and null for every collection at once. It was one of two
 * names and a "Both" that nobody owns, chosen from a switch on the
 * page — a real question when there were two collections and a
 * password that could write to either, and a meaningless one now
 * that the page is somebody's collection.
 */
data class StatsScope(val owner: String? = null) {
    val label: String get() = owner ?: "Everything"

    /** `1=1` rather than an empty string, so it always slots into a WHERE. */
    internal val where: String get() = if (owner == null) "1=1" else Owners.owns("owner_id")
    internal val params: List<Any?> get() = owner?.let { listOf(it) } ?: emptyList()
}

object StatsQueries {

    /**
     * The headline row.
     *
     * Every count is its own subquery against the same scope, so adding
     * one cannot change what another means. The scope is repeated, so
     * its parameter is too — eight times, in the order the subqueries
     * appear, which is why this builds the list rather than trusting a
     * caller to.
     */
    fun totals(scope: StatsScope): Sql {
        val w = scope.where
        val sql = """SELECT
            (SELECT COUNT(*) FROM cards WHERE $w)                     AS printings,
            (SELECT COUNT(*) FROM totals WHERE $w)                    AS uniques,
            (SELECT COALESCE(SUM(qty), 0) FROM cards WHERE $w)        AS physical,
            (SELECT COUNT(*) FROM decks WHERE $w)                     AS decks,
            (SELECT COALESCE(SUM(free), 0) FROM bulk_cards WHERE $w)  AS free,
            (SELECT COUNT(DISTINCT setcode) FROM cards WHERE $w)      AS sets,
            (SELECT COUNT(*) FROM cards WHERE finish != 'nonfoil' AND $w) AS foils,
            (SELECT ROUND(SUM(qty * price)) FROM card_prices
               WHERE price IS NOT NULL AND $w)                        AS value,
            (SELECT MAX(updated_at) FROM prices)                      AS priced_at"""
        // Eight scoped subqueries, in order. The ninth has no scope.
        val params = buildList { repeat(8) { addAll(scope.params) } }
        return Sql(sql, params)
    }

    /** The side-by-side, always both, whatever the page is scoped to. */
    fun perOwner() = Sql(
        """SELECT ${Owners.nameOf("c.owner_id")} AS owner,
                  COUNT(*)                        AS printings,
                  COUNT(DISTINCT c.name_norm)     AS uniques,
                  SUM(c.qty)                      AS physical
             FROM cards c
            GROUP BY c.owner_id
            ORDER BY c.owner_id""",
        emptyList(),
    )

    /**
     * Every stack in the collection, with what the charts need of it.
     *
     * Its own LIMIT, because the server caps a statement without one
     * at 5,000 rows and Matt's collection is over 6,000 printings: the
     * charts would have been quietly about most of it.
     */
    fun cards(scope: StatsScope): Sql {
        val w = if (scope.owner == null) "1=1" else Owners.owns("c.owner_id")
        return Sql(
            """SELECT c.name, c.qty, c.finish, c.cmc, c.type_line, c.color_identity,
                      c.rarity, c.set_name, substr(c.released_at, 1, 4) AS year, c.artist,
                      cp.price
                 FROM cards c
                 LEFT JOIN card_prices cp ON cp.card_id = c.id
                WHERE $w
                LIMIT 50000""",
            scope.params,
        )
    }

    fun decodeCards(cols: List<String>, rows: List<JsonArray>): List<StatCard> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map { row ->
            fun str(n: String): String? {
                val v = at[n]?.let { row.getOrNull(it) } ?: return null
                if (v is JsonNull) return null
                return (v as? JsonPrimitive)?.content
            }
            StatCard(
                name = str("name").orEmpty(),
                qty = str("qty")?.toDoubleOrNull()?.toInt() ?: 0,
                foil = str("finish").let { it != null && it != "nonfoil" },
                manaValue = str("cmc")?.toDoubleOrNull() ?: 0.0,
                typeLine = str("type_line").orEmpty(),
                identity = str("color_identity").orEmpty().uppercase(),
                rarity = str("rarity").orEmpty(),
                setName = str("set_name").orEmpty(),
                year = str("year").orEmpty(),
                artist = str("artist").orEmpty(),
                price = str("price")?.toDoubleOrNull(),
            )
        }
    }

    fun decode(cols: List<String>, rows: List<JsonArray>): Totals {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        val row = rows.firstOrNull() ?: return Totals()
        fun str(n: String): String? {
            val v = at[n]?.let { row.getOrNull(it) } ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.content
        }
        fun int(n: String) = str(n)?.toDoubleOrNull()?.toInt() ?: 0
        return Totals(
            printings = int("printings"),
            uniques = int("uniques"),
            physical = int("physical"),
            decks = int("decks"),
            free = int("free"),
            sets = int("sets"),
            foils = int("foils"),
            value = str("value")?.toDoubleOrNull(),
            pricedAt = str("priced_at"),
        )
    }
}

data class StatsState(
    val scope: StatsScope = StatsScope(),
    val totals: Totals = Totals(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Every stack in the collection. The charts are worked out from these, here. */
    val cards: List<StatCard> = emptyList(),
    val tab: StatTab = StatTab.ALL,
    val filter: StatFilter = StatFilter(),
) {
    /** What the page draws: the cards on [tab], under [filter]. */
    val breakdown: CollectionBreakdown by lazy { CollectionAnalysis.of(cards, tab, filter) }

    /** The tabs worth offering, with how many copies each holds under [filter]. */
    val tabs: List<Pair<StatTab, Int>> by lazy { CollectionAnalysis.tabs(cards, filter) }

    /** The rarities the collection has, for the filter's chips, in rarity order. */
    val rarities: List<String> by lazy {
        listOf("common", "uncommon", "rare", "mythic", "special", "bonus").filter { r -> cards.any { it.rarity == r } }
    }

    fun scopedTo(owner: String?) = copy(scope = StatsScope(owner), error = null)
    fun loading() = copy(busy = true, error = null)
    fun loaded(t: Totals) = copy(totals = t, busy = false, error = null)
    fun loaded(t: Totals, cards: List<StatCard>) = copy(totals = t, cards = cards, busy = false, error = null)
    fun onTab(t: StatTab) = copy(tab = t)
    fun toggleColour(pip: Pip) = copy(filter = filter.toggle(pip))
    fun toggleRarity(r: String) = copy(filter = filter.toggleRarity(r))

    /** Back to every colour and rarity, on the same tab. */
    fun clearFilters() = copy(filter = StatFilter())
    fun failed(message: String) = copy(busy = false, error = message)
}
