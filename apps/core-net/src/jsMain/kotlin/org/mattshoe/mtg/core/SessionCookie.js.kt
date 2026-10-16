package org.mattshoe.mtg.core

/**
 * Wrap the browser's `fetch` so a call to [base] says
 * `credentials: 'include'`, and a call anywhere else is left alone.
 *
 * Scryfall answers `access-control-allow-origin: *`, and a browser
 * refuses that answer to a credentialed request, so offering the
 * cookie to everybody would break every card lookup on the site.
 *
 * Ktor 3.0's JS engine calls the global `fetch` and has no option for
 * the credentials mode, which is why this is a wrapper rather than a
 * line of client config. Wrapped once per `fetch`: a second client
 * adds its address to the same wrapper, and a test that swaps `fetch`
 * for a stub gets the stub wrapped by the next client it makes.
 *
 * Off a browser — the Node tests — there is no cookie to send.
 */
internal actual fun sendSessionTo(base: String) {
    if (js("typeof window === 'undefined' || typeof window.fetch !== 'function'") as Boolean) return
    val w: dynamic = js("window")
    val bases: dynamic = w.fetch.mtgSessionBases
    if (bases != null && bases != undefined) {
        if (!(bases.includes(base) as Boolean)) bases.push(base)
        return
    }
    w.fetch = credentialed(w.fetch, arrayOf(base))
}

@Suppress("UNUSED_PARAMETER")
private fun credentialed(inner: dynamic, bases: Array<String>): dynamic = js(
    """
    (function () {
      var wrapped = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
        for (var i = 0; i < bases.length; i++) {
          if (url.indexOf(bases[i]) === 0) {
            init = Object.assign({}, init || {}, { credentials: 'include' });
            break;
          }
        }
        return inner.call(window, input, init);
      };
      wrapped.mtgSessionBases = bases;
      return wrapped;
    })()
    """,
)
