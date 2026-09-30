package org.mattshoe.mtg.core

/**
 * The shell: which screens exist, which are locked, and how a route is
 * spelled.
 *
 * Android has a nav bar and the web has a hash, but which views exist
 * and which of them need a token is one answer, not two. Getting that
 * apart is how a build ends up with a screen the other platform does not
 * have, or an admin page reachable while locked.
 */
enum class View(val slug: String, val label: String, val gated: Boolean = false) {
    LIBRARY("search", "Library"),
    DECKS("decks", "Decks"),
    STATS("stats", "Stats"),
    CONSOLE("console", "Query"),
    ENTRY("entry", "Mass Entry", gated = true),
    LOGS("logs", "Server Logs", gated = true),
    ;

    companion object {
        val DEFAULT = LIBRARY

        fun of(slug: String?) = entries.firstOrNull { it.slug == slug }

        /**
         * Add and remove were separate pages once. Anything still
         * pointing at the old names lands on the wizard rather than on
         * nothing.
         */
        val MOVED = mapOf("add" to ENTRY, "remove" to ENTRY)
    }
}

/** A parsed route: which view, what after it, and any query string. */
data class Route(val view: View, val rest: String = "", val query: String = "") {

    /** `#/decks/alela` rather than `#/decks`. */
    val namesADeck: Boolean get() = view == View.DECKS && rest.isNotEmpty()

    fun toHash(): String = buildString {
        append("#/").append(view.slug)
        if (rest.isNotEmpty()) append('/').append(rest)
        if (query.isNotEmpty()) append('?').append(query)
    }

    companion object {
        /**
         * `#/stats/matt?x=1` in, a route out.
         *
         * An unknown view is the default rather than an error: a stale
         * bookmark should land somewhere useful, not on a blank page.
         */
        fun parse(hash: String?): Route {
            val raw = hash.orEmpty().removePrefix("#").removePrefix("/")
            val path = raw.substringBefore('?')
            val query = raw.substringAfter('?', "")
            val parts = path.split('/').filter { it.isNotEmpty() }
            val head = parts.firstOrNull()
            val view = View.of(head) ?: View.MOVED[head] ?: View.DEFAULT
            return Route(view, parts.drop(1).joinToString("/"), query)
        }
    }
}

/**
 * Admin state.
 *
 * The token is kept until it is locked or the server refuses it. It does
 * not expire — the Worker stopped issuing timed tokens because a share
 * from the Android share sheet is a fresh launch, and being asked for
 * the password every time was worse than useless.
 */
data class Admin(val token: String? = null) {

    val unlocked: Boolean get() = !token.isNullOrBlank()

    /** Which views are reachable right now. */
    fun reachable(view: View): Boolean = !view.gated || unlocked

    val visible: List<View> get() = View.entries.filter { reachable(it) }

    /**
     * Where a route actually lands.
     *
     * A bookmark or a back button can still point at a gated view while
     * locked. It bounces to the default rather than rendering a shell
     * that cannot do anything.
     */
    fun land(route: Route): Route =
        if (reachable(route.view)) route else Route(View.DEFAULT, query = route.query)

    fun unlock(token: String) = copy(token = token)
    fun lock() = copy(token = null)
}
