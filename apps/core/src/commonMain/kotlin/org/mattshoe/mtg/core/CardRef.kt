package org.mattshoe.mtg.core

/**
 * Which card a card page is showing.
 *
 * `#/card/matt:lightning+bolt` — the whole address, naming the card
 * and nothing else. It used to ride in the query string of whichever
 * page the drawer was open over, so a link to a card carried the deck
 * somebody happened to have open when they copied it.
 */
data class CardRef(val owner: String, val nameNorm: String) {

    /** `owner:name_norm`, both halves encoded so a colon in a name is safe. */
    fun encoded(): String = FilterUrl.encode(owner) + ":" + FilterUrl.encode(nameNorm)

    /** The address of this card's page. */
    fun route(): Route = Route(View.CARD, encoded())

    companion object {

        fun parse(raw: String?): CardRef? {
            val v = raw.orEmpty()
            val i = v.indexOf(':')
            if (i <= 0 || i == v.length - 1) return null
            val owner = FilterUrl.decode(v.substring(0, i))
            val norm = FilterUrl.decode(v.substring(i + 1))
            if (owner.isBlank() || norm.isBlank()) return null
            return CardRef(owner, norm)
        }

    }
}
