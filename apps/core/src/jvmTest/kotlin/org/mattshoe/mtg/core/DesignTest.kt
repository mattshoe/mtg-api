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
}
