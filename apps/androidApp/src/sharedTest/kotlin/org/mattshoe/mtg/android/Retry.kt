package org.mattshoe.mtg.android

import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a failing UI test up to three more times before calling it red.
 *
 * Matt: "20 fucking minutes of waiting just for a flaky test. Your UI
 * tests should have 3 retries for failures." The one that did it was
 * `JourneyTest.openingTheAppShowsCardsTheDatabaseActuallyHas`, red once
 * on the emulator and green on the commit before with no app change.
 *
 * Declare it outermost — `@get:Rule(order = Int.MIN_VALUE)` — so every
 * attempt gets a fresh compose rule, activity and `FakeWorker` instead
 * of re-running the body inside whatever the failed attempt left.
 * `EveryUiTestRetriesTest` holds every `AndroidJUnit4` class to that.
 *
 * A rule rather than Gradle's test-retry plugin, because one rule
 * covers both runners — Robolectric and the instrumentation runner on
 * the device, which the plugin cannot reach — and a retried test is
 * still one result in the XML, so `check-test-count.mjs` and the
 * floors count it once.
 *
 * A skip is not a failure: an assumption that fails is rethrown at
 * once, or every `needsRealRendering` test would run four times on the
 * JVM to be skipped four times.
 */
class Retry(private val retries: Int = 3) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val failures = mutableListOf<Throwable>()
            repeat(retries + 1) { attempt ->
                try {
                    base.evaluate()
                    if (failures.isNotEmpty()) {
                        println("RETRY ${description.displayName} passed on attempt ${attempt + 1}, after: ${failures.last()}")
                    }
                    return
                } catch (skip: AssumptionViolatedException) {
                    throw skip
                } catch (e: Throwable) {
                    failures += e
                    println("RETRY ${description.displayName} failed attempt ${attempt + 1} of ${retries + 1}: $e")
                    // A Compose rule cannot be run twice in one test: the
                    // second `base.evaluate()` dies inside kotlinx with
                    // "Only a single call to `runTest` can be performed",
                    // and THAT is the error that gets reported — so every
                    // flaky UI failure came back with a message about
                    // coroutines and nothing about what actually broke.
                    //
                    // Two runs of this suite were spent reading that
                    // message as the cause. Stop at the first one that
                    // cannot be retried and report the real failure.
                    if (e.message?.contains("single call to `runTest`") == true) {
                        throw failures.first()
                    }
                }
            }
            val last = failures.last()
            throw AssertionError("failed ${failures.size} times, last: $last", last).apply {
                failures.dropLast(1).forEach(::addSuppressed)
            }
        }
    }
}
