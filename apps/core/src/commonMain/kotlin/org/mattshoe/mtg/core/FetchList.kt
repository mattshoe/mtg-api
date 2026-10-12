package org.mattshoe.mtg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A deck list in, where to find every card out.
 *
 * Matt: "my cards are all sorted in order of: 1. Type 2. Color 3.
 * Rarity", and the answer is "clearly labeled sections for each
 * combination of the 3 sort criteria so that i can quickly pick through
 * each section for all the cards i need". He asked for "a 'pull list'
 * or something. maybe something more catchy" — a fetch is what a
 * fetchland does to a card in your library, and it is one short word
 * under an icon.
 *
 * Both shells read this. The query, the filing, the Also check and the
 * saved lists are decided here once.
 */

/**
 * Which binder a card lives in by type.
 *
 * One type per card, the way a sorted binder has one place for it. A
 * land is a land whatever else it is — Dryad Arbor is filed with the
 * lands. Otherwise a creature is a creature even when it is also an
 * artifact or an enchantment, because that is how it is played and
 * how it is looked for.
 */
enum class FetchType(val label: String) {
    CREATURE("Creatures"),
    PLANESWALKER("Planeswalkers"),
    BATTLE("Battles"),
    INSTANT("Instants"),
    SORCERY("Sorceries"),
    ARTIFACT("Artifacts"),
    ENCHANTMENT("Enchantments"),
    LAND("Lands"),
    OTHER("Other"),
    ;

    companion object {
        /** From the `types` column: `Artifact Creature`, `Land`, and a double-faced card's front. */
        fun of(types: String?): FetchType {
            val words = types.orEmpty().substringBefore("//").split(' ').map { it.trim() }.toSet()
            return when {
                "Land" in words -> LAND
                "Creature" in words -> CREATURE
                "Planeswalker" in words -> PLANESWALKER
                "Battle" in words -> BATTLE
                "Instant" in words -> INSTANT
                "Sorcery" in words -> SORCERY
                "Artifact" in words -> ARTIFACT
                "Enchantment" in words -> ENCHANTMENT
                else -> OTHER
            }
        }
    }
}

/** One colour, several, or none — WUBRG, then multicolour, then colourless. */
enum class FetchColor(val label: String) {
    WHITE("White"),
    BLUE("Blue"),
    BLACK("Black"),
    RED("Red"),
    GREEN("Green"),
    MULTICOLOR("Multicolor"),
    COLORLESS("Colorless"),
    ;

    companion object {
        /** From the `colors` column, which is letters — `U`, `BG` — or nothing. */
        fun of(colors: String?): FetchColor {
            val c = colors.orEmpty().uppercase().filter { it in "WUBRG" }.toSet()
            return when {
                c.isEmpty() -> COLORLESS
                c.size > 1 -> MULTICOLOR
                else -> when (c.single()) {
                    'W' -> WHITE
                    'U' -> BLUE
                    'B' -> BLACK
                    'R' -> RED
                    else -> GREEN
                }
            }
        }
    }
}

enum class FetchRarity(val slug: String, val label: String) {
    COMMON("common", "Common"),
    UNCOMMON("uncommon", "Uncommon"),
    RARE("rare", "Rare"),
    MYTHIC("mythic", "Mythic"),
    SPECIAL("special", "Special"),
    BONUS("bonus", "Bonus"),
    UNKNOWN("", "Unknown rarity"),
    ;

    companion object {
        fun of(rarity: String?): FetchRarity =
            entries.firstOrNull { it.slug.isNotEmpty() && it.slug == rarity?.trim()?.lowercase() } ?: UNKNOWN
    }
}

/** One owned printing's worth of rows, as the query hands it back. */
data class OwnedPrinting(
    val nameNorm: String,
    val name: String,
    /** The front face, for a double-faced card. A list names it by this. */
    val face: String?,
    val types: String?,
    val colors: String?,
    val rarity: String?,
    val qty: Int,
)

/**
 * One card to pull.
 *
 * `alsoCheck` is every other rarity it might really be filed under.
 * Matt: "sometimes the rarity i have in the database does not match the
 * rarity of the actual printing i have. so if there are multiple
 * rarities available for a given card name, you should present them as
 * an 'Also check' caveat".
 */
@Serializable
data class FetchCard(
    val name: String,
    val want: Int,
    val have: Int,
    val alsoCheck: List<FetchRarity> = emptyList(),
) {
    val alsoCheckLine: String get() = "Also check: " + alsoCheck.joinToString(", ") { it.label }
}

/** One binder section: a type, a colour and a rarity. */
@Serializable
data class FetchSection(
    val type: FetchType,
    val color: FetchColor,
    val rarity: FetchRarity,
    val cards: List<FetchCard>,
) {
    val title: String get() = "${type.label} · ${color.label} · ${rarity.label}"
}

/** A card the collection does not have enough of. `short` is how many are missing. */
@Serializable
data class MissingCard(val name: String, val short: Int, val have: Int = 0)

@Serializable
data class FetchResult(
    val sections: List<FetchSection> = emptyList(),
    val missing: List<MissingCard> = emptyList(),
)

/** A result kept under a title, with the list it came from. */
@Serializable
data class SavedFetch(
    val id: String,
    val title: String,
    /** ISO 8601, so it sorts as a string and reads the same everywhere. */
    val at: String,
    val list: String,
    val result: FetchResult,
)

object FetchList {

    /** What the screen says when there is no collection to look in. */
    const val NO_COLLECTION = "Sign in, or open somebody's collection, to see where its cards are"

    /**
     * Where each card on the list is.
     *
     * `printed` is name → every rarity the card has been printed at,
     * from Scryfall, keyed the way the list keys it. It can be empty —
     * offline, or Scryfall down — and the Also check then only knows
     * the rarities he owns.
     */
    fun build(
        entries: List<DeckList.Entry>,
        owned: List<OwnedPrinting>,
        printed: Map<String, Set<String>> = emptyMap(),
    ): FetchResult {
        val byName = owned.groupBy { it.nameNorm }
        val byFace = owned.filter { !it.face.isNullOrBlank() }.groupBy { it.face!!.lowercase() }

        data class Filed(val type: FetchType, val color: FetchColor, val rarity: FetchRarity, val card: FetchCard)

        val filed = mutableListOf<Filed>()
        val missing = mutableListOf<MissingCard>()
        entries.forEach { e ->
            val rows = byName[e.key] ?: byFace[e.key]
            if (rows.isNullOrEmpty()) {
                missing += MissingCard(e.name, e.qty)
                return@forEach
            }
            val have = rows.sumOf { it.qty }
            // The rarity with the most copies is where most of them
            // are; a tie goes to the commoner one, which is the bigger
            // binder and the likelier mistake.
            val rarities = rows.groupBy { FetchRarity.of(it.rarity) }
                .mapValues { (_, r) -> r.sumOf { it.qty } }
            val primary = rarities.entries
                .sortedWith(compareByDescending<Map.Entry<FetchRarity, Int>> { it.value }.thenBy { it.key.ordinal })
                .first().key
            val elsewhere = rarities.keys +
                (printed[e.key] ?: printed[rows.first().nameNorm]).orEmpty().map { FetchRarity.of(it) }
            val also = elsewhere.filter { it != primary && it != FetchRarity.UNKNOWN }.distinct().sortedBy { it.ordinal }
            val first = rows.first()
            filed += Filed(
                FetchType.of(first.types),
                FetchColor.of(first.colors),
                primary,
                FetchCard(first.name, e.qty, have, also),
            )
            if (have < e.qty) missing += MissingCard(first.name, e.qty - have, have)
        }

        val sections = filed
            .groupBy { Triple(it.type, it.color, it.rarity) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<Triple<FetchType, FetchColor, FetchRarity>, List<Filed>>> { it.key.first.ordinal }
                    .thenBy { it.key.second.ordinal }
                    .thenBy { it.key.third.ordinal },
            )
            .map { (k, v) -> FetchSection(k.first, k.second, k.third, v.map { it.card }.sortedBy { it.name.lowercase() }) }
        return FetchResult(sections, missing)
    }

    /**
     * Every owned printing of every card on the list, one row per name
     * and rarity.
     *
     * The names go in as one JSON array rather than a placeholder each:
     * D1 refuses more than a hundred bound parameters, and a Commander
     * deck is a hundred cards. A double-faced card is matched by its
     * front face too, because that is how a list writes it.
     *
     * Null with no collection to look in. An empty owner is never
     * every owner — see `Owners`.
     */
    fun query(keys: List<String>, owner: String): Sql? {
        if (owner.isBlank()) return null
        val names = keys.distinct()
        val json = Json.encodeToString(ListSerializer(String.serializer()), names)
        return Sql(
            """SELECT c.name_norm, c.name, c.face1, c.types, c.colors, c.rarity, SUM(c.qty) AS qty
                 FROM cards c
                WHERE ${Owners.owns("c.owner_id")}
                  AND (c.name_norm IN (SELECT value FROM json_each(?2))
                       OR lower(c.face1) IN (SELECT value FROM json_each(?2)))
                GROUP BY c.name_norm, c.rarity""",
            listOf(owner, json),
        )
    }

    fun decode(cols: List<String>, rows: List<JsonArray>): List<OwnedPrinting> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        return rows.map { row ->
            fun str(n: String): String? {
                val v = at[n]?.let { row.getOrNull(it) } ?: return null
                if (v is JsonNull) return null
                return (v as? JsonPrimitive)?.content
            }
            OwnedPrinting(
                nameNorm = str("name_norm").orEmpty(),
                name = str("name").orEmpty(),
                face = str("face1"),
                types = str("types"),
                colors = str("colors"),
                rarity = str("rarity"),
                qty = str("qty")?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }
    }
}

/**
 * The Fetch screen: the list being typed, its answer, and the lists
 * kept.
 *
 * Saved lists are kept per device, the way the entry history is —
 * see `Store`. Matt: "it's very important that a pull list can be saved
 * and referenced later", and "a title should be required in order to
 * save it".
 */
data class FetchListState(
    val list: String = "",
    val title: String = "",
    val result: FetchResult? = null,
    val saved: List<SavedFetch> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** The cards on the list, the way `DeckList` reads any list. */
    val entries: List<DeckList.Entry> get() = DeckList.entries(list)

    val canFind: Boolean get() = !busy && entries.isNotEmpty()

    fun typed(text: String) = copy(list = text, error = null)
    fun titled(t: String) = copy(title = t, error = null)
    fun loading() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun found(r: FetchResult) = copy(result = r, busy = false, error = null)

    /** A fresh list, keeping the saved ones. */
    fun cleared() = FetchListState(saved = saved)

    /**
     * Kept under a title. No title, no save — it says so instead.
     *
     * A title already used is replaced rather than listed twice, so
     * saving again after a change updates the one kept.
     */
    fun save(title: String, now: String): FetchListState {
        val t = title.trim()
        val r = result ?: return copy(error = "Find the cards first")
        if (t.isEmpty()) return copy(error = "Give it a title first")
        val id = saved.firstOrNull { it.title == t }?.id ?: "$now-${saved.size}"
        val entry = SavedFetch(id, t, now, list, r)
        return copy(title = t, error = null, saved = listOf(entry) + saved.filter { it.id != id })
    }

    /** A saved list, back on screen as it was. */
    fun open(id: String): FetchListState {
        val s = saved.firstOrNull { it.id == id } ?: return this
        return copy(list = s.list, title = s.title, result = s.result, busy = false, error = null)
    }

    fun delete(id: String) = copy(saved = saved.filter { it.id != id })

    companion object {
        const val KEY = "mtg.fetch"

        private val json = Json { ignoreUnknownKeys = true }

        /** Anything unreadable is no saved lists rather than a crash on boot. */
        fun restore(store: Store): FetchListState = try {
            FetchListState(
                saved = store.get(KEY)?.let { json.decodeFromString(ListSerializer(SavedFetch.serializer()), it) }
                    ?: emptyList(),
            )
        } catch (e: Exception) {
            FetchListState()
        }

        fun persist(store: Store, saved: List<SavedFetch>) {
            if (saved.isEmpty()) store.remove(KEY)
            else store.put(KEY, json.encodeToString(ListSerializer(SavedFetch.serializer()), saved))
        }
    }
}

/**
 * Save the list on screen under the title typed, and say so.
 *
 * On `AppState` rather than the screen's own state so the toast that
 * confirms it is the app's one toast on both platforms.
 */
fun AppState.savedFetch(now: String): AppState {
    val next = fetch.save(fetch.title, now)
    val s = copy(fetch = next)
    return if (next.error == null) s.say("Saved \"" + next.title + "\"") else s
}
