package org.mattshoe.mtg.core

import kotlinx.serialization.SerialName
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
    /** The `color_identity` column: letters in any order, or nothing for colourless. */
    val identity: String? = null,
    /** Copies of this name that real decks hold, the same on every rarity's row. */
    val inDecks: Int = 0,
    /** The real decks holding it, by name. */
    val decks: List<String> = emptyList(),
    /** One printing of it, for the carousel's picture. */
    val scryfallId: String? = null,
    /** Scryfall's `layout`, for whether the carousel can turn it over. */
    val layout: String? = null,
)

/**
 * A colour identity, the way a card prints it.
 *
 * Matt: "UR is not 'multicolored', it's fucking UR", "every color
 * identity needs its own section", and "make sure the mana symbols are
 * in the SAME order they appear on the card". Cards print their colours
 * in a fixed order that is not WUBRG for every set — Izzet is {U}{R},
 * Boros {R}{W}, Abzan {W}{B}{G}, Temur {G}{U}{R} — and the database
 * stores the letters in its own order (`RU`, `BGW`). So all thirty-one
 * combinations are written out here, in the order a section is listed:
 * one colour, then two, three, four and five, colourless last.
 */
object ColorIdentity {

    val PRINTED: List<String> = listOf(
        "W", "U", "B", "R", "G",
        // Allies, then enemies.
        "WU", "UB", "BR", "RG", "GW",
        "WB", "UR", "BG", "RW", "GU",
        // Shards, then wedges.
        "WUB", "UBR", "BRG", "RGW", "GWU",
        "WBG", "URW", "BGU", "RWB", "GUR",
        // Each four-colour set, missing green, white, blue, black, red.
        "WUBR", "UBRG", "BRGW", "RGWU", "GWUB",
        "WUBRG",
    )

    private val byLetters: Map<Set<Char>, String> = PRINTED.associateBy { it.toSet() }

    /** `RU` into `UR`. Empty for colourless, or for anything that is not colours. */
    fun printed(letters: String?): String {
        val set = letters.orEmpty().uppercase().filter { it in "WUBRG" }.toSet()
        return byLetters[set].orEmpty()
    }

    /** Where a section with this identity is listed. Colourless is last. */
    fun rank(letters: String): Int {
        val i = PRINTED.indexOf(printed(letters))
        return if (i < 0) PRINTED.size else i
    }

    /** The symbols to draw: the letters, or `C` for colourless. */
    fun symbols(letters: String): List<String> =
        printed(letters).ifEmpty { "C" }.map { it.toString() }
}

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
    /** The decks holding it, when no copy is left in bulk. */
    val decks: List<String> = emptyList(),
    /** How the list names it, which is what Remove takes out of the text. Empty on a list saved before. */
    val key: String = "",
    val nameNorm: String = "",
    /** One printing, for the carousel's picture. */
    val scryfallId: String? = null,
    /** The `types` column, for the line under the carousel's name. */
    val types: String? = null,
    /** Scryfall's `layout`, for whether the carousel can turn it over. */
    val layout: String? = null,
) {
    val alsoCheckLine: String get() = "Also check: " + alsoCheck.joinToString(", ") { it.label }

    /** `In deck: Alela`, or null with a copy still in bulk. */
    val decksLine: String? get() = when (decks.size) {
        0 -> null
        1 -> "In deck: " + decks.single()
        else -> "In decks: " + decks.joinToString(", ")
    }
}

/**
 * One binder section: a type, a colour identity and a rarity.
 *
 * `colors` is the identity in printed order, empty for colourless.
 * `legacy` is the word a list saved before identities had in its place
 * — "MULTICOLOR" among them — and only ever read to tell such a list
 * apart. See `FetchListState.restore`.
 */
@Serializable
data class FetchSection(
    val type: FetchType,
    val colors: String = "",
    val rarity: FetchRarity,
    val cards: List<FetchCard>,
    @SerialName("color") val legacy: String? = null,
) {
    /** What the head draws between the type and the rarity. */
    val symbols: List<String> get() = ColorIdentity.symbols(colors)

    /** The section in words, for an address or a test: `Instants · UR · Uncommon`. */
    val title: String get() = "${type.label} · ${colors.ifEmpty { "C" }} · ${rarity.label}"
}

/** A card the collection does not have enough of. `short` is how many are missing. */
@Serializable
data class MissingCard(val name: String, val short: Int, val have: Int = 0, val key: String = "")

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
    /** Null for a list saved under the old colour sections. See `FetchListState.restore`. */
    val result: FetchResult?,
    /** The cards ticked off as found. Matt: "the checked boxes should Persist". */
    val ticked: Set<String> = emptySet(),
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

        data class Filed(val type: FetchType, val colors: String, val rarity: FetchRarity, val card: FetchCard)

        val filed = mutableListOf<Filed>()
        val missing = mutableListOf<MissingCard>()
        entries.forEach { e ->
            val rows = byName[e.key] ?: byFace[e.key]
            if (rows.isNullOrEmpty()) {
                missing += MissingCard(e.name, e.qty, key = e.key)
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
            // Bulk is what no real deck holds — `card_usage.free`. With
            // none left the card is in a deck, so say which.
            val decks = if (have - first.inDecks <= 0) first.decks else emptyList()
            filed += Filed(
                FetchType.of(first.types),
                ColorIdentity.printed(first.identity),
                primary,
                FetchCard(
                    first.name, e.qty, have, also, decks,
                    key = e.key,
                    nameNorm = first.nameNorm,
                    scryfallId = rows.firstNotNullOfOrNull { it.scryfallId },
                    types = first.types,
                    layout = rows.firstNotNullOfOrNull { it.layout },
                ),
            )
            if (have < e.qty) missing += MissingCard(first.name, e.qty - have, have, e.key)
        }

        val sections = filed
            .groupBy { Triple(it.type, it.colors, it.rarity) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<Triple<FetchType, String, FetchRarity>, List<Filed>>> { it.key.first.ordinal }
                    .thenBy { ColorIdentity.rank(it.key.second) }
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
     * `in_decks` and `decks` are the copies real decks hold and their
     * names, by the rule `card_usage` counts by: not a proxy, not
     * PROPOSED, and a slot backed by a card the owner holds. They are
     * per name, so every rarity's row carries the same.
     *
     * Null with no collection to look in. An empty owner is never
     * every owner — see `Owners`.
     */
    fun query(keys: List<String>, owner: String): Sql? {
        if (owner.isBlank()) return null
        val names = keys.distinct()
        val json = Json.encodeToString(ListSerializer(String.serializer()), names)
        return Sql(
            """SELECT c.name_norm, c.name, c.face1, c.types, c.colors, c.color_identity, c.rarity, SUM(c.qty) AS qty,
                      MIN(c.scryfall_id) AS scryfall_id, MIN(c.layout) AS layout,
                      (SELECT COALESCE(SUM(dc.qty), 0) FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
                        WHERE $REAL_DECK) AS in_decks,
                      (SELECT json_group_array(name) FROM (
                         SELECT DISTINCT d.name FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
                          WHERE $REAL_DECK ORDER BY d.name)) AS decks
                 FROM cards c
                WHERE ${Owners.owns("c.owner_id")}
                  AND (c.name_norm IN (SELECT value FROM json_each(?2))
                       OR lower(c.face1) IN (SELECT value FROM json_each(?2)))
                GROUP BY c.name_norm, c.rarity""",
            listOf(owner, json),
        )
    }

    /** A deck slot that holds one of this owner's real copies of `c`. */
    private const val REAL_DECK =
        "dc.name_norm = c.name_norm AND d.owner_id = c.owner_id AND d.is_proxy = 0 " +
            "AND (d.status IS NULL OR d.status NOT LIKE 'PROPOSED%') AND dc.in_collection = 1"

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
                identity = str("color_identity"),
                inDecks = str("in_decks")?.toDoubleOrNull()?.toInt() ?: 0,
                decks = str("decks")?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
                    .orEmpty(),
                scryfallId = str("scryfall_id"),
                layout = str("layout"),
            )
        }
    }
}

/**
 * The Fetch screen: the list being typed, its answer, and the lists
 * kept.
 *
 * Saved lists are the account's, kept by the Worker so every device
 * sees the same ones, and a copy is kept on the device in `Store` for
 * a start with no network. Matt: "making a fetch list on web does not
 * show on android and vice versa". See [adopted]. And: "it's very important that a pull list can be saved
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
    /** The filing is on screen and Scryfall's Also check has not come back yet. */
    val checking: Boolean = false,
    /** What is typed in the root's box. `list` is the one on the list page. */
    val draft: String = "",
    /** Names ticked off as found on the list page. */
    val ticked: Set<String> = emptySet(),
    /** The saved list on the list page, or null for one not saved yet. */
    val openId: String? = null,
    /** The list text while Edit is open, or null. */
    val editing: String? = null,
    /**
     * The account's lists have come back from the Worker, so a change
     * to [saved] is sent there. False until then, so a device's stale
     * copy is never written over the account's before it has been read.
     */
    val synced: Boolean = false,
) {
    fun drafted(text: String) = copy(draft = text, error = null)

    /** Find from the root's box: a new list, so no ticks and no title. */
    fun started() = copy(
        list = draft, draft = "", title = "", result = null, ticked = emptySet(),
        openId = null, editing = null, error = null, checking = false,
    )

    /**
     * Find from Edit: the edited text is the list now. Ticks stay —
     * Matt: "editing should not remove existing check boxes".
     */
    fun refinding() = copy(list = editing ?: list, editing = null, error = null)

    fun tick(name: String) = copy(ticked = if (name in ticked) ticked - name else ticked + name).synced()

    fun isTicked(name: String) = name in ticked

    /** `1 of 4 found`, against the cards filed. One not owned cannot be found. */
    val progress: String get() {
        val names = result?.sections?.flatMap { s -> s.cards.map { it.name } } ?: return ""
        return "${names.count { it in ticked }} of ${names.size} found"
    }

    /**
     * A card off the list: out of the text Edit opens and Find reads,
     * out of the filing, the missing and the ticks. Matt: "make sure
     * doing so actually edits the deck list text as well".
     */
    fun remove(card: FetchCard): FetchListState = removing(card.name, setOf(card.key, card.nameNorm))

    fun remove(card: MissingCard): FetchListState = removing(card.name, setOf(card.key))

    private fun removing(name: String, keys: Set<String>): FetchListState {
        val r = result ?: return this
        val gone = (keys + name.lowercase()).filter { it.isNotEmpty() }.toSet()
        return copy(
            list = DeckList.without(list, gone),
            result = r.copy(
                sections = r.sections
                    .map { s -> s.copy(cards = s.cards.filter { it.name != name }) }
                    .filter { it.cards.isNotEmpty() },
                missing = r.missing.filter { it.name != name },
            ),
            ticked = ticked - name,
        ).synced()
    }

    fun edit() = copy(editing = list)
    fun editTyped(text: String) = if (editing == null) this else copy(editing = text)
    fun cancelEdit() = copy(editing = null)

    /** The list on its page, as kept across a restart. Null with nothing open. */
    val current: SavedFetch? get() =
        if (list.isBlank()) null else SavedFetch(openId.orEmpty(), title, "", list, result, ticked)

    val canStart: Boolean get() = !busy && DeckList.entries(draft).isNotEmpty()

    /** A list found and not saved, which the root offers to reopen. */
    val unsaved: Boolean get() = list.isNotBlank() && openId == null

    /** The open saved list brought up to date with the page. */
    private fun synced(): FetchListState {
        val id = openId ?: return this
        return copy(saved = saved.map { if (it.id == id) it.copy(list = list, result = result, ticked = ticked) else it })
    }

    /** The cards on the list, the way `DeckList` reads any list. */
    val entries: List<DeckList.Entry> get() = DeckList.entries(list)

    val canFind: Boolean get() = !busy && entries.isNotEmpty()

    fun typed(text: String) = copy(list = text, error = null)
    fun titled(t: String) = copy(title = t, error = null)
    fun loading() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun found(r: FetchResult) = copy(result = r, busy = false, error = null, checking = false).synced()

    /**
     * The collection has answered: the filing goes on screen and Find
     * is free again, while the Also check is still coming.
     *
     * Matt: "it takes several full minutes to run". The minutes were
     * Scryfall's, and the list used to wait for them. `list` is the
     * list that was asked about, so a list cleared or retyped since
     * does not get somebody else's answer.
     */
    fun filed(list: String, r: FetchResult) =
        if (list != this.list) this else copy(result = r, busy = false, error = null, checking = true).synced()

    /** The same list with the Also check filled in. Dropped if the list has changed since. */
    fun checked(list: String, r: FetchResult) =
        if (list != this.list) copy(checking = false) else copy(result = r, busy = false, checking = false).synced()

    /** A fresh list, keeping the saved ones. */
    fun cleared() = FetchListState(saved = saved, synced = synced)

    /**
     * The account's saved lists, back from the Worker.
     *
     * They are the lists from now on. `merge` is the first time any
     * account syncs on this device: lists saved here before then were
     * only ever here, so they are kept after the account's rather than
     * lost. The list open on the page follows its saved copy — ticks
     * made on the other device show — unless Edit is open; one deleted
     * elsewhere stays on the page, unsaved.
     */
    fun adopted(remote: List<SavedFetch>, merge: Boolean): FetchListState {
        val theirs = remote.map(::unfiled)
        val ids = theirs.map { it.id }.toSet()
        val all = if (merge) theirs + saved.filter { it.id !in ids } else theirs
        val open = openId?.let { id -> all.firstOrNull { it.id == id } }
        val base = copy(saved = all, synced = true, openId = open?.id)
        return if (open == null || editing != null) base
        else base.copy(list = open.list, title = open.title, result = open.result, ticked = open.ticked)
    }

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
        val entry = SavedFetch(id, t, now, list, r, ticked)
        return copy(title = t, error = null, openId = id, saved = listOf(entry) + saved.filter { it.id != id })
    }

    /** A saved list, back on screen as it was, ticks and all. */
    fun open(id: String): FetchListState {
        val s = saved.firstOrNull { it.id == id } ?: return this
        return copy(
            list = s.list, title = s.title, result = s.result, ticked = s.ticked, openId = id,
            editing = null, busy = false, error = null, checking = false,
        )
    }

    /** Deleting the list on the page leaves it there, unsaved. */
    fun delete(id: String) = copy(saved = saved.filter { it.id != id }, openId = openId.takeIf { it != id })

    companion object {
        const val KEY = "mtg.fetch"

        /** Said under Find while the filing is on screen and Scryfall is still being asked. */
        const val CHECKING = "Checking Scryfall for other rarities…"

        private val json = Json { ignoreUnknownKeys = true }

        /** Anything unreadable is no saved lists rather than a crash on boot. */
        fun restore(store: Store): FetchListState = try {
            reopen(store, FetchListState(
                saved = store.get(KEY)?.let { json.decodeFromString(ListSerializer(SavedFetch.serializer()), it) }
                    ?.map(::unfiled)
                    ?: emptyList(),
            ))
        } catch (e: Exception) {
            FetchListState()
        }

        /** The list that was on its page, back as it was. Unreadable is nothing open. */
        private fun reopen(store: Store, s: FetchListState): FetchListState = try {
            val c = store.get(OPEN)?.let { json.decodeFromString(SavedFetch.serializer(), it) }
            if (c == null) s
            else s.copy(
                list = c.list, title = c.title, ticked = c.ticked,
                result = c.result?.takeIf { r -> r.sections.none { it.legacy != null } },
                openId = c.id.takeIf { id -> s.saved.any { it.id == id } },
            )
        } catch (e: Exception) {
            s
        }

        /**
         * Filed by colour words, Multicolor among them, before
         * identities had their own sections. The list stays; the old
         * filing is not shown as if it were right, and Find files it
         * again.
         */
        private fun unfiled(s: SavedFetch): SavedFetch =
            if (s.result?.sections?.any { it.legacy != null } == true) s.copy(result = null) else s

        /** Where the list on its page is kept, saved or not. */
        const val OPEN = "mtg.fetch.open"

        /** The account that last synced its lists on this device. */
        const val SYNCED = "mtg.fetch.synced"

        /**
         * Whether no account has synced on this device yet, so the
         * lists on it were saved here and nowhere else. Only then are
         * they merged in: once any account has synced, a second one
         * signing in must not have the first one's lists made theirs.
         */
        fun firstSync(store: Store): Boolean = store.get(SYNCED) == null

        fun syncedAs(store: Store, account: String) = store.put(SYNCED, account)

        const val NOT_LOADED = "Your saved Fetch lists did not load: "
        const val NOT_SAVED = "Your Fetch lists did not save to your account: "

        /** `#/fetch/list`: the list's own page. `#/fetch` is the entry box. */
        const val PAGE = "list"
        const val UNSAVED = "Unsaved list"
        const val NOTHING_OPEN = "No list open. Paste one into Fetch and press Find cards."

        /** Said under the loading view. */
        fun finding(cards: Int) = "Finding " + (if (cards == 1) "1 card" else "$cards cards") + " in your binders…"

        fun keep(store: Store, current: SavedFetch?) {
            if (current == null) store.remove(OPEN)
            else store.put(OPEN, json.encodeToString(SavedFetch.serializer(), current))
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

/** The account's lists could not be read. The ones on this device stay, and the Fetch page asks again. */
fun AppState.fetchListsFailedToLoad(why: String): AppState = say(FetchListState.NOT_LOADED + why, failed = true)

/** A change did not reach the Worker. It is still on this device, and the next change sends them all again. */
fun AppState.fetchListsFailedToSave(why: String): AppState = say(FetchListState.NOT_SAVED + why, failed = true)

/** `#/fetch/list` rather than the entry box at `#/fetch`. */
val AppState.onFetchList: Boolean get() = view == View.FETCH && route.rest == FetchListState.PAGE

/**
 * Find pressed: onto the list's own page with the list to look for.
 *
 * From the root's box that is a new list. From Edit it is the edited
 * text with the ticks kept. Anywhere else — Try again — it is the
 * list already there.
 */
fun AppState.findingFetch(): AppState {
    val f = when {
        fetch.editing != null -> fetch.refinding()
        !onFetchList && fetch.draft.isNotBlank() -> fetch.started()
        else -> fetch.copy(error = null)
    }
    val s = if (onFetchList) this else navigate(Route(View.FETCH, FetchListState.PAGE))
    return s.copy(fetch = f)
}

/** A saved list on its page, or with an empty id the unsaved one. */
fun AppState.openFetch(id: String): AppState {
    val f = if (id.isEmpty()) fetch else fetch.open(id)
    return navigate(Route(View.FETCH, FetchListState.PAGE)).copy(fetch = f)
}

/** Back to the entry box and the saved lists. An open Edit is dropped. */
fun AppState.leaveFetchList(): AppState = navigate(Route(View.FETCH)).copy(fetch = fetch.cancelEdit())

/** Every card filed on the list page, top to bottom: what the carousel runs over. */
val FetchListState.pageOrder: List<FetchCard> get() = result?.sections?.flatMap { it.cards }.orEmpty()

/**
 * A row of the list tapped outside its box: the carousel over the
 * list, at that card. Matt: "tapping the row (outside checkbox) should
 * show the carousel".
 */
fun AppState.peekFetch(card: FetchCard): AppState =
    peekAt(fetch.pageOrder.indexOfFirst { it.name == card.name }, PeekOf.FETCH)

/**
 * Remove on the carousel's sheet: the card on show off the list, the
 * same remove as the page's row. Matt: "i should keep my place in the
 * carousel, just moved forward one card to the next (unless i remove
 * the last obviously)". The same position is the next card now; past
 * the end it is the one before. Nothing left shuts the carousel.
 */
fun AppState.peekRemove(): AppState {
    if (peek.of != PeekOf.FETCH || !peek.open) return this
    val card = fetch.pageOrder.getOrNull(peek.at) ?: return this
    val left = copy(fetch = fetch.remove(card))
    val last = left.fetch.pageOrder.lastIndex
    if (last < 0) return left.closing(Overlay.CARD_PEEK)
    return left.copy(peek = peek.copy(at = peek.at.coerceAtMost(last), zoom = CardZoom()))
}

/**
 * The Found box on the carousel's sheet. The page's own tick, so the
 * count on one is the count on the other.
 */
fun AppState.peekTick(): AppState {
    if (peek.of != PeekOf.FETCH) return this
    val name = peeked?.title ?: return this
    return copy(fetch = fetch.tick(name))
}
