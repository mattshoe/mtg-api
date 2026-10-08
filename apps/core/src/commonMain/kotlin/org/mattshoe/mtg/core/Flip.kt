package org.mattshoe.mtg.core

/**
 * Which cards have a picture on the back.
 *
 * Matt: "[All] double faced cards should have a toggle layover on the
 * image to flip it". Only these four layouts are two images; a split,
 * an adventure and a Kamigawa flip card are one picture with two names
 * printed on it, and turning them over would show nothing.
 *
 * One list for the toggle and for `is:dfc` in the query box, so the
 * search and the button cannot disagree about which cards are
 * double-faced.
 */
object Flip {
    val LAYOUTS: List<String> = listOf("transform", "modal_dfc", "reversible_card", "double_faced_token")

    fun flips(layout: String?): Boolean = layout in LAYOUTS
}
