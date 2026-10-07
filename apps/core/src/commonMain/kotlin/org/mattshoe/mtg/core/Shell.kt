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
    /** Whether it needs a role and not merely an account. */
    val operator: Boolean = false,
) {
    LIBRARY("search", "Library"),
    DECKS("decks", "Decks"),
    STATS("stats", "Stats"),
    // "Mass Entry" wrapped onto two lines under an icon. The screen
    // is unchanged; only what the bar calls it.
    ENTRY("entry", "Entry", gated = true),
    /**
     * Running the server, not owning cards.
     *
     * `operator = true` is the difference between the two gates: Entry
     * needs an account, this needs a role, and nobody has a role
     * unless Matt hands them one. "NOBODY GETS FUCKING ADMIN
     * PERMISSIONS!!!!!! YOU JUST GET TO MODIFY YOUR OWN FUCKING CARDS
     * BY DEFAULT!!!!!"
     */
    /**
     * The knobs and levers, which today is who has which role.
     *
     * Matt: "under account avatar, we'll create a button 'Admin
     * Settings' and in there will live all of the admin knobs and
     * levers like assigning roles etc"
     *
     * Before the log, because handing out a role is a thing you come
     * here to do and the log is a thing you come here to read.
     */
    ADMIN("admin", "Admin Settings", gated = true, bar = false, operator = true),
    LOGS("logs", "Server Logs", gated = true, bar = false, operator = true),

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

        /**
         * The views that are somebody's collection.
         *
         * One answer rather than one per shell: both `loadFor`s gate
         * on it, and a view added to the app has to be classified
         * here rather than quietly reading every collection at once.
         * A card is not in it — a card page is the card, and says
         * which collections hold copies.
         */
        val COLLECTION = setOf(LIBRARY, DECKS, STATS)

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
data class Route(
    val view: View,
    val rest: String = "",
    val query: String = "",
    /**
     * Whose collection this address names, by its public key.
     *
     * Empty for every link that existed before keys did, which still
     * work and still mean "whatever collection the reader is looking
     * at". A link worth sharing is not one of those — Matt: "if i
     * copy the url then they'll just go to their own fucking
     * collection" — which is what this segment is for.
     */
    val collection: String = "",
) {

    /** `#/decks/alela` rather than `#/decks`. */
    val namesADeck: Boolean get() = view == View.DECKS && rest.isNotEmpty()

    /** `#/card/matt:sol+ring`. The whole address of one card. */
    val namesACard: Boolean get() = view == View.CARD && rest.isNotEmpty()

    fun toHash(): String = buildString {
        append("#/")
        if (collection.isNotEmpty()) append("c/").append(collection).append('/')
        append(view.slug)
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
            val all = path.split('/').filter { it.isNotEmpty() }
            // `c/<key>` in front, when there is one. Everything after
            // it is the address it has always been, so a collection
            // can be put in front of any link without touching what
            // the link means.
            val collection = if (all.firstOrNull() == "c") all.getOrNull(1).orEmpty() else ""
            val parts = if (collection.isEmpty()) all else all.drop(2)
            val head = parts.firstOrNull()
            val view = View.of(head) ?: View.MOVED[head] ?: View.DEFAULT
            return Route(view, parts.drop(1).joinToString("/"), query, collection)
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
/**
 * Somebody signed in.
 *
 * `slug` is both who they are and where their collection lives:
 * `cards.owner` has held a slug since the first day, so an account
 * whose slug is `matt` owns every row that says `matt`.
 *
 * `role` is about running the server — the log, the maintenance job —
 * and not about owning cards. Every account owns its own collection
 * with no role at all, which is what Matt asked for: "admin rights by
 * default for their own cards and only their own cards".
 */
data class Account(
    val slug: String,
    val name: String? = null,
    val avatar: String? = null,
    val role: String = Role.USER,
    /**
     * The public identifier the collection is shared by, which is
     * what goes in an address. Never what decides whether anybody may
     * edit it: that is the session's business.
     */
    val key: String = "",
) {
    val isOperator: Boolean get() = role == Role.ADMIN

    /** What to call them. A Google account can arrive with no name on it. */
    val shownName: String get() = name?.takeIf { it.isNotBlank() } ?: slug

    /**
     * Whether this account may edit that collection.
     *
     * Its own, always. Anybody's, with the admin role — Matt: "Anyone
     * with the admin role will be able to do whatever they want, from
     * modify others cards to giving other users admin etc etc."
     *
     * This line came out for an hour this morning on "NOBODY GETS
     * FUCKING ADMIN PERMISSIONS!!!!!! YOU JUST GET TO MODIFY YOUR OWN
     * FUCKING CARDS BY DEFAULT!!!!!", which is about the default and
     * not about what the role means once granted. Both hold at once:
     * every new account is a `user`, a `user` owns only its own
     * cards, and nobody is an `admin` unless Matt says so.
     *
     * Only an affordance either way. The server keeps the same rule
     * in `canEdit`, and it is the one that counts.
     */
    fun owns(collection: String): Boolean =
        isOperator || (collection.isNotEmpty() && collection == slug)
}

data class Admin(
    /**
     * The session, and only ever a session.
     *
     * It used to be either a session or the operator's password, and
     * the password could write to anybody's cards — the thing
     * accounts replaced. Only [signIn] sets this now, so a token
     * arriving from anywhere else proves nothing and unlocks nothing.
     */
    val token: String? = null,
    /** Who is signed in, if anybody. */
    val account: Account? = null,
    /**
     * Whether the server has said who this session is.
     *
     * False for the moment between the page opening and `/auth/me`
     * answering. It matters because an unanswered question and "no
     * account" look identical in this object otherwise, and the app
     * read the second: every collection-scoped query went out with an
     * empty owner, which meant *every* collection, so the first load
     * of the decks page was everybody's decks. Matt: "why are kaylas
     * decks showing for me in the web app?!?!?!"
     *
     * Nothing scoped is fetched until this is true. The answer itself
     * can be "nobody", which is settled too — a stranger following a
     * link is a legitimate reader.
     */
    val settled: Boolean = false,
) {

    val signedIn: Boolean get() = account != null

    /** What to call whoever is here. */
    val shownName: String? get() = account?.shownName

    /**
     * Whether this app may offer to change anything.
     *
     * Being signed in, and nothing else. There was a shared password
     * beside it — one secret that could write to anybody's cards —
     * and an app offering both was offering two ways to be somebody,
     * one of which was a way to be everybody.
     *
     * Only an affordance. "Only the collection's owner may edit that
     * collection" is the server's rule and the server keeps it; what
     * this decides is whether to draw the button. `AppState.canEdit`
     * is the one that knows *whose* collection is on screen.
     */
    val unlocked: Boolean get() = signedIn

    /** Which views are reachable right now. */
    fun reachable(view: View): Boolean = when {
        view.operator -> account?.isOperator == true
        view.gated -> signedIn
        else -> true
    }

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
     * Empty for almost everybody: the log is the one screen that
     * needs a role, and a role is Matt's to hand out.
     */
    val behindProfile: List<View> get() = visible.filter { !it.bar }

    /**
     * Where a route actually lands.
     *
     * A bookmark or a back button can still point at a gated view
     * while nobody is signed in. It bounces to the default rather
     * than rendering a shell that cannot do anything.
     *
     * Not until the server has answered, though. Asking who this
     * session is takes a round trip, and bouncing on the way to the
     * answer put you on the Library every time you opened a bookmark
     * to `#/entry` — the same mistake as fetching before the answer:
     * an unanswered question is not a "no". The shells re-land once
     * [settled] turns true, so a route that really is out of reach
     * still bounces, a moment later.
     */
    fun land(route: Route): Route = when {
        !settled -> route
        reachable(route.view) -> route
        else -> Route(View.DEFAULT, query = route.query)
    }

    /**
     * Signed in, and carrying the session that proves it.
     *
     * The session goes in `token` on purpose rather than a field of
     * its own: that is already the bearer every write sends and
     * already what `AdminToken` persists, so an account's session is
     * kept and presented by the code that was doing both anyway.
     */
    fun signIn(account: Account, session: String? = null) =
        copy(account = account, token = session ?: token, settled = true)

    /**
     * The server answered, and the answer was nobody.
     *
     * `settle`, not `settled`: Kotlin/JS gives a property and a
     * function of the same name the same JavaScript name, and the
     * two clash at compile time.
     */
    fun settle() = copy(settled = true)

    /** Out: the account and the session that proved it. */
    fun signOut() = Admin(settled = true)
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

/**
 * What the thing is called.
 *
 * One string, because it is the launcher label, the page title and
 * the word in the Android header, and three copies of it drift. The
 * Android label is checked against this in `AppNameTest`.
 */
object Brand {
    const val NAME = "MTG Collection"
}
