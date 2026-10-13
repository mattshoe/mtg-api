package org.mattshoe.mtg.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * `AndroidJUnitRunner`, pointing `AndroidJUnit4` at [RetryingDeviceRunner]
 * before any test class is built. `testInstrumentationRunner` in
 * `build.gradle.kts` names this. See [Retry].
 */
class RetryingInstrumentationRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        System.setProperty(Retry.RUNNER_PROPERTY, RetryingDeviceRunner::class.java.name)
        super.onCreate(arguments)
    }
}
