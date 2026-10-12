package org.mattshoe.mtg.core

/** One tile on the Guide's front page. Its slug is its address, `#/guide/<slug>`. */
data class GuideTile(val slug: String, val title: String, val blurb: String)

/**
 * A named colour combination.
 *
 * `pips` in the order the cards themselves print them — Gruul is RG,
 * Naya is RGW — which is not always WUBRG order. `Guild` sorts, so
 * the two still agree on the name.
 */
data class Combo(val pips: String, val name: String, val note: String = "")

/** A run of combinations of one size, with a line saying what they have in common. */
data class ComboGroup(val title: String, val about: String, val combos: List<Combo>)

object Guide {
    const val COLOURS = "colours"

    val tiles: List<GuideTile> = emptyList()

    val colours: List<ComboGroup> = emptyList()

    fun tile(slug: String): GuideTile? = null
}

/** The tile on screen, or null on the Guide's front page and everywhere else. */
val AppState.guideTile: GuideTile? get() = null

fun AppState.openGuide(tile: GuideTile): AppState = this
