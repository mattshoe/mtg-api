package org.mattshoe.mtg.web

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A test that fails the first time it runs and passes the second.
 *
 * Matt: "Your UI tests should have 3 retries for failures." On the web
 * that is Mocha's own `retries`, set in `karma.config.d/mocha.js`.
 * This proves Karma hands the setting to Mocha in the real browser: if
 * it stops doing so, this is red.
 *
 * The count lives at the top level because a retry builds the test
 * class afresh.
 */
private var runs = 0

class RetryIsWiredTest {

    @Test
    fun aTestThatFailsOnceIsRetriedAndPasses() {
        runs++
        assertTrue(runs > 1, "ran once and was not retried")
    }
}
