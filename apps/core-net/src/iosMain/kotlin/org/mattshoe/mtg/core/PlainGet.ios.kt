package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/** Ktor is fine off the browser, and brings the retry policy with it. */
internal actual suspend fun plainGet(
    http: HttpClient,
    url: String,
    accept: String,
    userAgent: String,
): String = http.get(url) {
    header("Accept", accept)
    header("User-Agent", userAgent)
}.bodyAsText()
