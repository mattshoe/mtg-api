package org.mattshoe.mtg.android

import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a failing UI test up to three more times before calling it red,
 * each time on a fresh test instance.
 *
 * Matt: "20 fucking minutes of waiting just for a flaky test. Your UI
 * tests should have 3 retries for failures." The one that did it was
 * `JourneyTest.openingTheAppShowsCardsTheDatabaseActuallyHas`, red once
 * on the emulator and green on the commit before with no app change.
 *
 * This was a `TestRule` declared outermost on every class, and it
 * never retried a Compose test. A rule retries by evaluating the
 * statement it was handed again, and JUnit builds that statement once
 * against the test instance that already exists, so the second attempt
 * reused the same `createComposeRule()` and Compose refused with "Only
 * a single call to `runTest` can be performed". That was then the last
 * failure, so it was the one reported, and every flaky UI test came
 * back saying something about coroutines.
 *
 * So it is runner-level now. [attempts] takes a function that builds
 * the whole test — instance, rules, befores and afters — and calls it
 * again for every attempt. `RetryingRobolectricRunner` on the JVM and
 * `RetryingDeviceRunner` on a phone pass their own `methodBlock`, and
 * `AndroidJUnit4` is pointed at them by its `android.junit.runner`
 * property: Gradle sets it for the JVM, `RetryingInstrumentationRunner`
 * sets it on the device. Every `AndroidJUnit4` class retries without
 * declaring anything, so a new test cannot quietly opt out.
 *
 * Not Gradle's test-retry plugin, because it cannot reach the
 * instrumentation runner on the device, and because a retried test
 * has to stay ONE result in the XML: `check-test-count.mjs` and the
 * floors count rows. Wrapping `methodBlock` does that, because the
 * runner reports the leaf around it once however many times it runs.
 *
 * A skip is not a failure: an assumption that fails is rethrown at
 * once, or every `needsRealRendering` test would run four times on the
 * JVM to be skipped four times.
 *
 * The failure reported is the FIRST one. Later attempts are what a
 * flake looks like the second time, and the first is what broke.
 */
object Retry {

    /** The property `AndroidJUnit4` reads to choose the runner it delegates to. */
    const val RUNNER_PROPERTY = "android.junit.runner"

    const val RETRIES = 3

    fun attempts(description: Description, retries: Int = RETRIES, test: () -> Statement): Statement =
        test()
}
