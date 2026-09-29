package org.mattshoe.mtg.core

import kotlin.random.Random

/**
 * One key per write, kept across that write's own retries.
 *
 * Retrying is only safe if the second attempt can be recognised as the
 * same write. Every mutation the Worker applies is a single `batch()`,
 * so a 5xx means nothing landed — but a connection dropped *after* it
 * committed looks exactly the same from here, and without a key a
 * retried add applies twice.
 *
 * The key is set once, when the request is built. Ktor's retry re-sends
 * the same request, header and all, so a retry carries the key of the
 * attempt it is repeating and the Worker answers it with the first
 * attempt's reply.
 */
object Idempotency {

    const val HEADER = "Idempotency-Key"

    /**
     * Random rather than a hash of the request: two deliberate identical
     * writes — adding one Sol Ring, then another — are different writes
     * and must both apply. Only a retry of the same attempt shares a key.
     *
     * 128 bits, hand-rolled because `kotlin.uuid` is not on every target
     * this module builds for.
     */
    fun key(): String = buildString(32) {
        repeat(32) { append(HEX[Random.nextInt(16)]) }
    }

    private const val HEX = "0123456789abcdef"
}
