package org.mattshoe.mtg.core

/**
 * Where a card is bought, narrowed to the thing you actually wanted.
 *
 * Scryfall hands over a TCGplayer product page when it knows the
 * product, and a plain name search when it does not. For tokens the
 * search is usually wrong: "Sculpture Treasure" returns the token and
 * three printings of Vraska, Soul of Stone, because a token shares
 * its name with whatever card made it.
 *
 * TCGplayer's own search carries a rarity facet and tokens are their
 * own rarity there, so a search can be told to list nothing else.
 */
object Tcg {

    /** TCGplayer's facet for the rarity tokens are filed under. */
    private const val FILTER = "Rarity=Token"

    /** Percent-encoded, for the copy that rides inside `u=`. */
    private const val FILTER_IN_U = "%26Rarity%3DToken"

    /**
     * True when the link lands on one product.
     *
     * Either spelling: the affiliate wrapper carries the real address
     * percent-encoded in its `u` parameter.
     */
    fun isProduct(url: String): Boolean = "/product/" in url || "%2Fproduct%2F" in url

    /**
     * The same link, listing tokens only.
     *
     * A product page is already exact and comes back untouched. A
     * search gets the facet added, inside `u=` when the link is
     * wrapped, so the affiliate credit survives.
     */
    fun tokensOnly(url: String): String = when {
        url.isBlank() -> url
        isProduct(url) -> url
        "Rarity" in url -> url
        else -> {
            val u = url.indexOf("u=")
            if (u < 0) {
                url + (if ("?" in url) "&" else "?") + FILTER
            } else {
                val from = u + 2
                val to = url.indexOf('&', from).let { if (it < 0) url.length else it }
                url.substring(0, to) + FILTER_IN_U + url.substring(to)
            }
        }
    }
}
