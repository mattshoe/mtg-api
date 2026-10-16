package org.mattshoe.mtg.web

import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit

/**
 * `GET /c/<key>` answered here, for a test that plays `window.fetch`.
 *
 * The shell resolves the key in the address with a plain `window.fetch`,
 * which a `MockEngine` never sees, so these tests used to send it to the
 * live Worker. The Worker answers a stranger's origin with
 * `allow-origin: *`, and since the site's calls carry the session cookie
 * a browser refuses that answer: the collection never resolved and every
 * journey waited out its timeout.
 */
internal fun collectionAt(url: String): Response {
    val key = url.substringAfterLast("/c/").substringBefore('?')
    return Response(
        """{"key":"$key","name":"Matt"}""",
        ResponseInit(status = 200, headers = js("({'Content-Type':'application/json'})")),
    )
}
