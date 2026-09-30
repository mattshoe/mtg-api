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
     * Two decimals under ten, whole dollars above it.
     *
     * A bulk common is 37 cents and the cents matter; a dual land is
     * four hundred and they do not.
     */
    fun money(v: Double?, dash: String = "—"): String {
        if (v == null) return dash
        // The sign belongs outside the symbol, and "under ten" has to
        // mean small rather than negative — `money(-4000.0)` was
        // taking the cents branch because -4000 < 10.
        val sign = if (v < 0) "-" else ""
        val abs = if (v < 0) -v else v
        return sign + if (abs < 10) "$" + twoPlaces(abs) else "$" + grouped(abs.roundToLongHalfUp())
    }

    /** Always two decimals, for a total that has to add up on screen. */
    fun exact(v: Double?, dash: String = "—"): String {
        if (v == null) return dash
        val sign = if (v < 0) "-" else ""
        return sign + "$" + twoPlaces(if (v < 0) -v else v)
    }

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

    /** "$18.20, at least" — a total that is missing some prices. */
    fun atLeast(v: Double?, unpriced: Int): String =
        if (unpriced <= 0) exact(v) else exact(v) + " + $unpriced unpriced"

    // ------------------------------------------------------------------
    // Formatting by hand: there is no shared locale-aware formatter
    // across JVM, JS and Native, and a price is not the place to find out
    // which platform rounds differently.

    private fun twoPlaces(v: Double): String {
        // Rounded on the decimal string rather than on the binary
        // double: `2.675 * 100` is 267.49999999999997, so a naive
        // half-up gave $2.67 where every other formatter gives $2.68.
        val cents = roundedCents(v)
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

    /** Half-up on the value as written, not as stored. */
    private fun roundedCents(v: Double): Long {
        val sign = if (v < 0) -1L else 1L
        val abs = if (v < 0) -v else v
        // Nudge by one ulp-ish before flooring, which is enough to
        // put x.xx5 on the right side without moving anything else.
        return sign * kotlin.math.floor(abs * 100 + 0.5 + 1e-9).toLong()
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

    /**
     * One deck, written the way a deck list is written.
     *
     * The commander on its own at the top with a blank line under it,
     * because that is the form every builder reads a commander from,
     * and the rest alphabetical. Quantities first so the whole thing
     * pastes straight back into the mass entry box here.
     */
    fun deck(cards: List<DeckCard>): String {
        fun line(c: DeckCard) = "${c.qty} ${c.shown}"
        val leaders = cards.filter { it.isCommander }.sortedBy { it.shown.lowercase() }
        val rest = cards.filterNot { it.isCommander }.sortedBy { it.shown.lowercase() }
        return (leaders.map(::line) + listOf("").takeIf { leaders.isNotEmpty() && rest.isNotEmpty() }.orEmpty() + rest.map(::line))
            .joinToString("\n")
    }

    /** `alela-2026-09-30.txt`. */
    fun deckFilename(slug: String, today: String) = "$slug-$today.txt"
}
