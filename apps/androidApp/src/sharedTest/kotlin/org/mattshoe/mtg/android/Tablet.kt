package org.mattshoe.mtg.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Tablet-sized screens, for any screen test.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The carousel shipped sized for a phone, and
 * on his folding phone opened out the card was taller than the space
 * it had, so the top and the rules text were cut off. Every screen
 * test the JVM ran was on Robolectric's 320 by 470 default, which is
 * narrower than it is tall and so could never show it.
 *
 * `ForcedSize` rather than a Robolectric qualifier, because these
 * tests also run on a device, which has no Robolectric. It hands the
 * content a screen of exactly this many dp, shrinking the density to
 * fit when the real window is smaller.
 */
object Tablet {
    /** A folding phone opened out: nearly square. The screenshot's device. */
    val UNFOLDED = DpSize(750.dp, 830.dp)

    /** A ten-inch tablet held sideways: far wider than tall. */
    val LANDSCAPE = DpSize(1280.dp, 800.dp)

    @Composable
    fun Sized(size: DpSize, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) { content() }
    }
}
