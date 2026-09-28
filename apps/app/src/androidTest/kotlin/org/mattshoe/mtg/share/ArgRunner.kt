package org.mattshoe.mtg.share

import android.os.Bundle

/**
 * The framework test runner, plus a way to read the arguments it was given.
 *
 * The live tests need a token and it must not live in the source or on the
 * device's filesystem. `-e liveToken <token>` is read here and nowhere else.
 */
class ArgRunner : android.test.InstrumentationTestRunner() {
    override fun onCreate(arguments: Bundle?) {
        args = arguments
        super.onCreate(arguments)
    }

    companion object {
        @Volatile
        @JvmStatic
        var args: Bundle? = null

        fun arg(name: String): String? = args?.getString(name)?.trim()?.ifEmpty { null }
    }
}
