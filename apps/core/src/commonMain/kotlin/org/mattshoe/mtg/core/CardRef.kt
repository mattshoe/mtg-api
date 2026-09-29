package org.mattshoe.mtg.core

/**
 * The open card, as a thing you can send somebody.
 *
 * The drawer is an overlay rather than a screen — it opens over the
 * search or over a deck and back should dismiss it, not navigate — so
 * it is not a `View`. But a link to a card is the most obvious thing
 * to want to share, and an overlay with no address cannot be shared at
 * all.
 *
 * So it rides in the query string of whatever route is underneath:
 * `#/search?q=bolt&card=matt:lightning+bolt`, or
 * `#/decks/alela?card=matt:sol+ring`. The page you send arrives the way
 * you left it, card and all.
 */
data class CardRef(val owner: String, val nameNorm: String) {

    /** `owner:name_norm`, both halves encoded so a colon in a name is safe. */
    fun encoded(): String = FilterUrl.encode(owner) + ":" + FilterUrl.encode(nameNorm)

    companion object {
        const val KEY = "card"

        fun parse(raw: String?): CardRef? {
            val v = raw.orEmpty()
            val i = v.indexOf(':')
            if (i <= 0 || i == v.length - 1) return null
            val owner = FilterUrl.decode(v.substring(0, i))
            val norm = FilterUrl.decode(v.substring(i + 1))
            if (owner.isBlank() || norm.isBlank()) return null
            return CardRef(owner, norm)
        }

        /** Pulls `card=` out of a raw query string, ignoring the rest. */
        fun from(query: String?): CardRef? = query.orEmpty()
            .removePrefix("?")
            .split("&")
            .firstOrNull { it.startsWith("$KEY=") }
            ?.let { parse(it.removePrefix("$KEY=")) }

        /**
         * The hash for a route that has a card open over it.
         *
         * Appended rather than rebuilt, so it works the same over the
         * Library's sixty filter parameters and over a bare `#/stats`.
         */
        fun appendTo(hash: String, ref: CardRef?): String {
            if (ref == null) return hash
            val sep = if (hash.contains('?')) "&" else "?"
            return hash + sep + KEY + "=" + ref.encoded()
        }
    }
}
