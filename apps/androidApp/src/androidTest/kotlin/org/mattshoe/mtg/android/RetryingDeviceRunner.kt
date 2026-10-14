package org.mattshoe.mtg.android

import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * The instrumentation runner's own class runner, with every test
 * retried on a fresh instance. See [Retry].
 *
 * `AndroidJUnit4` delegates to this on a phone because
 * [RetryingInstrumentationRunner] sets `android.junit.runner` to it.
 */
class RetryingDeviceRunner(testClass: Class<*>) : AndroidJUnit4ClassRunner(testClass) {
    override fun methodBlock(method: FrameworkMethod): Statement =
        Retry.attempts(describeChild(method)) { super.methodBlock(method) }
}
