package org.mattshoe.mtg.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * A Compose test that fails the first time it runs and passes the
 * second.
 *
 * Here and not only in `RetryTest`, because this one goes through the
 * real runners: Robolectric on the JVM and the instrumentation runner
 * on the emulator, which is where the flake that started this
 * happened. If either runner stops retrying, this is red.
 *
 * It holds a real `createComposeRule()` because that is what every UI
 * test here has, and it is what the old outermost `Retry` rule could
 * never retry: the second attempt reused the same compose rule and
 * Compose refused with "Only a single call to `runTest` can be
 * performed". The retry only works if each attempt is a fresh test
 * instance with fresh rules, and this checks both.
 */
@RunWith(AndroidJUnit4::class)
class RetryIsWiredTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val compose = createComposeRule()

    private val me = Any()

    @Test
    fun aComposeTestThatFailsOnceIsRetriedOnAFreshInstanceAndPasses() {
        compose.setContent { Text("attempt") }
        compose.onNodeWithText("attempt").assertExists()
        instances += me
        assertTrue(instances.size > 1, "ran once and was not retried")
        assertTrue(
            instances.toSet().size == instances.size,
            "the retry reused the test instance, so its rules were not rebuilt",
        )
    }

    private companion object {
        val instances = mutableListOf<Any>()
    }
}
