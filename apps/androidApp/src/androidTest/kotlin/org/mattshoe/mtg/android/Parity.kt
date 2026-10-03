package org.mattshoe.mtg.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The shared kit for proving the phone does what the website does.
 *
 * Two apps, one `:core`, and a long history of the two drifting in
 * the half that `:core` does not cover: the phone had its own rulings
 * loop and disagreed with the web page about the same card; the
 * "Applied" title was wrong on Android after it was fixed on the web;
 * the launcher icon was the Android default for months.
 *
 * Reading both implementations and concluding they match is how that
 * drift survived. These tests look at the running app instead.
 */
object Parity {

    /**
     * Where screenshots land, to be pulled off the device.
     *
     * The app's own external files directory, so no permission is
     * needed and `adb pull` can reach it.
     */
    val shots: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "parity").apply { mkdirs() }
    }

    /**
     * A PNG of whatever this node is, named for the check that took
     * it.
     *
     * Evidence, not an assertion: a screenshot cannot fail a build,
     * and a test that only takes one proves nothing. Every call here
     * sits beside assertions that do the proving — the picture is for
     * the person deciding whether "the same" really looks the same.
     */
    fun SemanticsNodeInteraction.shoot(name: String): File {
        val file = File(shots, "$name.png")
        val bitmap = captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * What the web page says about itself, as facts to check here.
     *
     * A parity fact is written once and asserted on both platforms.
     * Where the web suite already states one — "neither owner is
     * preselected", "nothing is offered twice while it is being
     * written" — the Android test asserts the same sentence rather
     * than something adjacent that happens to pass.
     */
    data class Fact(val what: String, val holds: () -> Boolean)

    /** Every fact, checked, with the failures named together. */
    fun check(vararg facts: Fact) {
        val broken = facts.filterNot { runCatching { it.holds() }.getOrDefault(false) }
        if (broken.isNotEmpty()) {
            throw AssertionError(
                "the phone does not match the website on:\n  " +
                    broken.joinToString("\n  ") { it.what },
            )
        }
    }
}
