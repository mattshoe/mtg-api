package org.mattshoe.mtg.android

import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.Result
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM's retrying runner, driven for real over the classes in
 * `RetryFixtures.kt`.
 *
 * What it has to keep, from the request that replaced the rule:
 * a retried test is still ONE result, because `check-test-count.mjs`
 * and the floors count rows; each attempt is a fresh instance, because
 * a reused compose rule refuses a second run; and a skip is a skip,
 * run once.
 */
class RetryingRunnerTest {

    private class Ran(val result: Result, val runs: Int, val instances: List<Int>)

    private fun drive(fixture: Class<*>): Ran {
        RetryFixtures.switchOn()
        try {
            val result = JUnitCore().run(Request.runner(RetryingRobolectricRunner(fixture)))
            return Ran(result, RetryFixtures.runs(), RetryFixtures.instances())
        } finally {
            RetryFixtures.switchOff()
        }
    }

    private fun Result.said() = failures.joinToString { it.message.orEmpty() }

    @Test
    fun aComposeTestThatFailsOnceIsRetriedOnAFreshInstanceAndPasses() {
        val ran = drive(FlakyComposeFixture::class.java)
        assertEquals(2, ran.runs, "the failed first attempt was not retried: ${ran.result.said()}")
        assertEquals(2, ran.instances.toSet().size, "both attempts ran on the same instance, so its rules were reused")
        assertTrue(ran.result.wasSuccessful(), "a test green on its second attempt is green: ${ran.result.said()}")
        assertEquals(1, ran.result.runCount, "a retried test reported more than one result")
    }

    @Test
    fun aTestThatAlwaysFailsIsOneFailureAfterFourAttemptsSayingWhatBroke() {
        val ran = drive(AlwaysFailsFixture::class.java)
        assertEquals(4, ran.runs, "three retries means four attempts")
        assertEquals(1, ran.result.runCount, "a retried test reported more than one result")
        assertEquals(1, ran.result.failureCount, "four failed attempts are one failure")
        assertTrue(
            ran.result.said().contains("really broken, attempt 1"),
            "the failure should name the first attempt's cause: ${ran.result.said()}",
        )
    }

    @Test
    fun aSkippedTestRunsOnceAndIsOneSkip() {
        val ran = drive(SkippedFixture::class.java)
        assertEquals(1, ran.runs, "a skip is not a failure and was run again")
        assertEquals(0, ran.result.failureCount, "a skip was reported as a failure: ${ran.result.said()}")
        assertEquals(1, ran.result.assumptionFailureCount, "the skip was not reported as a skip")
    }
}
