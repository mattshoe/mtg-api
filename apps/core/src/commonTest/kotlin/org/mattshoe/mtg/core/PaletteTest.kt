package org.mattshoe.mtg.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The six colours, measured rather than admired.
 *
 * Matt is colourblind. Twice now a palette has gone out that looked
 * fine to whoever picked it and was unusable to him — first a pastel
 * set whose closest pair was 1.00 apart, meaning identical, and then
 * a saturated set that was 1.01 and neon with it. Saturation was
 * never the axis that helps. Lightness is: it is the only one that
 * survives every colour deficiency, because every simulation of one
 * preserves it.
 *
 * So this is arithmetic, not taste. Nothing here says a colour is
 * nice. It says no two of them collapse into each other.
 */
class PaletteTest {

    private val palette = mapOf(
        "W" to Design.W, "U" to Design.U, "B" to Design.B,
        "R" to Design.R, "G" to Design.G, "C" to Design.C,
    )

    /** The page these are drawn on. */
    private val background = Design.BG_2

    private fun channel(c: Double) =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(argb: Long): Double {
        val r = channel(((argb shr 16) and 0xFF) / 255.0)
        val g = channel(((argb shr 8) and 0xFF) / 255.0)
        val b = channel((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * What a colour looks like to an eye missing one kind of cone.
     *
     * The usual linear approximations. Precise enough for the only
     * question being asked: do two of these end up the same.
     */
    private fun simulate(argb: Long, kind: String): Long {
        val r = ((argb shr 16) and 0xFF).toDouble()
        val g = ((argb shr 8) and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        val m = when (kind) {
            "deuteranopia" -> arrayOf(
                doubleArrayOf(0.625, 0.375, 0.0),
                doubleArrayOf(0.700, 0.300, 0.0),
                doubleArrayOf(0.0, 0.300, 0.700),
            )
            "protanopia" -> arrayOf(
                doubleArrayOf(0.567, 0.433, 0.0),
                doubleArrayOf(0.558, 0.442, 0.0),
                doubleArrayOf(0.0, 0.242, 0.758),
            )
            else -> arrayOf(
                doubleArrayOf(0.950, 0.050, 0.0),
                doubleArrayOf(0.0, 0.433, 0.567),
                doubleArrayOf(0.0, 0.475, 0.525),
            )
        }
        fun ch(row: DoubleArray) =
            (row[0] * r + row[1] * g + row[2] * b).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (ch(m[0]) shl 16) or (ch(m[1]) shl 8) or ch(m[2])
    }

    /**
     * How far apart the closest pair is. Below this and two of the
     * six read as the same colour.
     *
     * 1.20 rather than something grander because six hues cannot all
     * be separated further than that and still be the six colours of
     * Magic. What buys the rest is that colour is never the only
     * signal: every pip carries its symbol and every chart its key.
     */
    private val floor = 1.20

    private fun closestPair(transform: (Long) -> Long): Triple<String, String, Double> {
        val keys = palette.keys.toList()
        var worst = Triple("", "", Double.MAX_VALUE)
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                val c = contrast(transform(palette[keys[i]]!!), transform(palette[keys[j]]!!))
                if (c < worst.third) worst = Triple(keys[i], keys[j], c)
            }
        }
        return worst
    }

    @Test
    fun noTwoColoursLookTheSameToAnyoneAtAll() {
        listOf<Pair<String, (Long) -> Long>>(
            "normal vision" to { it },
            "deuteranopia" to { simulate(it, "deuteranopia") },
            "protanopia" to { simulate(it, "protanopia") },
            "tritanopia" to { simulate(it, "tritanopia") },
        ).forEach { (what, transform) ->
            val (a, b, c) = closestPair(transform)
            assertTrue(
                c >= floor,
                "under $what, $a and $b are only ${(c * 100).toInt() / 100.0} apart — " +
                    "they read as the same colour",
            )
        }
    }

    @Test
    fun whiteIsTheLightestAndBlackIsTheDarkest() {
        // It is what the words mean. A palette that gets this wrong
        // is unreadable even to someone who sees every hue.
        val byLight = palette.entries.sortedByDescending { luminance(it.value) }.map { it.key }
        assertTrue(byLight.first() == "W", "the lightest colour is ${byLight.first()}, not white")
        assertTrue(byLight.last() == "B", "the darkest colour is ${byLight.last()}, not black")
    }

    @Test
    fun blackIsGreyRatherThanPurple() {
        // It was violet for a day. Black mana is not purple.
        val r = (Design.B shr 16) and 0xFF
        val g = (Design.B shr 8) and 0xFF
        val b = Design.B and 0xFF
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue(spread <= 40, "black has a hue of ${spread} — it should be near grey")
    }

    @Test
    fun noneOfThemIsNeon() {
        // The complaint the second time round. A fully saturated
        // colour on a dark page glares and helps nobody.
        palette.forEach { (name, argb) ->
            val r = ((argb shr 16) and 0xFF) / 255.0
            val g = ((argb shr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            val saturation = if (hi == 0.0) 0.0 else (hi - lo) / hi
            assertTrue(saturation <= 0.80, "$name is ${(saturation * 100).toInt()}% saturated")
        }
    }

    @Test
    fun everyOneOfThemStandsOffThePageItIsDrawnOn() {
        palette.forEach { (name, argb) ->
            val c = contrast(argb, background)
            assertTrue(c >= 2.5, "$name is only ${(c * 100).toInt() / 100.0} against the background")
        }
    }
}
