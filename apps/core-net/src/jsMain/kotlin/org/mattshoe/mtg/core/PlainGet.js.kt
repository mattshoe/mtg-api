package org.mattshoe.mtg.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Ktor first, so a stubbed engine still answers, and the browser's
 * own fetch when Ktor will not read the response.
 *
 * What it will not read is a compressed body whose `Content-Length`
 * describes the compressed bytes — which is every real call to
 * Scryfall's autocomplete from a browser. The browser has no such
 * quarrel with itself.
 */
internal actual suspend fun plainGet(
    http: HttpClient,
    url: String,
    accept: String,
    userAgent: String,
): String = try {
    http.get(url) { header("Accept", accept) }.bodyAsText()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    browserGet(url, accept)
}

/**
 * `User-Agent` is a forbidden header here and is dropped rather than
 * sent. That is fine: Scryfall asks for one so it can tell clients
 * apart, and a browser already sends its own.
 */
private suspend fun browserGet(url: String, accept: String): String {
    val init = js("({})")
    val headers = js("({})")
    headers["Accept"] = accept
    init["headers"] = headers
    val response = jsFetch(url, init).await()
    if (!(response.ok as Boolean)) error("HTTP " + response.status)
    return (response.text() as Promise<String>).await()
}

@Suppress("UNUSED_PARAMETER")
private fun jsFetch(url: String, init: dynamic): Promise<dynamic> =
    js("fetch(url, init)").unsafeCast<Promise<dynamic>>()
