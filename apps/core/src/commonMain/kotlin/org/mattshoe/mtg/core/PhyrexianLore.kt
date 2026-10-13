package org.mattshoe.mtg.core

/** A line from the cards or the novels, and who said it where. */
data class LoreQuote(val words: String, val source: String)

/** One chapter of the story: a short name for its tab, a title, and the telling. */
data class LoreEra(val slug: String, val tab: String, val title: String, val story: List<String>, val quote: LoreQuote? = null)

object PhyrexianLore {
    val eras: List<LoreEra> = emptyList()

    fun era(slug: String): LoreEra? = null

    fun next(era: LoreEra): LoreEra? = null
}

val AppState.loreEra: LoreEra? get() = null

fun AppState.openEra(era: LoreEra): AppState = this
