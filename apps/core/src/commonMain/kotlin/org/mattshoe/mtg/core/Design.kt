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

    const val W = 0xFFF5DA64
    const val U = 0xFF3FA9FF
    const val B = 0xFFB87CFF
    const val R = 0xFFFF5C33
    const val G = 0xFF2ED98F
    const val C = 0xFF9DB2CC

    fun pip(letter: String): Long = when (letter.uppercase()) {
        "W" -> W
        "U" -> U
        "B" -> B
        "R" -> R
        "G" -> G
        else -> C
    }

    // ----------------------------------------------------------- shape

    const val RADIUS = 10
    const val RADIUS_SM = 7
    /** A pill: the nav tabs and the chips. */
    const val RADIUS_PILL = 99

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
