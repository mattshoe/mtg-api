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
enum class View(
    val slug: String,
    val label: String,
    val gated: Boolean = false,
    /** Whether the menu offers it. A card is reached from a card, not from a list of places. */
    val inNav: Boolean = true,
    /**
     * Whether it belongs in the bottom bar rather than behind the
     * profile.
     *
     * The bar is for the places you move between constantly; the
     * profile is for who you are and the things that follow from
     * that. The server log is a thing you look at once a month when
     * something is wrong, so it sits behind the profile with the
     * lock rather than taking a fifth of the bar forever.
     *
     * A bar label is drawn under an icon, so it is one short word.
     * `everyItemInTheBarIsOneShortWord` enforces that rather than
     * leaving it to whoever adds the next one.
     */
    val bar: Boolean = true,
) {
    LIBRARY("search", "Library"),
    DECKS("decks", "Decks"),
    STATS("stats", "Stats"),
    // "Mass Entry" wrapped onto two lines under an icon. The screen
    // is unchanged; only what the bar calls it.
    ENTRY("entry", "Entry", gated = true),
    LOGS("logs", "Server Logs", gated = true, bar = false),

    /**
     * One card, as its own destination.
     *
     * It was a drawer over whatever was underneath, with its address
     * riding in the query string of that page. Every navigation bug
     * of the last week came out of that: a link to a card carried the
     * deck it was opened from, back had to guess whether to dismiss
     * or navigate, and the page behind it kept its own scroll.
     */
    CARD("card", "Card", inNav = false, bar = false),
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

    /** `#/card/matt:sol+ring`. The whole address of one card. */
    val namesACard: Boolean get() = view == View.CARD && rest.isNotEmpty()

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
data class Admin(
    val token: String? = null,
    /** A password already gone to the server and not yet answered. */
    val trying: Boolean = false,
) {

    val unlocked: Boolean get() = !token.isNullOrBlank()

    /** Offered once, until the server has said something back. */
    val canTry: Boolean get() = !trying

    /** Which views are reachable right now. */
    fun reachable(view: View): Boolean = !view.gated || unlocked

    val visible: List<View> get() = View.entries.filter { it.inNav && reachable(it) }

    /**
     * The bottom bar: everywhere you go often, in order.
     *
     * Three while locked, four while not — `Entry` joins rather than
     * the bar changing shape around it.
     */
    val bar: List<View> get() = visible.filter { it.bar }

    /**
     * Everywhere the profile offers, which today is the server log.
     *
     * Empty while locked, because there is nothing behind the
     * profile but admin and the things admin unlocks.
     */
    val behindProfile: List<View> get() = visible.filter { !it.bar }

    /**
     * Where a route actually lands.
     *
     * A bookmark or a back button can still point at a gated view while
     * locked. It bounces to the default rather than rendering a shell
     * that cannot do anything.
     */
    fun land(route: Route): Route =
        if (reachable(route.view)) route else Route(View.DEFAULT, query = route.query)

    fun tries() = copy(trying = true)
    fun unlock(token: String) = copy(token = token, trying = false)
    fun gaveUp() = copy(trying = false)
    fun lock() = copy(token = null, trying = false)
}

/**
 * The token's one address outside memory, and the one function that
 * writes it there or takes it away.
 *
 * `Admin.lock()` only ever changed the copy in memory — locking and
 * reopening the app brought the old token right back, because
 * clearing the persisted one was each platform's own job, and there
 * turned out to be more than one place that locks: a nav-menu button
 * and a keyboard shortcut, on two platforms, none of which remembered
 * to forget. Fixing the button is fixing one of them.
 *
 * So neither platform calls `store.put`/`store.remove` for the token
 * by hand any more. Each keeps its own `AppState` behind a single
 * property with a custom setter (the web already had the shape, for
 * the toast and the address bar) and calls [sync] from there, once,
 * comparing the old `Admin` to the new one on every write. Whichever
 * of the two UI paths produced the change, and whatever a third path
 * does tomorrow, the token in storage cannot drift from the token in
 * memory — there is no separate step to skip.
 */
object AdminToken {
    const val KEY = "mtg.admin"

    /** What a fresh launch finds, if anything. */
    fun restore(store: Store): String? = store.get(KEY)

    /** Call with the `Admin` before and after every write to `AppState`. */
    fun sync(store: Store, was: Admin, next: Admin) {
        if (was.token == next.token) return
        val t = next.token
        if (t.isNullOrBlank()) store.remove(KEY) else store.put(KEY, t)
    }
}
