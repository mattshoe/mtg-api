package org.mattshoe.mtg.core

import kotlin.math.roundToInt

/** One bar of a chart: what it is, how many, and what the number means. */
data class Bar(val label: String, val value: Int, val note: String = "") {
    /** How wide to draw it, given the tallest bar beside it. */
    fun share(most: Int): Int = if (most <= 0) 0 else ((value * 100.0) / most).roundToInt()
}

/** A token the deck makes, and how many cards make it. */
data class TokenMade(val what: String, val cards: Int)

/**
 * What a deck is made of.
 *
 * All of it derived from the cards, in the shared core, so the phone
 * and the website cannot disagree about a deck's curve. Nothing here
 * touches the network: it is arithmetic over the rows the deck list
 * already loaded.
 *
 * Every count is by copies rather than by distinct names — a deck with
 * nine Mountains has nine red sources, not one.
 */
data class DeckStats(
    val curve: List<Bar>,
    val pips: List<Bar>,
    val sources: List<Bar>,
    val types: List<Bar>,
    val rarities: List<Bar>,
    val tokens: List<TokenMade>,
    val lands: Int,
    val spells: Int,
    /**
     * Cards the collection has no printing of, so nothing is known
     * about them. Counted and named rather than folded in: as
     * nought-drops they would flatten the curve and drag the average
     * down, and the chart would be lying rather than incomplete.
     */
    val unknown: Int,
    val totalCards: Int,
    val averageManaValue: Double,
    val medianManaValue: Double,
    val value: Double?,
    val unpriced: Int,
    val missing: Int,
    val identity: String,
) {
    val hasCurve: Boolean get() = curve.any { it.value > 0 }

    /** Lands as a share of the deck, the number every deckbuilder checks first. */
    val landShare: Int get() = if (totalCards <= 0) 0 else ((lands * 100.0) / totalCards).roundToInt()

    /**
     * Colours the deck asks for but cannot produce.
     *
     * The one pairing worth calling out: a splash with no sources is
     * a card that sits in hand, and it is invisible in either chart
     * on its own.
     */
    val unsupported: List<String>
        get() = Pip.COLOURS.mapNotNull { pip ->
            val wants = pips.firstOrNull { it.label == pip.label }?.value ?: 0
            val has = sources.firstOrNull { it.label == pip.label }?.value ?: 0
            if (wants > 0 && has == 0) pip.label else null
        }
}

object DeckAnalysis {

    /** Mana values above this are one bucket. A seven-drop and a ten are the same problem. */
    const val CURVE_TOP = 7

    fun of(cards: List<DeckCard>): DeckStats {
        val copies = cards.sumOf { it.qty }
        val lands = cards.filter { it.group == DeckGroup.LANDS }.sumOf { it.qty }
        val known = cards.filter { it.group != DeckGroup.UNKNOWN }
        val nonland = known.filter { it.group != DeckGroup.LANDS }

        return DeckStats(
            curve = curve(nonland),
            pips = pips(cards),
            sources = sources(cards),
            types = types(cards),
            rarities = rarities(cards),
            tokens = tokens(cards),
            lands = lands,
            spells = copies - lands,
            unknown = cards.filter { it.group == DeckGroup.UNKNOWN }.sumOf { it.qty },
            totalCards = copies,
            averageManaValue = average(nonland),
            medianManaValue = median(nonland),
            value = cards.mapNotNull { c -> c.price?.let { it * c.qty } }.takeIf { it.isNotEmpty() }?.sum(),
            unpriced = cards.filter { it.price == null }.sumOf { it.qty },
            missing = cards.sumOf { it.short },
            identity = identity(cards),
        )
    }

    /**
     * The curve, lands excluded.
     *
     * Lands cost nothing and would put a third of the deck in the
     * zero column, which is the one thing a curve must not say.
     */
    private fun curve(nonland: List<DeckCard>): List<Bar> {
        val buckets = IntArray(CURVE_TOP + 1)
        nonland.forEach { c ->
            val mv = (c.knownManaValue ?: ManaCost.manaValue(c.manaCost).toDouble())
                .toInt().coerceIn(0, CURVE_TOP)
            buckets[mv] += c.qty
        }
        return buckets.mapIndexed { mv, n ->
            Bar(if (mv == CURVE_TOP) "$CURVE_TOP+" else "$mv", n, "mana value $mv")
        }
    }

    /** How many coloured pips the deck asks for, by colour. */
    private fun pips(cards: List<DeckCard>): List<Bar> {
        val counts = mutableMapOf<Pip, Int>()
        cards.forEach { c ->
            ManaCost.pips(c.manaCost).forEach { pip ->
                counts[pip] = (counts[pip] ?: 0) + c.qty
            }
        }
        return Pip.entries.mapNotNull { pip ->
            counts[pip]?.takeIf { it > 0 }?.let { Bar(pip.label, it, pip.letter) }
        }
    }

    /** How many cards can make each colour. Lands and rocks alike. */
    private fun sources(cards: List<DeckCard>): List<Bar> {
        val counts = mutableMapOf<Pip, Int>()
        cards.forEach { c ->
            c.knownProducedMana.orEmpty().forEach { letter ->
                Pip.of(letter.toString().uppercase())?.let { pip ->
                    counts[pip] = (counts[pip] ?: 0) + c.qty
                }
            }
        }
        return Pip.entries.mapNotNull { pip ->
            counts[pip]?.takeIf { it > 0 }?.let { Bar(pip.label, it, pip.letter) }
        }
    }

    /** The deck by card type, in the order a list is written in. */
    private fun types(cards: List<DeckCard>): List<Bar> =
        DeckGroup.entries.mapNotNull { group ->
            cards.filter { it.group == group }.sumOf { it.qty }
                .takeIf { it > 0 }
                ?.let { Bar(group.title, it) }
        }

    private val RARITY_ORDER = listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

    private fun rarities(cards: List<DeckCard>): List<Bar> =
        RARITY_ORDER.mapNotNull { r ->
            cards.filter { it.rarity == r }.sumOf { it.qty }
                .takeIf { it > 0 }
                ?.let { Bar(r.replaceFirstChar(Char::uppercase), it) }
        }

    private fun average(nonland: List<DeckCard>): Double {
        val copies = nonland.sumOf { it.qty }
        if (copies == 0) return 0.0
        val total = nonland.sumOf { (it.knownManaValue ?: 0.0) * it.qty }
        return ((total / copies) * 100).roundToInt() / 100.0
    }

    private fun median(nonland: List<DeckCard>): Double {
        val all = nonland.flatMap { c -> List(c.qty) { c.knownManaValue ?: 0.0 } }.sorted()
        if (all.isEmpty()) return 0.0
        val mid = all.size / 2
        return if (all.size % 2 == 1) all[mid] else ((all[mid - 1] + all[mid]) / 2)
    }

    /** WUBRG, from every card in the deck. */
    private fun identity(cards: List<DeckCard>): String {
        val seen = cards.flatMap { it.colorIdentity.orEmpty().toList() }.toSet()
        return Pip.COLOURS.filter { it.letter.single() in seen }.joinToString("") { it.letter }
    }

    // --------------------------------------------------------- tokens

    /**
     * What the deck makes, read out of the rules text.
     *
     * Scryfall knows the real answer through `all_parts`, which this
     * database does not store, so this reads the oracle text: the
     * words between "create" and "token". It is wording, not truth —
     * but "you will need two Treasure tokens and a 1/1 white Soldier"
     * is the thing you actually want before you sit down, and nothing
     * else here can tell you.
     */
    private val MAKES = Regex(
        """\bcreates?\s+([^.;•]{0,120}?)\btokens?\b""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Leading counts. "two 1/1 Soldier" is the same token as "a 1/1 Soldier". */
    private val COUNT = Regex(
        // `\s+|$` and not `\s+`: "create a token that's a copy" leaves
        // a bare "a" behind, which is not a count word any more and
        // was surviving as a token called "A".
        """^(?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|x|that many|a number of|any number of)(?:\s+|$)""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun tokens(cards: List<DeckCard>): List<TokenMade> {
        val made = mutableMapOf<String, Int>()
        cards.forEach { card ->
            val names = MAKES.findAll(card.oracleText.orEmpty().replace('\n', ' '))
                .mapNotNull { describe(it.groupValues[1]) }
                .toSet()
            names.forEach { made[it] = (made[it] ?: 0) + card.qty }
        }
        return made.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { TokenMade(it.key, it.value) }
    }

    /** `1/1`, `X/X` — the shape of a creature token's stats. */
    private val STATS = Regex("[0-9Xx*+-]+/[0-9Xx*+-]+")

    /** A token's type is a proper noun: Soldier, Treasure, Clue, Map. */
    private val NAMED = Regex("\\b[A-Z][a-z]{2,}")

    private fun describe(raw: String): String? {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = COUNT.replace(t, "")
        t = t.trim().trimEnd(',')
        // "create a token that's a copy of..." — the descriptor is
        // after the word, not before it.
        if (t.isEmpty() || t.startsWith("that", ignoreCase = true)) return "Copy of another permanent"
        // Sentence fragments that happen to sit between "create" and
        // "token": "create two **or more** tokens", "create **twice
        // that many of those** tokens". A real token is named or has
        // power and toughness; these are neither.
        if (!STATS.containsMatchIn(t) && !NAMED.containsMatchIn(t)) return null
        return t.replaceFirstChar(Char::uppercase)
    }
}
