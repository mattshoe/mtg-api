package org.mattshoe.mtg.core

import io.ktor.client.HttpClient

/**
 * A GET that just returns the text.
 *
 * Ktor's JS engine checks the body it read against `Content-Length`,
 * and Scryfall serves this compressed: the browser hands back the
 * decompressed body while the header still describes the compressed
 * one, so every call threw "Content-Length mismatch: expected 84
 * bytes, but received 60". That was caught and turned into an empty
 * list, so card-name suggestions were simply never there on the web
 * and nothing said why.
 *
 * The client is still passed in, so a test can still answer with a
 * stub rather than reaching the network.
 */
internal expect suspend fun plainGet(
    http: HttpClient,
    url: String,
    accept: String,
    userAgent: String,
): String
