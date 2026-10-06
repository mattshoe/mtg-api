package org.mattshoe.mtg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What was entered recently, and putting it back in the box.
 *
 * A port of the `Recent` panel in `manage.js`. It exists because a big
 * paste that failed halfway is miserable to reproduce by hand, and
 * because the same ManaBox export often gets entered twice by mistake —
 * seeing it listed is the cheapest way to notice.
 *
 * Kept per device, never sent anywhere. A list of card names is not
 * interesting to a server that already has the collection.
 */
@Serializable
data class HistoryEntry(
    /** ISO 8601, so it sorts as a string and reads the same everywhere. */
    val at: String,
    val direction: String,
    val owner: String,
    val count: Int,
    val list: String,
) {
    val isAdd: Boolean get() = direction == Direction.ADD.slug

    fun asDirection(): Direction? = Direction.entries.firstOrNull { it.slug == direction }
    /**
     * Which collection it went to, as the slug it was recorded with.
     *
     * It used to resolve to one of two names. There is no list of
     * collections to resolve against any more, and a row is a record
     * of what happened rather than something to validate.
     */
    fun asOwner(): String? = owner.takeIf { it.isNotEmpty() }
}

data class EntryHistory(val entries: List<HistoryEntry> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /** What the panel shows. The rest is kept but not rendered. */
    val recent: List<HistoryEntry> get() = entries.take(SHOWN)

    fun remember(entry: HistoryEntry) = EntryHistory((listOf(entry) + entries).take(MAX))

    fun cleared() = EntryHistory()

    /**
     * Reuse: the same list, in the same direction it was used in, with
     * the wizard back on the list step.
     *
     * The owner is deliberately not restored. Whose collection this
     * lands in is the one question this app never answers for you, and a
     * remembered answer is still an answer.
     */
    fun reuse(entry: HistoryEntry): MassEntry = MassEntry(
        step = Step.LIST,
        direction = entry.asDirection(),
        list = entry.list,
    )

    companion object {
        const val MAX = 30
        const val SHOWN = 12
        const val KEY = "mtg.history"

        private val json = Json { ignoreUnknownKeys = true }

        /** Anything unreadable is no history rather than a crash on boot. */
        fun load(store: Store): EntryHistory = try {
            store.get(KEY)?.let {
                EntryHistory(json.decodeFromString(ListSerializer(HistoryEntry.serializer()), it))
            }
                ?: EntryHistory()
        } catch (e: Exception) {
            EntryHistory()
        }

        fun save(store: Store, history: EntryHistory) {
            if (history.isEmpty) store.remove(KEY)
            else store.put(KEY, json.encodeToString(ListSerializer(HistoryEntry.serializer()), history.entries))
        }

        /**
         * What actually happened, as a row.
         *
         * The collection comes from the caller now rather than off
         * the entry: the wizard stopped asking whose it is, because
         * an account owns one and the server refuses a write to any
         * other.
         */
        fun of(entry: MassEntry, now: String, owner: String) = HistoryEntry(
            at = now,
            direction = entry.direction?.slug.orEmpty(),
            owner = owner,
            count = entry.cardCount,
            list = entry.list,
        )
    }
}
