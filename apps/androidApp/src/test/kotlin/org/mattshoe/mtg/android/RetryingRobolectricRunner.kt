package org.mattshoe.mtg.android

import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric, with every test retried on a fresh instance. See [Retry].
 *
 * `AndroidJUnit4` delegates to this on the JVM because
 * `android.junit.runner` names it, in `build.gradle.kts`.
 */
class RetryingRobolectricRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun methodBlock(method: FrameworkMethod): Statement =
        Retry.attempts(describeChild(method)) { super.methodBlock(method) }
}
