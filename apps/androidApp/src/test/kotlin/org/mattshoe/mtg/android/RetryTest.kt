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
 * [Retry.attempts] on its own, driven with statements that fail a
 * counted number of times. Plain JUnit, no Robolectric: this is about
 * how many times a body runs and what is reported, not about a screen.
 * `RetryingRunnerTest` drives the real runner.
 */
class RetryTest {

    private val who = Description.createTestDescription("Somewhere", "something")

    /** A body that throws [failures] times and then passes, counting every run. */
    private class Flaky(private val failures: Int, private val error: (Int) -> Throwable) : Statement() {
        var runs = 0
        override fun evaluate() {
            runs++
            if (runs <= failures) throw error(runs)
        }
    }

    @Test
    fun aTestThatFailsThreeTimesAndThenPassesIsGreen() {
        val body = Flaky(3) { AssertionError("flaked") }
        Retry.attempts(who) { body }.evaluate()
        assertEquals(4, body.runs, "three retries means four runs in all")
    }

    @Test
    fun aTestThatPassesFirstTimeRunsOnce() {
        val body = Flaky(0) { AssertionError("never") }
        Retry.attempts(who) { body }.evaluate()
        assertEquals(1, body.runs, "a green test was run again")
    }

    @Test
    fun everyAttemptAsksForTheTestAgain() {
        val built = mutableListOf<Statement>()
        val body = Flaky(2) { AssertionError("flaked") }
        Retry.attempts(who) { body.also { built += it } }.evaluate()
        assertEquals(3, built.size, "an attempt re-ran the test it was handed instead of building a fresh one")
    }

    @Test
    fun aTestThatFailsEveryTimeIsRedAfterFourRunsAndReportsTheFirstFailure() {
        val body = Flaky(Int.MAX_VALUE) { run -> AssertionError(if (run == 1) "what really broke" else "attempt $run") }
        val thrown = assertFailsWith<AssertionError> { Retry.attempts(who) { body }.evaluate() }
        assertEquals(4, body.runs, "gave up after the wrong number of runs")
        val message = thrown.message.orEmpty()
        assertTrue(
            message.contains("failed 4 times") && message.contains("what really broke"),
            "the failure should say it was retried and carry the first cause: $message",
        )
        assertEquals("what really broke", thrown.cause?.message, "the cause should be the first failure")
        assertEquals(3, thrown.suppressed.size, "the other three attempts should ride along as suppressed")
    }

    @Test
    fun aSkippedTestIsNotRetried() {
        val body = Flaky(Int.MAX_VALUE) { AssumptionViolatedException("needs a device") }
        assertFailsWith<AssumptionViolatedException> { Retry.attempts(who) { body }.evaluate() }
        assertEquals(1, body.runs, "a skip is not a failure and was run again")
    }
}
