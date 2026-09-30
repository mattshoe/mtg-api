package org.mattshoe.mtg.core

/**
 * The five colours, plus the one that is the absence of them.
 *
 * Ordered WUBRG, which is the order every Magic player reads them in
 * and therefore the order every chart here draws them in.
 */
enum class Pip(val letter: String, val label: String) {
    W("W", "White"),
    U("U", "Blue"),
    B("B", "Black"),
    R("R", "Red"),
    G("G", "Green"),
    C("C", "Colourless"),
    ;

    companion object {
        val COLOURS = listOf(W, U, B, R, G)
        fun of(letter: String): Pip? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * A mana cost, read symbol by symbol.
 *
 * `{2}{W}{W}` is two generic and two white. The awkward ones are the
 * hybrids: `{W/U}` can be paid either way, `{2/W}` either way, and
 * `{W/P}` is white or two life. All three count toward every colour
 * they could be paid with, because the question a colour chart
 * answers is "how hard is this to cast", and a card you can only pay
 * white for is exactly as demanding whichever half you use.
 */
object ManaCost {

    /** `{2}{W/U}{G}` into `["2", "W/U", "G"]`. */
    fun symbols(cost: String?): List<String> {
        val raw = cost.orEmpty()
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '{') { i++; continue }
            val end = raw.indexOf('}', i + 1)
            if (end < 0) break
            out += raw.substring(i + 1, end)
            i = end + 1
        }
        return out
    }

    /**
     * Coloured pips only, one entry per colour a symbol can pay for.
     *
     * Generic (`{2}`), variable (`{X}`) and snow (`{S}`) are not
     * colour requirements and are left out; `{C}` is, and is counted.
     */
    fun pips(cost: String?): List<Pip> = symbols(cost).flatMap { symbol ->
        val parts = symbol.split("/")
        parts.mapNotNull { part ->
            // `P` is the phyrexian half and `2` the generic half of a
            // hybrid; neither is a colour of its own.
            if (part == "P" || part.toIntOrNull() != null) null else Pip.of(part.uppercase())
        }
    }

    /**
     * Scryfall's own artwork for a symbol.
     *
     * `{W}` is `W.svg`, `{W/U}` is `WU.svg`, `{2/W}` is `2W.svg` —
     * the symbol with its braces and slashes taken out. Checked
     * against Scryfall's `/symbology`, which is where the rule comes
     * from rather than a guess.
     *
     * The real symbols rather than a letter in a circle: a mana cost
     * drawn as `{1}{G}` is something to decode, and everybody who
     * plays this game already reads the pictures.
     */
    fun symbolArt(symbol: String): String {
        val key = symbol.uppercase().filter { it.isLetterOrDigit() }
        return "$SYMBOL_BASE/$key.svg"
    }

    /** Every symbol of a cost, as artwork, in the order they are printed. */
    fun art(cost: String?): List<Pair<String, String>> =
        symbols(cost).map { it to symbolArt(it) }

    const val SYMBOL_BASE = "https://svgs.scryfall.io/card-symbols"

    /** What the cost costs, ignoring colour. `{X}` counts as nothing. */
    fun manaValue(cost: String?): Int = symbols(cost).sumOf { symbol ->
        val first = symbol.split("/").first()
        first.toIntOrNull() ?: if (symbol.uppercase() == "X") 0 else 1
    }
}
