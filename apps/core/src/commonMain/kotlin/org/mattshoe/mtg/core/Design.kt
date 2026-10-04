package org.mattshoe.mtg.core

/**
 * The palette and the measurements, once.
 *
 * These are the `:root` custom properties in `frontend/css/app.css`,
 * moved somewhere Android can read them. The web still gets them from
 * the stylesheet — a Kotlin object cannot set a CSS variable — but the
 * numbers live here and `DesignTest` checks the stylesheet still says
 * the same thing, so the two cannot drift without a test going red.
 *
 * Colours are `0xAARRGGBB`, which is what every platform's colour type
 * takes. Sizes are in the units the CSS uses: px for radii and type,
 * which Android reads as dp and sp.
 */
object Design {

    // ------------------------------------------------------- surfaces

    const val BG = 0xFF0E1116
    const val BG_2 = 0xFF151A22
    const val BG_3 = 0xFF1C232D
    const val BG_HOVER = 0xFF222B37
    const val LINE = 0xFF262F3C
    const val LINE_2 = 0xFF344052

    // ----------------------------------------------------------- text
    //
    // Contrast against the three surfaces above, at the worst of them:
    // text 12.9:1, text-2 7.3:1, text-3 5.4:1. Nothing here is allowed
    // below WCAG AA's 4.5:1.

    const val TEXT = 0xFFE4E9F0
    const val TEXT_2 = 0xFFA4B1C2
    const val TEXT_3 = 0xFF94A1B2

    // --------------------------------------------------------- accent

    const val ACCENT = 0xFFD4A24C
    const val ACCENT_2 = 0xFFF0C070
    const val ACCENT_DIM = 0xFF3A2F18

    const val OK = 0xFF4EA672
    const val WARN = 0xFFD99B3C
    const val BAD = 0xFFD6636B
    const val INFO = 0xFF5B9BD5

    /** On an accent-filled button, which is dark text on gold. */
    const val ON_ACCENT = 0xFF17130A

    // --------------------------------------------------------- colours
    //
    // The five, plus colourless. Used for the identity pips.

    const val W = 0xFFF8F3E0
    const val U = 0xFF61A3DD
    const val B = 0xFF5C5A6B
    const val R = 0xFFC47063
    const val G = 0xFF277A42
    const val C = 0xFFBBCCDE

    fun pip(letter: String): Long = when (letter.uppercase()) {
        "W" -> W
        "U" -> U
        "B" -> B
        "R" -> R
        "G" -> G
        else -> C
    }

    // ------------------------------------------------------- mixtures
    //
    // `color-mix(in srgb, A P%, B)` appears four times in `app.css`
    // and the browser evaluates it for nothing. Android has to work
    // it out, and a hand-converted hex in the Compose source is
    // exactly the kind of copy `DesignTest` exists to stop — so the
    // shares are named here and checked against the stylesheet.

    /**
     * `color-mix(in srgb, …)`: a straight per-channel average at the
     * encoded values, which is what sRGB means and what the browser
     * does. Not a perceptual blend — Compose's own `lerp` goes
     * through Oklab and lands somewhere else.
     */
    fun mixSrgb(a: Long, b: Long, shareOfA: Float): Long {
        fun channel(shift: Int): Long {
            val left = (a shr shift) and 0xFF
            val right = (b shr shift) and 0xFF
            val mixed = left * shareOfA + right * (1f - shareOfA)
            // The browser rounds; truncating is a channel out on
            // nearly every mix.
            return (mixed + 0.5f).toLong().coerceIn(0L, 255L)
        }
        return (0xFFL shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    /**
     * `.chip.ok`, `.chip.bad` and `.chip.warn`: the edge is the tone
     * at 45% over `--line`, and the face stays the page's own.
     */
    const val CHIP_EDGE_MIX = 0.45f

    /**
     * `.curve .bar`: `linear-gradient(to top, var(--accent),
     * color-mix(in srgb, var(--accent) 55%, var(--bg-3)))`. Full
     * accent at the foot of the bar, dimmed at the head of it.
     */
    const val CURVE_BAR_MIX = 0.55f

    // ----------------------------------------------------------- shape

    const val RADIUS = 10
    const val RADIUS_SM = 7
    /** A pill: the nav tabs and the chips. */
    const val RADIUS_PILL = 99

    /**
     * `.deck-line .thumb` and `.token .thumb`: a 40px square of
     * cropped art, rounded by 6px.
     *
     * Not `--radius`. Android used the page radius, which is ten on a
     * forty-pixel box — a quarter of the thumbnail's width taken off
     * each corner, so the art read as a circle rather than a square
     * with the corners off.
     */
    const val RADIUS_THUMB = 6

    // ------------------------------------------------------------ type

    const val BODY = 14
    const val SMALL = 13
    const val MINI = 12
    const val TINY = 11
    const val H1 = 20
    const val H2 = 16
    const val H3 = 14

    // --------------------------------------------------------- spacing

    const val GAP = 8
    const val PANEL_PAD = 14
    const val WRAP_PAD = 18
    /** `.wrap` under 720px, which is every phone. */
    const val WRAP_PAD_NARROW = 12

    /** A Magic card, so the frames do not jump as the art loads. */
    const val CARD_ASPECT = 488f / 680f
}
