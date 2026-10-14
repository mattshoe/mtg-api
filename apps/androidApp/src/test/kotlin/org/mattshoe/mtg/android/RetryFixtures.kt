package org.mattshoe.mtg.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Test classes for `RetryingRunnerTest` to run through the real runner.
 *
 * Gradle finds these too and runs them like any other class, so each
 * one does nothing unless `RetryingRunnerTest` has switched them on —
 * through a system property, because Robolectric loads them again in
 * its own class loader and an ordinary flag would be a different copy
 * there. What they did is counted the same way.
 */
internal object RetryFixtures {
    private const val DRIVE = "mtg.retry.fixtures"
    private const val RUNS = "mtg.retry.fixtures.runs"
    private const val INSTANCES = "mtg.retry.fixtures.instances"

    val driven: Boolean get() = System.getProperty(DRIVE) == "on"

    fun switchOn() {
        System.getProperties().remove(RUNS)
        System.getProperties().remove(INSTANCES)
        System.setProperty(DRIVE, "on")
    }

    fun switchOff() {
        System.clearProperty(DRIVE)
    }

    /** Counts one attempt by [instance] and says which attempt it was. */
    fun ran(instance: Any): Int {
        val props = System.getProperties()
        props[INSTANCES] = instances() + System.identityHashCode(instance)
        val run = runs() + 1
        props[RUNS] = run
        return run
    }

    fun runs(): Int = System.getProperties()[RUNS] as Int? ?: 0

    @Suppress("UNCHECKED_CAST")
    fun instances(): List<Int> = System.getProperties()[INSTANCES] as List<Int>? ?: emptyList()
}

/** Fails its first attempt and passes after, holding a real compose rule as every UI test does. */
@RunWith(AndroidJUnit4::class)
class FlakyComposeFixture {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failsTheFirstAttemptOnly() {
        if (!RetryFixtures.driven) return
        compose.setContent { Text("attempt") }
        assertTrue(RetryFixtures.ran(this) > 1, "the first attempt fails on purpose")
    }
}

@RunWith(AndroidJUnit4::class)
class AlwaysFailsFixture {
    @Test
    fun failsEveryAttempt() {
        if (!RetryFixtures.driven) return
        fail("really broken, attempt ${RetryFixtures.ran(this)}")
    }
}

@RunWith(AndroidJUnit4::class)
class SkippedFixture {
    @Test
    fun isSkipped() {
        if (!RetryFixtures.driven) return
        RetryFixtures.ran(this)
        assumeTrue("needs a device", false)
    }
}
