package org.mattshoe.mtg.core

/**
 * Which card a card page is showing.
 *
 * `#/card/lightning+bolt` — the card, and nothing about who is
 * looking at it. It used to carry an owner as well, which gave the
 * same card two addresses and hid Kayla's copies from Matt's page.
 * Who owns how many is a section on the page, not part of its
 * identity.
 */
data class CardRef(val nameNorm: String) {

    /** Encoded, so a name with a slash or a space in it survives the hash. */
    fun encoded(): String = FilterUrl.encode(nameNorm)

    /** The address of this card's page. */
    fun route(): Route = Route(View.CARD, encoded())

    companion object {

        fun parse(raw: String?): CardRef? {
            val norm = FilterUrl.decode(raw.orEmpty()).trim()
            if (norm.isBlank()) return null
            return CardRef(norm)
        }
    }
}
