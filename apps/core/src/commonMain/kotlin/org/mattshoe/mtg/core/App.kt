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

    /**
     * Where the address bar should point right now.
     *
     * The Library's filters live in the query string, so a plain
     * `Route.toHash()` for that view drops them — leave the tab and
     * come back and the URL no longer describes what is on screen.
     */
    fun hash(): String {
        val base = if (view == View.LIBRARY) FilterUrl.toHash(library.filters) else route.toHash()
        return CardRef.appendTo(base, cardRef)
    }

    /**
     * The open card, if one is. Null when the drawer is shut, so the
     * address goes back to the page underneath when it closes.
     */
    val cardRef: CardRef?
        get() = card?.takeIf { Overlay.CARD in overlays.stack }
            ?.let { CardRef(it.owner, it.nameNorm) }

    fun say(message: String?) = copy(toast = message)

    /**
     * The screen a route lands on, marked as fetching.
     *
     * Both platforms went straight to their `work` helper, which
     * leaves `busy` false — so Decks rendered "No decks yet" over a
     * load that was still in flight, and kept rendering it if the load
     * failed, because the failure went to a toast. It read as the
     * decks having vanished, on and off, depending on how fast the
     * database answered.
     */
    fun fetching(view: View = this.view): AppState = when (view) {
        View.LIBRARY -> copy(library = library.loading())
        View.DECKS -> copy(decks = decks.loading())
        View.STATS -> copy(stats = stats.loading())
        View.LOGS -> copy(logs = logs.loading())
        View.CONSOLE, View.ENTRY -> this
    }

    /** And the same screen, told why it has nothing to show. */
    fun fetchFailed(message: String, view: View = this.view): AppState = when (view) {
        View.LIBRARY -> copy(library = library.failed(message))
        View.DECKS -> copy(decks = decks.failed(message))
        View.STATS -> copy(stats = stats.failed(message))
        View.LOGS -> copy(logs = logs.failed(message))
        View.CONSOLE, View.ENTRY -> say(message)
    }

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

    // ------------------------------------------------------- the name box

    /**
     * A character typed into the card name box.
     *
     * Both the suggestion state and the filter hold that word, and
     * they have to move together in ONE copy. Done as two calls —
     * `onComplete(c)` then `onState(library)` — both build on the
     * `AppState` captured before the keystroke, so whichever lands
     * second throws the other away. That is what stopped the box
     * accepting a single character: `complete.term` never advanced,
     * and the input is bound to it.
     */
    /**
     * A whole search arriving from a link or a cold start.
     *
     * Not just `library.restoredFrom`: the card name box is bound to
     * `complete.term`, not to `filters.q`, so restoring only the
     * filters left the search applied with an empty box above it — no
     * way to see what was filtering and no way to clear it by hand.
     */
    fun restoredSearch(f: Filters): AppState = copy(
        library = library.restoredFrom(f),
        complete = complete.copy(term = f.q, items = emptyList(), open = false),
    )

    fun typedCardName(c: Completion): AppState = copy(
        complete = c,
        library = library.where(library.filters.copy(q = c.term)),
    )

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

    /** Everything the drawer shows: printings, decks, legality, rulings. */
    fun card(nameNorm: String, owner: String): List<Sql> = listOf(
        CardQueries.printings(nameNorm, owner),
        CardQueries.usedIn(nameNorm, owner),
        CardQueries.legalities(nameNorm),
        CardQueries.rulings(nameNorm),
    )

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
