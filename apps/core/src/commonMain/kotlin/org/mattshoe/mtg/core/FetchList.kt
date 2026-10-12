package org.mattshoe.mtg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

enum class FetchType(val label: String) {
    CREATURE("Creatures"), PLANESWALKER("Planeswalkers"), BATTLE("Battles"),
    INSTANT("Instants"), SORCERY("Sorceries"), ARTIFACT("Artifacts"),
    ENCHANTMENT("Enchantments"), LAND("Lands"), OTHER("Other");

    companion object {
        fun of(types: String?): FetchType = OTHER
    }
}

enum class FetchColor(val label: String) {
    WHITE("White"), BLUE("Blue"), BLACK("Black"), RED("Red"), GREEN("Green"),
    MULTICOLOR("Multicolor"), COLORLESS("Colorless");

    companion object {
        fun of(colors: String?): FetchColor = COLORLESS
    }
}

enum class FetchRarity(val label: String) {
    COMMON("Common"), UNCOMMON("Uncommon"), RARE("Rare"), MYTHIC("Mythic"),
    SPECIAL("Special"), BONUS("Bonus"), UNKNOWN("Unknown rarity");

    companion object {
        fun of(rarity: String?): FetchRarity = UNKNOWN
    }
}

data class OwnedPrinting(
    val nameNorm: String,
    val name: String,
    val face: String?,
    val types: String?,
    val colors: String?,
    val rarity: String?,
    val qty: Int,
)

@Serializable
data class FetchCard(val name: String, val want: Int, val have: Int, val alsoCheck: List<FetchRarity> = emptyList())

@Serializable
data class FetchSection(
    val type: FetchType,
    val color: FetchColor,
    val rarity: FetchRarity,
    val cards: List<FetchCard>,
) {
    val title: String get() = ""
}

@Serializable
data class MissingCard(val name: String, val short: Int, val have: Int = 0)

@Serializable
data class FetchResult(val sections: List<FetchSection> = emptyList(), val missing: List<MissingCard> = emptyList())

@Serializable
data class SavedFetch(val id: String, val title: String, val at: String, val list: String, val result: FetchResult)

object FetchList {
    fun build(
        entries: List<DeckList.Entry>,
        owned: List<OwnedPrinting>,
        printed: Map<String, Set<String>> = emptyMap(),
    ): FetchResult = FetchResult()

    fun query(keys: List<String>, owner: String): Sql? = Sql("", emptyList())

    fun decode(cols: List<String>, rows: List<JsonArray>): List<OwnedPrinting> = emptyList()
}

data class FetchListState(
    val list: String = "",
    val title: String = "",
    val result: FetchResult? = null,
    val saved: List<SavedFetch> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    fun titled(t: String) = copy(title = t)
    fun loading() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun save(title: String, now: String): FetchListState = this
    fun open(id: String): FetchListState = this
    fun delete(id: String): FetchListState = this

    companion object {
        fun persist(store: Store, saved: List<SavedFetch>) {}
        fun restore(store: Store): FetchListState = FetchListState()
    }
}
