package org.mattshoe.mtg.core

/**
 * The lands everybody owns and nobody inventories.
 *
 * A deck list is full of Plains and Islands that match no row in the
 * collection, because people do not count their basics — 434 copies
 * of them across eighteen decks, in this one. Every one of those was
 * a card the analysis knew nothing about: not a land, no colour
 * produced, no mana value. Feather Storm read as a 22-land deck with
 * 14 white sources when it has 37 and 27.
 *
 * These twelve names are the one case where the answer does not need
 * a printing to look it up in. They have not changed since 1993 and
 * they are not going to.
 */
data class Basic(val typeLine: String, val produces: String) {

    companion object {
        private const val SNOW = "Snow "

        private val COLOURS = mapOf(
            "plains" to "W",
            "island" to "U",
            "swamp" to "B",
            "mountain" to "R",
            "forest" to "G",
        )

        /** Wastes is the colourless one, and is not snow-covered. */
        private val WASTES = Basic("Basic Land", "C")

        private val ALL: Map<String, Basic> = buildMap {
            COLOURS.forEach { (name, mana) ->
                val type = name.replaceFirstChar(Char::uppercase)
                put(name, Basic("Basic Land — $type", mana))
                put("snow-covered $name", Basic("${SNOW}Basic Land — $type", mana))
            }
            put("wastes", WASTES)
        }

        /** By `name_norm`, which is what a deck row carries. */
        fun of(nameNorm: String): Basic? = ALL[nameNorm.trim().lowercase()]

        val names: Set<String> get() = ALL.keys
    }
}
