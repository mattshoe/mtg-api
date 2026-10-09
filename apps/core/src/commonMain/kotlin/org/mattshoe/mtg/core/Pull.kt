package org.mattshoe.mtg.core

/**
 * How far a page has to be dragged down before letting go refreshes it.
 *
 * The phone's `PullToRefreshBox` decides this for itself, with
 * Material's numbers: the indicator moves at half the finger's speed
 * and fires once it has travelled 80dp. The web has no such box, so it
 * asks here, and the same thumb refreshes at the same point on both.
 */
object Pull {
    /** How far the indicator moves per pixel of finger. */
    const val DRAG_RATE = 0.5

    /** How far the indicator has to travel, in dp or CSS pixels. */
    const val THRESHOLD = 80.0

    /** Where the indicator is for a finger [fingerDy] below where it started. */
    fun travel(fingerDy: Double): Double = (fingerDy * DRAG_RATE).coerceAtLeast(0.0)

    /** Whether letting go here refreshes. */
    fun fires(fingerDy: Double): Boolean = travel(fingerDy) >= THRESHOLD
}
