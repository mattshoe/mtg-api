package org.mattshoe.mtg.core

/**
 * What a colour combination is called.
 *
 * Matt: "I want the 'deck at a glance' view to show which guild or
 * whatever you call it."
 *
 * A row of pips says which colours a deck is; it does not say that UB
 * is Dimir, and Dimir is how people actually talk about decks. Magic
 * names every combination: ten guilds, ten shards and wedges, five
 * mono colours, five four-colour sets and WUBRG.
 *
 * Keyed on the letters in WUBRG order, so the caller does not have to
 * care what order they arrived in — `Deck.identity` already sorts
 * them, but a hand-written row might not.
 */
object Guild {

    private val NAMES: Map<String, String> = mapOf(
        // One colour.
        "W" to "Mono-white", "U" to "Mono-blue", "B" to "Mono-black",
        "R" to "Mono-red", "G" to "Mono-green",

        // The ten guilds.
        "WU" to "Azorius", "UB" to "Dimir", "BR" to "Rakdos", "RG" to "Gruul",
        "WG" to "Selesnya", "WB" to "Orzhov", "UR" to "Izzet", "BG" to "Golgari",
        "WR" to "Boros", "UG" to "Simic",

        // The five shards and the five wedges.
        "WUB" to "Esper", "UBR" to "Grixis", "BRG" to "Jund",
        "WRG" to "Naya", "WUG" to "Bant",
        "WBG" to "Abzan", "WUR" to "Jeskai", "UBG" to "Sultai",
        "WBR" to "Mardu", "URG" to "Temur",

        "WUBRG" to "Five-colour",
    )

    /** What each four-colour set leaves out, which is how they are said. */
    private val MISSING: Map<String, String> = mapOf(
        "UBRG" to "white", "WBRG" to "blue", "WURG" to "black",
        "WUBG" to "red", "WUBR" to "green",
    )

    /**
     * The name, or null if those are not colours.
     *
     * An empty string is "Colourless" — a genuinely colourless deck,
     * which is a real thing. Null input is colourless too; it is
     * `Deck.guild` that decides whether "nobody worked it out" should
     * say anything at all, because that is a different fact from
     * "this deck has no colours".
     */
    fun of(letters: String?): String? {
        val sorted = sort(letters.orEmpty()) ?: return null
        if (sorted.isEmpty()) return "Colourless"
        NAMES[sorted]?.let { return it }
        MISSING[sorted]?.let { return "Four-colour, no $it" }
        return null
    }

    /** WUBRG order, de-duplicated, or null if anything is not a colour. */
    private fun sort(raw: String): String? {
        val letters = raw.uppercase()
        if (letters.any { it !in "WUBRG" }) return null
        return "WUBRG".filter { it in letters }
    }
}
