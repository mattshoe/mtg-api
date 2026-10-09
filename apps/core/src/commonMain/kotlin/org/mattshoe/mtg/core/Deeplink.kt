package org.mattshoe.mtg.core

/**
 * An `mtg.mattshoe.org` address, read back into the app.
 *
 * The inverse of [Share.link]. The site is hash-routed, so every link
 * the app hands out is the site plus a fragment the website has
 * always known how to parse — this reuses that parser rather than
 * writing a second reading of the same URL, because two readings of
 * one format is how a link opens one place in the browser and another
 * on the phone.
 *
 * Here rather than in `androidApp` because it is a fact about a URL.
 * The platform's job is to hand the string over and act on the
 * answer; everything that decides where a link goes is testable
 * without a device.
 */
object Deeplink {

    /**
     * The hosts this app will open a link for.
     *
     * Matched whole, never by suffix. `mtg.mattshoe.org.evil.com`
     * ends in nothing useful but a naive `endsWith` says otherwise,
     * and the whole point of a deep link is that somebody else's
     * content asked for it.
     */
    private val HOSTS = setOf("mtg.mattshoe.org", "www.mtg.mattshoe.org")

    /** Where [Share.link] points: the Worker's `/s/<route>` preview page. */
    private const val PREVIEW_HOST = "mtg-api.mattshoe81.workers.dev"

    /** `https://mtg.mattshoe.org/#/decks/alela` in, a route out, or null. */
    fun parse(url: String?): Route? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val scheme = raw.substringBefore("://", "")
        if (scheme.lowercase() !in setOf("http", "https")) return null

        val afterScheme = raw.substringAfter("://")
        // The authority ends at the first `/`, `?` or `#`, and the
        // port and any userinfo are not part of the host.
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()

        // A shared link: the route is in the path, after `/s`, and
        // the rest of the API is not a page of the app.
        if (host == PREVIEW_HOST) {
            val path = afterScheme.removePrefix(authority).substringBefore('#')
            if (path != "/s" && !path.startsWith("/s/") && !path.startsWith("/s?")) return null
            return Route.parse(path.removePrefix("/s"))
        }
        if (host !in HOSTS) return null

        // Everything after the first `#`. No fragment is the site
        // itself, which is the default view.
        val fragment = afterScheme.substringAfter('#', "")
        return Route.parse(fragment)
    }

    /**
     * The state the app should be in after following [url].
     *
     * The same two steps `MtgApp.mount` takes on the web: restore the
     * Library's filters out of the query string, then navigate. Doing
     * only the second drops the filters, and a shared search then
     * opens showing the whole collection — which is the bug this
     * being shared prevents.
     *
     * Everything the link does not name is left alone. Following a
     * link must not sign you out or bin an unsaved list.
     */
    fun landing(from: AppState, url: String?): AppState {
        val route = parse(url) ?: return from
        return from
            .restoredSearch(FilterUrl.fromHash(route.query))
            .navigate(route)
    }
}
