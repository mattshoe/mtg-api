package org.mattshoe.mtg.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * A test that fails the first time it runs and passes the second.
 *
 * Here and not only in `RetryTest`, because this one goes through the
 * real runners: Robolectric on the JVM and the instrumentation runner
 * on the emulator, which is where the flake that started this
 * happened. If either runner stops honouring the rule, this is red.
 */
@RunWith(AndroidJUnit4::class)
class RetryIsWiredTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @Test
    fun aTestThatFailsOnceIsRetriedAndPasses() {
        runs++
        assertTrue(runs > 1, "ran once and was not retried")
    }

    private companion object {
        var runs = 0
    }
}
