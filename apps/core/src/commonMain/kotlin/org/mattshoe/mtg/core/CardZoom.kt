package org.mattshoe.mtg.core

/**
 * How far the carousel's card is zoomed, and where.
 *
 * Matt: "pinch zoom should zoom in and stay at the specified zoom
 * tapping back should fully zoom out (as well as pinching to zoom all
 * the way out) swiping to the next card should only work while fully
 * zoomed out you should be able to drag the image around to move the
 * visible part of the image with 1 finger while zoomed in"
 *
 * [x] and [y] are how far the art is moved from where it sits, in the
 * shell's own pixels, and the [scale] is about the card's centre — the
 * order both `graphicsLayer` and a CSS `translate() scale()` draw in.
 * Both shells feed fingers in and draw what comes out, so the clamps
 * and the snap are one rule rather than two.
 */
data class CardZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {

    /** Anything past the whole card. Swiping waits for this to be false. */
    val zoomed: Boolean get() = scale > 1f

    /**
     * Two fingers moved apart by [factor], around a point [cx], [cy]
     * measured from the centre of a [width] by [height] card.
     *
     * The art under the fingers stays under them, which is what makes
     * a pinch feel like it is holding the picture.
     */
    fun pinch(factor: Float, cx: Float, cy: Float, width: Float, height: Float): CardZoom {
        if (factor.isNaN() || factor <= 0f) return this
        val next = (scale * factor).coerceIn(1f, MAX)
        val k = next / scale
        return CardZoom(next, cx - k * (cx - x), cy - k * (cy - y)).clamped(width, height)
    }

    /** One finger dragged by [dx], [dy]. Nothing to move while zoomed out. */
    fun pan(dx: Float, dy: Float, width: Float, height: Float): CardZoom =
        copy(x = x + dx, y = y + dy).clamped(width, height)

    /**
     * The fingers came off. A hair of zoom is let go of entirely,
     * because it would still lock the swipe while looking like none.
     */
    fun settled(): CardZoom = if (scale < SNAP) CardZoom() else this

    /** The art's edge never comes inside the card's. */
    private fun clamped(width: Float, height: Float): CardZoom {
        if (scale <= 1f + 1e-3f) return CardZoom()
        val mx = (scale - 1f) * width / 2f
        val my = (scale - 1f) * height / 2f
        return copy(x = x.coerceIn(-mx, mx), y = y.coerceIn(-my, my))
    }

    companion object {
        /** Four times: a card's rules text at that size is a headline. */
        const val MAX = 4f

        /** Under this when the fingers lift is fully out. */
        const val SNAP = 1.05f
    }
}
