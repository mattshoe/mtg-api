package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette in `Design` and the palette in the stylesheet are the
 * same palette.
 *
 * Android reads `Design`; the browser reads `:root` in `app.css`,
 * because a Kotlin object cannot set a CSS variable. That is two copies
 * of the same twenty numbers, which is exactly the kind of thing that
 * drifts — so this reads the stylesheet and compares.
 *
 * JVM only: it needs the repository on disk, and the point is to fail
 * on somebody's machine or in CI rather than on a phone.
 */
class DesignTest {

    private val css: String by lazy {
        val file = File("../../frontend/css/app.css")
        assertTrue(file.exists(), "cannot find the stylesheet at ${file.absolutePath}")
        file.readText()
    }

    private fun variable(name: String): String {
        val m = Regex("--$name:\\s*(#[0-9a-fA-F]{6})").find(css)
        return m?.groupValues?.get(1)?.uppercase()
            ?: error("the stylesheet has no --$name")
    }

    private fun hex(argb: Long) = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    @Test
    fun theSurfacesMatch() {
        assertEquals(variable("bg"), hex(Design.BG))
        assertEquals(variable("bg-2"), hex(Design.BG_2))
        assertEquals(variable("bg-3"), hex(Design.BG_3))
        assertEquals(variable("bg-hover"), hex(Design.BG_HOVER))
        assertEquals(variable("line"), hex(Design.LINE))
        assertEquals(variable("line-2"), hex(Design.LINE_2))
    }

    @Test
    fun theTextColoursMatch() {
        assertEquals(variable("text"), hex(Design.TEXT))
        assertEquals(variable("text-2"), hex(Design.TEXT_2))
        assertEquals(variable("text-3"), hex(Design.TEXT_3))
    }

    @Test
    fun theAccentsAndStatesMatch() {
        assertEquals(variable("accent"), hex(Design.ACCENT))
        assertEquals(variable("accent-2"), hex(Design.ACCENT_2))
        assertEquals(variable("accent-dim"), hex(Design.ACCENT_DIM))
        assertEquals(variable("ok"), hex(Design.OK))
        assertEquals(variable("warn"), hex(Design.WARN))
        assertEquals(variable("bad"), hex(Design.BAD))
        assertEquals(variable("info"), hex(Design.INFO))
    }

    @Test
    fun theColourPipsMatch() {
        assertEquals(variable("w"), hex(Design.W))
        assertEquals(variable("u"), hex(Design.U))
        assertEquals(variable("b"), hex(Design.B))
        assertEquals(variable("r"), hex(Design.R))
        assertEquals(variable("g"), hex(Design.G))
        assertEquals(variable("c"), hex(Design.C))
    }

    @Test
    fun theRadiiMatch() {
        val radius = Regex("--radius:\\s*(\\d+)px").find(css)!!.groupValues[1].toInt()
        val small = Regex("--radius-sm:\\s*(\\d+)px").find(css)!!.groupValues[1].toInt()
        assertEquals(radius, Design.RADIUS)
        assertEquals(small, Design.RADIUS_SM)
    }

    /**
     * The thumbnail's corner is a rule and not a `:root` variable, so
     * it is read out of the rule.
     *
     * Android used `--radius` on a 40px box, which is a quarter of
     * its width off each corner. Nothing in the stylesheet said 6px
     * twice, which is how the phone came to say 10.
     */
    @Test
    fun theThumbnailRadiusMatches() {
        val rule = Regex("""\.deck-line \.thumb \{[^}]*?border-radius:\s*(\d+)px""")
            .find(css)
            ?: error("the stylesheet has no `.deck-line .thumb` border-radius")
        assertEquals(rule.groupValues[1].toInt(), Design.RADIUS_THUMB)
    }

    /**
     * `color-mix(in srgb, A P%, B)` is arithmetic the browser does
     * and Android has to do itself.
     *
     * The share is read out of the declaration that uses it, so a
     * stylesheet tweak from 45% to 50% fails here rather than leaving
     * the phone a shade off for months.
     */
    @Test
    fun theChipEdgeMixMatches() {
        val share = Regex(
            """\.chip\.ok \{[^}]*?color-mix\(in srgb, var\(--ok\) (\d+)%, var\(--line\)\)""",
        ).find(css) ?: error("the stylesheet's `.chip.ok` no longer mixes its edge over --line")
        assertEquals(share.groupValues[1].toInt() / 100f, Design.CHIP_EDGE_MIX)
    }

    @Test
    fun theCurveBarGradientMatches() {
        val gradient = Regex(
            """\.curve \.bar \{[^}]*?background: linear-gradient\(to top, """ +
                """var\(--accent\), color-mix\(in srgb, var\(--accent\) (\d+)%, """ +
                """var\(--bg-3\)\)\)""",
        ).find(css) ?: error("the stylesheet's `.curve .bar` no longer ramps accent over --bg-3")
        assertEquals(gradient.groupValues[1].toInt() / 100f, Design.CURVE_BAR_MIX)
    }

    /**
     * The mix itself, against numbers worked out by hand.
     *
     * `Design.mixSrgb` is the only thing standing between the phone
     * and a hard-coded hex, so what it computes is stated rather than
     * trusted. 55% of `--accent` over `--bg-3` is #81693E: red
     * 0.55·212 + 0.45·28 = 129, green 0.55·162 + 0.45·35 = 105, blue
     * 0.55·76 + 0.45·45 = 62.
     */
    @Test
    fun theMixIsAStraightPerChannelAverage() {
        assertEquals(
            0xFF81693EL,
            Design.mixSrgb(Design.ACCENT, Design.BG_3, Design.CURVE_BAR_MIX),
        )
        // The ends, so a share is not quietly being ignored.
        assertEquals(Design.ACCENT, Design.mixSrgb(Design.ACCENT, Design.BG_3, 1f))
        assertEquals(Design.BG_3, Design.mixSrgb(Design.ACCENT, Design.BG_3, 0f))
        // Rounded, not truncated: half of 255 and 0 is 128 to a
        // browser and 127 to integer division.
        assertEquals(0xFF808080L, Design.mixSrgb(0xFFFFFFFF, 0xFF000000, 0.5f))
    }
}
