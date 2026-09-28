package org.mattshoe.mtg.core

/**
 * The whole app's state, and the one object that knows how to fill it.
 *
 * Every screen's state hangs off this, and `load` is the only thing that
 * talks to the API on their behalf. Both platforms drive the same
 * object, so "what does the Decks tab do when you open it" has one
 * answer rather than two that drift.
 */
data class AppState(
    val route: Route = Route(View.DEFAULT),
    val admin: Admin = Admin(),
    val library: Library = Library(),
    val decks: DecksState = DecksState(),
    val stats: StatsState = StatsState(),
    val console: ConsoleState = ConsoleState(),
    val logs: LogsState = LogsState(),
    val entry: MassEntry = MassEntry(),
    val newDeck: NewDeck = NewDeck(),
    /** Set when a share arrived and has not been used yet. */
    val sharedList: String? = null,
    val toast: String? = null,
) {
    val view: View get() = route.view

    /** Where a route actually lands, given the lock. */
    fun navigate(to: Route) = copy(route = admin.land(to), toast = null)

    fun navigate(view: View, rest: String = "") = navigate(Route(view, rest))

    fun say(message: String?) = copy(toast = message)

    /**
     * A shared list opens the wizard with the list already in the box.
     * The direction and the owner are still unanswered, the same as
     * anything typed.
     */
    fun withShare(list: String) = copy(
        sharedList = list,
        entry = MassEntry.fromShare(list),
        route = admin.land(Route(View.ENTRY)),
    )

    fun shareUsed() = copy(sharedList = null)
}

/**
 * Loading a screen.
 *
 * Returns the statements a view needs, so the platforms run them and
 * hand the rows back. Keeping the SQL here rather than in two UIs is the
 * point of the whole exercise.
 */
object Load {

    /** The Library needs its page and its count, from the same search. */
    fun library(s: Library): Pair<Sql, Sql> = s.queries()

    fun decks(): Sql = DeckQueries.all()

    fun deck(slug: String): Sql = DeckQueries.cards(slug)

    fun stats(scope: StatsScope): Sql = StatsQueries.totals(scope)

    fun card(nameNorm: String, owner: String): Pair<Sql, Sql> =
        CardQueries.printings(nameNorm, owner) to CardQueries.usedIn(nameNorm, owner)

    /**
     * Which route needs what.
     *
     * A platform asks this rather than deciding for itself, so opening
     * the Stats tab cannot fetch different things on Android than on the
     * web.
     */
    fun needs(route: Route): List<String> = when (route.view) {
        View.LIBRARY -> listOf("cards", "count")
        View.DECKS -> if (route.rest.isEmpty()) listOf("decks") else listOf("decks", "deck")
        View.STATS -> listOf("totals")
        View.LOGS -> listOf("logs")
        View.CONSOLE, View.ENTRY -> emptyList()
    }

    /** Stats scoping lives in the route: `#/stats/matt`. */
    fun scopeFrom(rest: String): StatsScope =
        StatsScope(Owner.entries.firstOrNull { it.slug == rest })
}
