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
data class StatsScope(val owner: Owner? = null) {
    val label: String get() = owner?.label ?: "Both"

    /** `1=1` rather than an empty string, so it always slots into a WHERE. */
    internal val where: String get() = if (owner == null) "1=1" else "owner = ?"
    internal val params: List<Any?> get() = owner?.let { listOf(it.slug) } ?: emptyList()
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
        """SELECT c.owner,
                  COUNT(*)                        AS printings,
                  COUNT(DISTINCT c.name_norm)     AS uniques,
                  SUM(c.qty)                      AS physical
             FROM cards c
            GROUP BY c.owner
            ORDER BY c.owner""",
        emptyList(),
    )

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
) {
    fun scopedTo(owner: Owner?) = copy(scope = StatsScope(owner), error = null)
    fun loading() = copy(busy = true, error = null)
    fun loaded(t: Totals) = copy(totals = t, busy = false, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
}
