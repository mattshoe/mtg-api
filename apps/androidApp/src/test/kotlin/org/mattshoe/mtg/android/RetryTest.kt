package org.mattshoe.mtg.android

import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Matt: "20 fucking minutes of waiting just for a flaky test. Your UI
 * tests should have 3 retries for failures."
 *
 * The rule on its own, driven with statements that fail a counted
 * number of times. Plain JUnit, no Robolectric: this is about how many
 * times a body runs, not about a screen.
 */
class RetryTest {

    private val who = Description.createTestDescription("Somewhere", "something")

    /** A body that throws [failures] times and then passes, counting every run. */
    private class Flaky(private val failures: Int, private val error: () -> Throwable) : Statement() {
        var runs = 0
        override fun evaluate() {
            runs++
            if (runs <= failures) throw error()
        }
    }

    @Test
    fun aTestThatFailsThreeTimesAndThenPassesIsGreen() {
        val body = Flaky(3) { AssertionError("flaked") }
        Retry().apply(body, who).evaluate()
        assertEquals(4, body.runs, "three retries means four runs in all")
    }

    @Test
    fun aTestThatPassesFirstTimeRunsOnce() {
        val body = Flaky(0) { AssertionError("never") }
        Retry().apply(body, who).evaluate()
        assertEquals(1, body.runs, "a green test was run again")
    }

    @Test
    fun aTestThatFailsEveryTimeIsRedAfterFourRunsAndSaysSo() {
        val body = Flaky(Int.MAX_VALUE) { AssertionError("really broken") }
        val thrown = assertFailsWith<AssertionError> { Retry().apply(body, who).evaluate() }
        assertEquals(4, body.runs, "gave up after the wrong number of runs")
        assertTrue(
            thrown.message.orEmpty().contains("failed 4 times") &&
                thrown.message.orEmpty().contains("really broken"),
            "the failure should say it was retried and still carry the cause: ${thrown.message}",
        )
    }

    @Test
    fun aSkippedTestIsNotRetried() {
        val body = Flaky(Int.MAX_VALUE) { AssumptionViolatedException("needs a device") }
        assertFailsWith<AssumptionViolatedException> { Retry().apply(body, who).evaluate() }
        assertEquals(1, body.runs, "a skip is not a failure and was run again")
    }
}
