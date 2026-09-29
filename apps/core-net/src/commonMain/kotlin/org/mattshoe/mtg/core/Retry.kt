package org.mattshoe.mtg.core

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException

/**
 * What every call does when the network does not answer.
 *
 * One policy, installed on every client, so a flaky minute is a pause
 * rather than an error banner. Five retries after the first attempt,
 * doubling from 300ms: 300, 600, 1200, 2400, 4800 — about nine
 * seconds of trying before anything is reported as failed.
 *
 * Retried: a transport failure (no answer at all), a 5xx, and a 429.
 * Not retried: a 4xx, which is the server saying the request itself is
 * wrong and will be just as wrong the sixth time, and a cancellation,
 * which is a newer search replacing this one.
 *
 * One thing this cannot make safe. The Worker applies every mutation
 * as a single `batch()`, so a 5xx means nothing was written and a
 * retry is free. A connection dropped *after* the Worker committed is
 * indistinguishable from one dropped before, so a retried add could
 * apply twice. Fixing that properly needs an idempotency key the
 * Worker remembers; until then the window is one dropped response on
 * a write.
 */
object Retry {

    /** Attempts after the first. Six in total. */
    const val MAX = 5

    const val FIRST_DELAY_MS = 300L

    /** 300, 600, 1200, 2400, 4800. `attempt` is 1-based. */
    fun delayFor(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        return FIRST_DELAY_MS shl (n - 1)
    }

    /** Every status worth trying again. */
    fun worthRetrying(status: Int): Boolean = status == 429 || status in 500..599

    fun worthRetrying(cause: Throwable): Boolean = cause !is CancellationException
}

/** The policy, on a client. */
fun HttpClientConfig<*>.retries() {
    install(HttpRequestRetry) {
        maxRetries = Retry.MAX
        retryIf { _, response -> Retry.worthRetrying(response.status.value) }
        retryOnExceptionIf { _, cause -> Retry.worthRetrying(cause) }
        // `exponentialDelay` starts at a second and cannot be told
        // otherwise, so the schedule is spelled out.
        delayMillis(respectRetryAfterHeader = true) { attempt -> Retry.delayFor(attempt) }
    }
}
