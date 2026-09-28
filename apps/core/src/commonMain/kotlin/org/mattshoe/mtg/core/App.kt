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
    val history: EntryHistory = EntryHistory(),
    val complete: Completion = Completion(),
    val facets: Facets = Facets(),
    val palette: PaletteState = PaletteState(),
    val card: CardDetail? = null,
    val deckEdit: DeckEditState? = null,
    val disassemble: DisassembleState? = null,
    /** What is on top, and therefore what back closes. */
    val overlays: Overlays = Overlays(),
    /** Set when a share arrived and has not been used yet. */
    val sharedList: String? = null,
    val toast: String? = null,
) {
    val view: View get() = route.view

    /** Where a route actually lands, given the lock. */
    fun navigate(to: Route) =
        copy(route = admin.land(to), toast = null, overlays = overlays.clear(), card = null)

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

    // --------------------------------------------------------- overlays

    fun opening(o: Overlay) = copy(overlays = overlays.open(o), toast = null)

    fun closing(o: Overlay) = copy(overlays = overlays.close(o)).forget(o)

    /**
     * Back, or escape.
     *
     * Returns null when there was nothing to dismiss, which is how a
     * platform knows to let the gesture through to its own navigation
     * rather than swallowing it.
     */
    fun dismissTop(): AppState? {
        val top = overlays.top ?: return null
        return copy(overlays = overlays.pop()).forget(top)
    }

    /** Closing an overlay throws away whatever it was holding. */
    private fun forget(o: Overlay): AppState = when (o) {
        Overlay.CARD -> copy(card = null)
        Overlay.PALETTE -> copy(palette = palette.closed())
        Overlay.DECK_EDIT -> copy(deckEdit = null)
        Overlay.DISASSEMBLE -> copy(disassemble = null)
        Overlay.NEW_DECK -> copy(newDeck = NewDeck())
        Overlay.CHEATSHEET, Overlay.UNLOCK -> this
    }

    // --------------------------------------------------------- shortcuts

    /**
     * A key, and what the whole app becomes because of it.
     *
     * Both platforms route their keyboard through this, so `d` cannot
     * mean decks in one build and nothing in the other. Unhandled keys
     * come back null.
     */
    fun onKey(key: String, typing: Boolean = false, meta: Boolean = false, ctrl: Boolean = false): AppState? {
        val chord = Shortcuts.ofChord(key, meta, ctrl)
        val action = chord ?: Shortcuts.of(key, typing, admin, overlays.any) ?: return null
        return when (action) {
            is Action.Go -> navigate(action.view)
            Action.OpenPalette -> opening(Overlay.PALETTE).copy(palette = palette.opened())
            Action.ShowHelp -> say(Shortcuts.help(admin))
            Action.Close -> dismissTop()
            Action.ToggleLock ->
                if (admin.unlocked) copy(admin = admin.lock()).navigate(route)
                else opening(Overlay.UNLOCK)
        }
    }

    // ------------------------------------------------------------ entry

    /**
     * A finished entry becomes a history row.
     *
     * Recorded here rather than by whichever screen happened to call
     * apply, so a share that writes from the Android activity and a
     * paste that writes from the web land in the same list.
     */
    fun recordEntry(now: String): AppState =
        if (entry.result == null) this
        else copy(history = history.remember(EntryHistory.of(entry, now)))
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

    fun find(term: String): Sql = PaletteQueries.find(term)

    /** The whole filtered set, unpaged, for an export. */
    fun export(filters: Filters): Sql = Export.query(filters)

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
