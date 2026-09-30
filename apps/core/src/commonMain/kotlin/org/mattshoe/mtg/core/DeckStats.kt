package org.mattshoe.mtg.core

import kotlin.math.roundToInt

/** One bar of a chart: what it is, how many, and what the number means. */
data class Bar(val label: String, val value: Int, val note: String = "") {
    /** How wide to draw it, given the tallest bar beside it. */
    fun share(most: Int): Int = if (most <= 0) 0 else ((value * 100.0) / most).roundToInt()
}

/**
 * A token the deck makes — the real card, not a description of one.
 *
 * Scryfall names the token components of every card in `all_parts`,
 * so these are actual printed tokens with their own art. Reading them
 * out of the rules text, which is what this did first, produced
 * things like "Or more" and could never find the art.
 */
data class TokenCard(
    val id: String,
    val name: String,
    val typeLine: String,
    val power: String? = null,
    val toughness: String? = null,
    val colors: String = "",
    /** How many cards in the deck make it. */
    val madeBy: Int = 1,
) {
    val art: String? get() = CardQueries.art(id, "art_crop")

    /** "Creature — Soldier", without the "Token" every one of them starts with. */
    val shortType: String get() = typeLine.removePrefix("Token ").trim()

    /** `1/1`, when it has them. */
    val stats: String? get() =
        if (power.isNullOrBlank() || toughness.isNullOrBlank()) null else "$power/$toughness"

    /**
     * What makes one token different from another.
     *
     * Not the id: a Bird token printed in four sets is four ids and
     * one token, and the deck page listed it four times. A 1/1 white
     * Bird and a 2/2 blue Bird are genuinely two, and only the power,
     * the toughness and the colours tell them apart — `all_parts`
     * carries none of that, which is why the tokens themselves are
     * fetched rather than just their names.
     */
    val identity: String get() = listOf(name, shortType, stats.orEmpty(), colors).joinToString("|")
}

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

}
