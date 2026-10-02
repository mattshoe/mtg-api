package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Where each screen was left.
 *
 * The whole app is one document at one address with a changing hash,
 * so the browser has no navigation to restore a scroll offset for and
 * simply leaves the page where it was. Opening a deck from halfway
 * down the list opened the deck halfway down.
 *
 * Somewhere new starts at the top. Coming back goes back to where you
 * were — and because the list arrives from the network a moment after
 * the route does, the offset is reapplied for a few frames rather than
 * set once into a page that is still empty.
 */
object Scroll {

    private val left = mutableMapOf<String, Double>()

    /** Which request is current. An older chase stops when this moves. */
    private var token = 0

    /** Remember where the screen at [hash] was, before leaving it. */
    fun remember(hash: String) {
        left[hash] = window.scrollY
    }

    /** Somewhere new. */
    fun top() {
        token++
        window.scrollTo(0.0, 0.0)
    }

    /** Back to [hash], once there is enough page to hold the offset. */
    fun restore(hash: String) {
        val y = left[hash] ?: return top()
        if (y <= 0.0) return top()
        chase(++token, y, FRAMES)
    }

    /**
     * Ask for the offset again each frame until it sticks.
     *
     * A single `scrollTo` into a page whose rows have not arrived is a
     * no-op, and that arrival is a network round trip away, so one
     * attempt would silently do nothing on every screen worth coming
     * back to.
     */
    private fun chase(mine: Int, y: Double, framesLeft: Int) {
        if (framesLeft <= 0) return
        window.requestAnimationFrame {
            if (mine != token) return@requestAnimationFrame
            window.scrollTo(0.0, y)
            if (kotlin.math.abs(window.scrollY - y) >= 1.0) chase(mine, y, framesLeft - 1)
        }
    }

    /**
     * Stop chasing, because the person is scrolling.
     *
     * The chase lasts long enough to outlast a slow round trip, and
     * that is long enough to fight somebody who started scrolling the
     * moment the page appeared. Their hand wins.
     */
    fun theyTookOver() {
        token++
    }

    /**
     * The card drawer scrolls inside itself and the element survives
     * one card being swapped for the next, so a long card read to the
     * bottom left the next one opening at its own middle.
     */
    fun drawerToTop() {
        window.requestAnimationFrame {
            document.querySelector(".drawer")?.scrollTop = 0.0
        }
    }

    /**
     * How long to keep asking. Two seconds at 60Hz.
     *
     * It was half a second, which is less than a search against D1
     * from a phone — so the rows arrived after the chase had given
     * up and coming back from a card landed at the top of the list.
     * It costs nothing to keep asking: the chase stops the frame it
     * succeeds, and a hand on the screen stops it too.
     */
    private const val FRAMES = 120
}
