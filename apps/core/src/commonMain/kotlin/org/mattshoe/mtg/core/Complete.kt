package org.mattshoe.mtg.core

/**
 * Card-name autocomplete, as state rather than as DOM.
 *
 * A port of the bookkeeping in `complete.js`: what was typed, what came
 * back, which suggestion is highlighted, and when it is worth asking
 * again. The fetch itself is `Scryfall.complete` in `:core-net`; this
 * decides whether to make it and what to do with the answer, so the
 * keyboard behaviour is identical on a phone and in a browser.
 */
data class Completion(
    val term: String = "",
    val items: List<String> = emptyList(),
    /** -1 is "nothing highlighted", which is where every new list starts. */
    val active: Int = -1,
    val open: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    val highlighted: String? get() = items.getOrNull(active)

    /** Worth a request. Two characters, the same as Scryfall asks for. */
    val worthAsking: Boolean get() = term.trim().length >= MIN

    fun typed(text: String): Completion =
        if (text.trim().length < MIN) Completion(term = text)
        else copy(term = text)

    fun suggested(names: List<String>, limit: Int = LIMIT) = copy(
        items = names.take(limit),
        active = -1,
        open = names.isNotEmpty(),
    )

    fun closed() = copy(open = false, active = -1)

    /**
     * Wraps at both ends, the way every list of this kind does.
     *
     * Up from nothing highlighted lands on the last suggestion rather
     * than the second: the original arithmetic wrapped through -1 and
     * picked the wrong end, which nobody noticed because nobody presses
     * up first on purpose.
     */
    fun down() = if (items.isEmpty()) this else copy(active = (active + 1).mod(items.size))
    fun up() = when {
        items.isEmpty() -> this
        active <= 0 -> copy(active = items.size - 1)
        else -> copy(active = active - 1)
    }

    fun highlight(i: Int) = if (i in items.indices) copy(active = i) else this

    /** Picking closes the list and puts the name in the box. */
    fun pick(i: Int): Pair<Completion, String?> {
        val name = items.getOrNull(i) ?: return this to null
        return Completion(term = name) to name
    }

    companion object {
        const val MIN = 2
        const val LIMIT = 10

        /** Scryfall asks for a gap between calls; a person types faster. */
        const val DEBOUNCE_MS = 180
    }
}
