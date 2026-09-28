package org.mattshoe.mtg.core

/**
 * Money, and why a card sometimes has none.
 *
 * A port of `frontend/js/prices.js`. The formatting rules are shared
 * because a price that reads `$2.50` on one platform and `$2.5` on the
 * other is the kind of difference that makes two apps feel like two
 * apps.
 */
object Prices {

    /** Things that are never sold as singles, so have no market price. */
    private val UNPRICED_LAYOUTS = setOf("token", "double_faced_token", "emblem", "art_series")

    /**
     * Two decimals under ten, whole pounds above it.
     *
     * A bulk common is 37 cents and the cents matter; a dual land is
     * four hundred and they do not.
     */
    fun money(v: Double?, dash: String = "—"): String {
        if (v == null) return dash
        return if (v < 10) "$" + twoPlaces(v) else "$" + grouped(v.roundToLongHalfUp())
    }

    /** Always two decimals, for a total that has to add up on screen. */
    fun exact(v: Double?): String = if (v == null) "—" else "$" + twoPlaces(v)

    /**
     * Why a card has no price.
     *
     * "—" on its own reads as a failure and most of the time it is not
     * one: an unreleased printing has no market yet, and a token never
     * will.
     */
    fun reason(layout: String?, releasedAt: String?, today: String): String {
        if (releasedAt != null && releasedAt > today) return "releases $releasedAt"
        if (layout in UNPRICED_LAYOUTS) return "not sold singly"
        return "no market price"
    }

    /** The price, or a short explanation of its absence. */
    fun orReason(price: Double?, layout: String?, releasedAt: String?, today: String): String =
        if (price == null) reason(layout, releasedAt, today) else exact(price)

    // ------------------------------------------------------------------
    // Formatting by hand: there is no shared locale-aware formatter
    // across JVM, JS and Native, and a price is not the place to find out
    // which platform rounds differently.

    private fun twoPlaces(v: Double): String {
        val cents = (v * 100).roundToLongHalfUp()
        val sign = if (cents < 0) "-" else ""
        val abs = if (cents < 0) -cents else cents
        return "$sign${grouped(abs / 100)}.${(abs % 100).toString().padStart(2, '0')}"
    }

    private fun grouped(n: Long): String {
        val s = (if (n < 0) -n else n).toString()
        val out = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return (if (n < 0) "-" else "") + out
    }

    private fun Double.roundToLongHalfUp(): Long {
        val floor = kotlin.math.floor(this)
        return (if (this - floor >= 0.5) floor + 1 else floor).toLong()
    }
}

/**
 * The whole filtered set as a decklist, not just the page on screen.
 *
 * No set code or collector number: a row is a card summed over every
 * printing owned, so pinning it to one printing would be a lie. "3 Sol
 * Ring" is what every deckbuilder reads anyway.
 */
object Export {

    /** The server's own ceiling on one read. */
    const val CAP = 5000

    /** The query behind an export: the same search, unpaged. */
    fun query(filters: Filters): Sql = buildQuery(filters.copy(page = 1, size = CAP))

    fun decklist(rows: List<CardRow>): String =
        rows.joinToString("\n") { "${it.qty} ${it.fullName}" }

    /** `mtg-decklist-2026-09-28.txt`. */
    fun filename(today: String) = "mtg-decklist-$today.txt"
}
