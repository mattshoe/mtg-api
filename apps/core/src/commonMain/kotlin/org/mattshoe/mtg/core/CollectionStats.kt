package org.mattshoe.mtg.core

import kotlin.math.roundToInt

/**
 * One stack of cards, as the Stats page needs to know it.
 *
 * Matt: "the stats page is so barren and not useful. i want TONS of
 * data and i want to be able to filter on like colors and types". The
 * page had eight totals and nothing else. Every chart it draws now is
 * arithmetic over these rows, in the shared core, so the phone and the
 * website cannot disagree about a collection's curve.
 *
 * One row per printing and finish, the way `cards` holds them, so a
 * foil and a nonfoil of one printing are two stacks with two prices.
 */
data class StatCard(
    val name: String,
    val qty: Int,
    val foil: Boolean,
    val manaValue: Double,
    val typeLine: String,
    /** WUBRG letters, empty for colourless. */
    val identity: String,
    val rarity: String,
    val setName: String,
    /** The year the printing came out, "2024". */
    val year: String,
    val artist: String,
    /** What one copy is worth, in its own finish. Null when nobody has priced it. */
    val price: Double?,
) {
    /** Every card type on any face: a Dryad Arbor is a Land and a Creature. */
    val types: Set<String> by lazy { CollectionAnalysis.typesOf(typeLine) }

    val isLand: Boolean get() = "Land" in types
}

/**
 * A tab on the Stats page: everything, or one card type.
 *
 * Matt: "a tab for every card type would be cool, with the landing
 * page being the data on the collection as a whole".
 */
enum class StatTab(val title: String, val type: String?) {
    ALL("Everything", null),
    CREATURE("Creatures", "Creature"),
    PLANESWALKER("Planeswalkers", "Planeswalker"),
    INSTANT("Instants", "Instant"),
    SORCERY("Sorceries", "Sorcery"),
    ARTIFACT("Artifacts", "Artifact"),
    ENCHANTMENT("Enchantments", "Enchantment"),
    BATTLE("Battles", "Battle"),
    LAND("Lands", "Land"),
    ;

    fun holds(c: StatCard): Boolean = type == null || type in c.types
}

/**
 * What the page is narrowed to, on top of its tab.
 *
 * A colour keeps a card whose identity has that colour, and two
 * colours keep a card with either: the question is "show me my red
 * cards", and a Rakdos card is one. Colourless is asked for like a
 * colour and means a card with none. Rarity is the same, any of.
 */
data class StatFilter(
    val colours: Set<Pip> = emptySet(),
    val rarities: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = colours.isEmpty() && rarities.isEmpty()

    fun toggle(pip: Pip) = copy(colours = if (pip in colours) colours - pip else colours + pip)

    fun toggleRarity(r: String) = copy(rarities = if (r in rarities) rarities - r else rarities + r)

    fun keeps(c: StatCard): Boolean {
        val colourOk = colours.isEmpty() || colours.any { pip ->
            if (pip == Pip.C) c.identity.isEmpty() else pip.letter.single() in c.identity
        }
        val rarityOk = rarities.isEmpty() || c.rarity in rarities
        return colourOk && rarityOk
    }
}

/** A card in a "most valuable" list: one name, what one copy is worth, how many. */
data class PricedCard(val name: String, val setName: String, val each: Double, val qty: Int, val foil: Boolean)

/**
 * Everything the page says about the cards on its tab, under its filters.
 *
 * Every count is by copies, as the deck page's are: nine Forests are
 * nine lands. A value bar is whole dollars, so it can be a [Bar].
 */
data class CollectionBreakdown(
    val copies: Int = 0,
    val uniques: Int = 0,
    val printings: Int = 0,
    val value: Double? = null,
    val unpriced: Int = 0,
    val foils: Int = 0,
    val sets: Int = 0,
    val averageManaValue: Double = 0.0,
    val names: List<String> = emptyList(),
    val colours: List<Bar> = emptyList(),
    val identities: List<Bar> = emptyList(),
    val types: List<Bar> = emptyList(),
    val curve: List<Bar> = emptyList(),
    val rarities: List<Bar> = emptyList(),
    val tribes: List<Bar> = emptyList(),
    val creatures: Int = 0,
    val topSets: List<Bar> = emptyList(),
    val years: List<Bar> = emptyList(),
    val artists: List<Bar> = emptyList(),
    val priceBands: List<Bar> = emptyList(),
    val valueByColour: List<Bar> = emptyList(),
    val valueByRarity: List<Bar> = emptyList(),
    val priciest: List<PricedCard> = emptyList(),
) {
    val isEmpty: Boolean get() = copies == 0

    val averageManaValueText: String get() = manaValueText(averageManaValue)

    /** The identities ring's slices, numbered, as the deck page's Exactly ring. */
    val identityRing: List<RingSlice> get() = RingSlice.of(identities)

    val hasCurve: Boolean get() = curve.any { it.value > 0 }

    /** The line under the creature types chart. */
    val tribesNote: String
        get() = "Top ${tribes.size} of $creatures card${if (creatures == 1) "" else "s"} with a creature type. " +
            "A Human Warrior counts once as each."
}

object CollectionAnalysis {

    /** How many rows a top list shows. */
    const val TOP = 10

    /** The creature types list is longer: it is the one people scroll. */
    const val TOP_TRIBES = 15

    private val CARD_TYPES = StatTab.entries.mapNotNull { it.type } + listOf("Kindred", "Tribal")

    private val RARITY_ORDER = listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

    /** The card types on every face, the words before the dash. */
    fun typesOf(typeLine: String): Set<String> =
        typeLine.split("//").flatMap { face ->
            face.substringBefore("—").split(" ").map { it.trim() }.filter { it in CARD_TYPES }
        }.toSet()

    /** The creature types on every face that is a creature or kindred. */
    fun tribesOf(typeLine: String): Set<String> =
        typeLine.split("//").flatMap { face ->
            val words = face.substringBefore("—").split(" ")
            if ("Creature" in words || "Kindred" in words || "Tribal" in words) {
                face.substringAfter("—", "").split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        }.toSet()

    /** Every tab with something in it under these filters, and how many copies. The landing tab always. */
    fun tabs(cards: List<StatCard>, filter: StatFilter): List<Pair<StatTab, Int>> {
        val kept = cards.filter(filter::keeps)
        return StatTab.entries.mapNotNull { tab ->
            val n = kept.filter(tab::holds).sumOf { it.qty }
            if (n > 0 || tab == StatTab.ALL) tab to n else null
        }
    }

    fun of(cards: List<StatCard>, tab: StatTab = StatTab.ALL, filter: StatFilter = StatFilter()): CollectionBreakdown {
        val kept = cards.filter { tab.holds(it) && filter.keeps(it) }
        if (kept.isEmpty()) return CollectionBreakdown()
        val priced = kept.filter { it.price != null }
        val nonland = kept.filter { !it.isLand }
        val nonlandCopies = nonland.sumOf { it.qty }
        val tribeCounts = counted(kept) { tribesOf(it.typeLine) }
        return CollectionBreakdown(
            copies = kept.sumOf { it.qty },
            uniques = kept.map { it.name }.distinct().size,
            printings = kept.size,
            value = priced.takeIf { it.isNotEmpty() }?.sumOf { it.qty * it.price!! }?.let { cents(it) },
            unpriced = kept.filter { it.price == null }.sumOf { it.qty },
            foils = kept.filter { it.foil }.sumOf { it.qty },
            sets = kept.map { it.setName }.distinct().size,
            averageManaValue = if (nonlandCopies == 0) 0.0 else
                ((nonland.sumOf { it.manaValue * it.qty } / nonlandCopies) * 100).roundToInt() / 100.0,
            names = kept.map { it.name }.distinct(),
            colours = colours(kept) { it.qty.toDouble() },
            identities = identities(kept),
            types = (StatTab.entries.mapNotNull { it.type }).mapNotNull { t ->
                kept.filter { t in it.types }.sumOf { it.qty }.takeIf { it > 0 }?.let { Bar(t, it) }
            }.sortedByDescending { it.value },
            curve = curve(nonland),
            rarities = RARITY_ORDER.mapNotNull { r ->
                kept.filter { it.rarity == r }.sumOf { it.qty }.takeIf { it > 0 }?.let { Bar(title(r), it) }
            },
            tribes = top(tribeCounts, TOP_TRIBES),
            creatures = kept.filter { tribesOf(it.typeLine).isNotEmpty() }.sumOf { it.qty },
            topSets = top(counted(kept) { setOf(it.setName) }, TOP),
            years = counted(kept) { setOf(it.year) }.entries.filter { it.key.isNotBlank() }
                .sortedBy { it.key }.map { Bar(it.key, it.value) },
            artists = top(counted(kept) { setOf(it.artist) }, TOP),
            priceBands = priceBands(kept),
            valueByColour = colours(priced) { it.qty * it.price!! }.filter { it.value > 0 },
            valueByRarity = RARITY_ORDER.mapNotNull { r ->
                priced.filter { it.rarity == r }.sumOf { it.qty * it.price!! }.roundToInt()
                    .takeIf { it > 0 }?.let { Bar(title(r), it) }
            },
            priciest = priced.sortedWith(compareByDescending<StatCard> { it.price }.thenBy { it.name })
                .distinctBy { it.name }
                .take(TOP)
                .map { c -> PricedCard(c.name, c.setName, c.price!!, kept.filter { it.name == c.name }.sumOf { it.qty }, c.foil) },
        )
    }

    private fun cents(v: Double): Double = (v * 100).roundToInt() / 100.0

    private fun title(r: String) = r.replaceFirstChar(Char::uppercase)

    /** Copies per key, a card counted once under each key it has. */
    private fun counted(cards: List<StatCard>, keys: (StatCard) -> Set<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        cards.forEach { c -> keys(c).filter { it.isNotBlank() }.forEach { counts[it] = (counts[it] ?: 0) + c.qty } }
        return counts
    }

    /** Most first, ties alphabetical. */
    private fun top(counts: Map<String, Int>, n: Int): List<Bar> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n)
            .map { Bar(it.key, it.value) }

    /**
     * Per colour of the identity, a three-colour card counted in each,
     * colourless last. Labelled the way the deck page's rings are, so
     * a ring draws them in their own colours.
     */
    private fun colours(cards: List<StatCard>, weight: (StatCard) -> Double): List<Bar> =
        Pip.entries.mapNotNull { pip ->
            val sum = cards.filter {
                if (pip == Pip.C) it.identity.isEmpty() else pip.letter.single() in it.identity
            }.sumOf(weight)
            sum.roundToInt().takeIf { it > 0 }?.let { Bar(pip.label, it, pip.letter) }
        }

    /** By exact identity, named like a guild. Most first. */
    private fun identities(cards: List<StatCard>): List<Bar> {
        val order = "WUBRG"
        val counts = mutableMapOf<String, Int>()
        cards.forEach { c ->
            val key = order.filter { it in c.identity }.ifEmpty { Pip.C.letter }
            counts[key] = (counts[key] ?: 0) + c.qty
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (key, n) -> Bar(key, n, Guild.of(key.takeIf { it != Pip.C.letter }.orEmpty()).orEmpty()) }
    }

    private fun curve(nonland: List<StatCard>): List<Bar> {
        val top = DeckAnalysis.CURVE_TOP
        val buckets = IntArray(top + 1)
        nonland.forEach { c -> buckets[c.manaValue.toInt().coerceIn(0, top)] += c.qty }
        return buckets.mapIndexed { mv, n -> Bar(if (mv == top) "$top+" else "$mv", n, "mana value $mv") }
    }

    /** What each copy is worth, in bands a person would say out loud. */
    private fun priceBands(cards: List<StatCard>): List<Bar> {
        val bands = listOf(
            "Under \$1" to (0.0 to 1.0),
            "\$1 to \$5" to (1.0 to 5.0),
            "\$5 to \$20" to (5.0 to 20.0),
            "\$20 and up" to (20.0 to Double.MAX_VALUE),
        )
        val priced = bands.map { (label, range) ->
            Bar(label, cards.filter { it.price != null && it.price >= range.first && it.price < range.second }.sumOf { it.qty })
        }
        return priced + Bar("No price", cards.filter { it.price == null }.sumOf { it.qty })
    }
}
