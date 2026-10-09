package org.mattshoe.mtg.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A mana value, as text — "2", never "2.0".
 *
 * `Double.toString()` is not the same function on Kotlin/JS and
 * Kotlin/JVM: JS drops a trailing ".0" and the JVM never does, so a
 * deck whose average sat at exactly 2.0 read "2" on the website and
 * "2.0" on the phone, off the one shared `Double`. Scaled to an
 * integer of hundredths and back, the way `Prices` rounds cents,
 * rather than trusting either platform's own formatter — there is
 * nothing left for either to disagree about.
 */
fun manaValueText(v: Double): String {
    val sign = if (v < 0) "-" else ""
    val hundredths = round(abs(v) * 100).toLong()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return if (frac == 0L) "$sign$whole" else "$sign$whole.${frac.toString().padStart(2, '0').trimEnd('0')}"
}

/** One bar of a chart: what it is, how many, and what the number means. */
data class Bar(val label: String, val value: Int, val note: String = "") {
    /**
     * The colours to draw it in, as WUBRG letters.
     *
     * A colour bar is labelled "Red" and a combination bar "UR"; a
     * ring draws either from this, and a combination is one band per
     * colour rather than a hue of its own nobody could name.
     */
    val letters: List<String>
        get() = Pip.entries.firstOrNull { it.label == label }?.let { listOf(it.letter) }
            ?: label.map { it.toString() }.filter { Pip.of(it) != null }.ifEmpty { listOf("C") }

    val fill: Long get() = 0L

    /** How wide to draw it, given the tallest bar beside it. */
    fun share(most: Int): Int = if (most <= 0) 0 else ((value * 100.0) / most).roundToInt()
}

/**
 * One slice of a ring, with the number it is labelled by.
 *
 * Matt: "It's not at all clear which slice is which in the exactly
 * chart." A combination is drawn as a band of each of its colours, so
 * a UR slice beside a U one is blue running into blue. The number on
 * the slice and the same number in the table are what tell them apart.
 *
 * [middle] is in degrees clockwise from twelve o'clock, which is where
 * both shells start drawing.
 */
data class RingSlice(val number: Int, val bar: Bar, val percent: Int, val middle: Double) {
    /** "Izzet", "Mono-red", "Colourless". */
    val name: String get() = bar.note.ifEmpty { bar.label }

    /**
     * Where to put the number, as fractions of the ring's box from its
     * top left: [radius] is a fraction of the ring's radius.
     */
    fun at(radius: Double): Pair<Double, Double> {
        val rad = middle * PI / 180
        return (0.5 + radius / 2 * sin(rad)) to (0.5 - radius / 2 * cos(rad))
    }

    companion object {
        fun of(bars: List<Bar>): List<RingSlice> {
            val drawn = bars.filter { it.value > 0 }
            val total = drawn.sumOf { it.value }
            if (total <= 0) return emptyList()
            var at = 0.0
            return drawn.mapIndexed { i, bar ->
                val sweep = 360.0 * bar.value / total
                val slice = RingSlice(i + 1, bar, ((bar.value * 100.0) / total).roundToInt(), at + sweep / 2)
                at += sweep
                slice
            }
        }
    }
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
    /**
     * Where to buy one, from Scryfall's own purchase link.
     *
     * Null when Scryfall has no listing — a token from a set nobody
     * sells singles of, mostly. The row then stays a row rather than
     * becoming a link to nowhere.
     */
    val tcgplayer: String? = null,
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
    /**
     * What the deck makes, by exact combination: a card making blue
     * and red is one "UR" source, not one of each.
     *
     * Matt: "a R slice would ONLY account for cards that produce ONLY
     * red mana, while a UR slice would ONLY account for cards that
     * produce EXACTLY UR".
     */
    val combos: List<Bar>,
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
     * "2", not "2.0" — see [manaValueText]. A screen prints this
     * instead of calling `averageManaValue.toString()` itself.
     */
    val averageManaValueText: String get() = manaValueText(averageManaValue)

    /** The median's own text, for the same reason. */
    val medianManaValueText: String get() = manaValueText(medianManaValue)

    /**
     * Colours the deck asks for but cannot produce.
     *
     * The one pairing worth calling out: a splash with no sources is
     * a card that sits in hand, and it is invisible in either chart
     * on its own.
     */
    /**
     * The Exactly ring's slices, numbered. The number is drawn on the
     * slice and again in the table under it, so which slice is which
     * never rests on telling two colours apart.
     */
    val exactly: List<RingSlice> get() = RingSlice.of(combos)

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
            sources = sources(cards, spendable(cards)),
            combos = combos(cards, spendable(cards)),
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
     * What a card costs.
     *
     * The printing's own mana value, or what its printed cost adds up
     * to when the row has one but no `cmc`. One function rather than
     * three: the curve read the cost and the average did not, so the
     * same card was a four-drop in one chart and a nought-drop in the
     * other.
     */
    private fun manaValue(c: DeckCard): Double =
        c.knownManaValue ?: ManaCost.manaValue(c.manaCost).toDouble()

    /**
     * The curve, lands excluded.
     *
     * Lands cost nothing and would put a third of the deck in the
     * zero column, which is the one thing a curve must not say.
     */
    private fun curve(nonland: List<DeckCard>): List<Bar> {
        val buckets = IntArray(CURVE_TOP + 1)
        nonland.forEach { c ->
            val mv = manaValue(c).toInt().coerceIn(0, CURVE_TOP)
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

    /**
     * The colours this deck is allowed to spend, or null when nothing
     * says. A commander sets it, and in Commander that is the rule
     * every other card in the deck already obeys.
     */
    private fun spendable(cards: List<DeckCard>): Set<Char>? {
        val leaders = cards.filter { it.isCommander }
        if (leaders.isEmpty()) return null
        // A commander nobody owns a printing of has no colour identity
        // to read, which is not the same as having none. Treated as an
        // empty identity it folded every land in the deck into one
        // colourless source and reported every colour the deck plays
        // as unsupported.
        if (leaders.all { it.colorIdentity == null }) return null
        return leaders.flatMap { it.colorIdentity.orEmpty().toList() }.toSet()
    }

    /**
     * How many cards can make each colour. Lands and rocks alike.
     *
     * Mana outside the commander's identity is folded into
     * colourless, because that is all it can ever be: nothing in the
     * deck has a coloured cost it could pay, so it is generic mana
     * with a colour nobody can use. A Reflecting Pool in a mono-black
     * deck makes one black source, not five of something.
     */
    private fun sources(cards: List<DeckCard>, spendable: Set<Char>?): List<Bar> {
        val counts = mutableMapOf<Pip, Int>()
        cards.forEach { c ->
            // One card making the same colour twice is still one
            // source of it, but a dual making two different colours
            // is a source of each.
            makes(c, spendable).forEach { pip -> counts[pip] = (counts[pip] ?: 0) + c.qty }
        }
        return Pip.entries.mapNotNull { pip ->
            counts[pip]?.takeIf { it > 0 }?.let { Bar(pip.label, it, pip.letter) }
        }
    }

    /** What one card can make, once each, with unusable colours folded to colourless. */
    private fun makes(c: DeckCard, spendable: Set<Char>?): List<Pip> =
        c.knownProducedMana.orEmpty()
            .mapNotNull { Pip.of(it.toString().uppercase()) }
            .map { pip ->
                val usable = spendable == null || pip == Pip.C || pip.letter.single() in spendable
                if (usable) pip else Pip.C
            }
            .distinct()

    /**
     * How many cards make each exact set of colours.
     *
     * Colourless beside a colour is not a combination of its own: a
     * painland making {C}, {U} or {R} is a UR land. Only a card that
     * makes nothing coloured is colourless. Ordered singles first,
     * then pairs and up, each in WUBRG order, colourless last.
     */
    private fun combos(cards: List<DeckCard>, spendable: Set<Char>?): List<Bar> {
        val counts = mutableMapOf<String, Int>()
        cards.forEach { c ->
            val pips = makes(c, spendable)
            if (pips.isEmpty()) return@forEach
            val key = Pip.COLOURS.filter { it in pips }.joinToString("") { it.letter }.ifEmpty { Pip.C.letter }
            counts[key] = (counts[key] ?: 0) + c.qty
        }
        val order = "WUBRGC"
        return counts.entries
            .sortedWith(
                compareBy<Map.Entry<String, Int>>({ it.key == Pip.C.letter }, { it.key.length })
                    .thenBy { e -> e.key.map { order.indexOf(it) }.joinToString(",") { it.toString() } },
            )
            .map { (key, n) -> Bar(key, n, Guild.of(key.takeIf { it != Pip.C.letter }.orEmpty()).orEmpty()) }
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
        val total = nonland.sumOf { manaValue(it) * it.qty }
        return ((total / copies) * 100).roundToInt() / 100.0
    }

    private fun median(nonland: List<DeckCard>): Double {
        val all = nonland.flatMap { c -> List(c.qty) { manaValue(c) } }.sorted()
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
