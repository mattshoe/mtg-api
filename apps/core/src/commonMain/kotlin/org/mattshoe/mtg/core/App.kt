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
    /** One card being added, swapped, counted or taken out. */
    val deckTweak: DeckTweak? = null,
    val disassemble: DisassembleState? = null,
    val rename: RenameState? = null,
    /** What is on top, and therefore what back closes. */
    val overlays: Overlays = Overlays(),
    /** Set when a share arrived and has not been used yet. */
    val sharedList: String? = null,
    /**
     * The page a card was opened from, for the button that goes back
     * to it. Null when the card was opened from a link somebody sent,
     * which has nothing behind it.
     */
    val from: Route? = null,
    val toast: String? = null,
    /**
     * Whether `toast` is reporting a failure.
     *
     * Both platforms used to say a dropped request and a finished
     * save in the same voice — a toast is a toast. On the web that
     * meant `.toast.ok` and `.toast.bad` sat in the stylesheet unused,
     * because nothing ever told the DOM which one it was drawing.
     */
    val toastFailed: Boolean = false,
) {
    val view: View get() = route.view

    /** Where a route actually lands, given the lock. */
    fun navigate(to: Route): AppState {
        val landed = admin.land(to)
        // A route change takes the overlays with it, and an overlay
        // that goes has to let go of what it was holding the same way
        // it would if its own X had been pressed. Clearing only the
        // stack left a half-typed new deck and a stale palette answer
        // sitting behind the nav bar, so the next `/` showed rows that
        // answered a search from two screens ago.
        return overlays.stack.fold(this) { s, o -> s.forget(o) }.copy(
            route = landed,
            toast = null,
            toastFailed = false,
            overlays = overlays.clear(),
            card = null,
            // A route that does not name a deck has no deck open.
            // The open deck lives in `decks`, not in the route, so
            // going back from a deck to the list left the detail on
            // screen over an address that said list.
            // A card is opened from a deck as often as from the
            // library, and closing the deck here would throw its
            // cards away and re-read them on the way back.
            decks = if (landed.namesADeck || landed.view == View.CARD) decks else decks.close(),
        )
    }

    fun navigate(view: View, rest: String = "") = navigate(Route(view, rest))

    /**
     * Where the address bar should point right now.
     *
     * The Library's filters live in the query string, so a plain
     * `Route.toHash()` for that view drops them — leave the tab and
     * come back and the URL no longer describes what is on screen.
     */
    fun hash(): String =
        if (view == View.LIBRARY) FilterUrl.toHash(library.filters) else route.toHash()

    /**
     * Is this somewhere new, rather than the same place rewritten?
     *
     * The address bar gains an entry for a step and rewrites one for
     * everything else. A different screen is a step, and so is a
     * different deck or a different card, because each is its own
     * route. A filter changing on every keystroke is not.
     */
    fun isAStepFrom(was: AppState): Boolean =
        route.view != was.route.view || route.rest != was.route.rest

    /** Which card the address names, if it names one. */
    val cardRef: CardRef? get() = if (view == View.CARD) CardRef.parse(route.rest) else null

    /**
     * Open a card, remembering the page it was opened from.
     *
     * The card is its own destination, so this is an ordinary
     * navigation: the address says the card and nothing else, back
     * leaves the way it came, and the link is the card alone rather
     * than the card plus whatever deck happened to be underneath.
     */
    fun openCard(ref: CardRef, name: String = ref.nameNorm): AppState =
        navigate(Route(View.CARD, ref.encoded())).copy(
            card = CardDetail(name = name, nameNorm = ref.nameNorm).loading(),
            // Where the *run* of cards started, not the last card.
            // Going card to card kept overwriting this with nothing,
            // so one step along a deck left back with nowhere to go.
            from = if (route.view == View.CARD) from else route,
        )

    /**
     * The deck this card is being read as part of, in page order.
     *
     * Empty unless the card was opened from the deck that is still
     * loaded — a card reached from the Library or a link belongs to
     * no run and gets no next and no previous.
     */
    val deckRun: List<DeckCard>
        get() {
            val slug = from?.takeIf { it.view == View.DECKS }?.rest ?: return emptyList()
            if (slug.isEmpty() || slug != decks.openSlug) return emptyList()
            return decks.pageOrder
        }

    /** Where this card sits in that run, or -1 if it is not in one. */
    val cardAt: Int
        get() = cardRef?.nameNorm?.let { norm -> deckRun.indexOfFirst { it.nameNorm == norm } } ?: -1

    val previousCard: DeckCard? get() = cardAt.takeIf { it > 0 }?.let { deckRun[it - 1] }
    val nextCard: DeckCard? get() = cardAt.takeIf { it in 0 until deckRun.size - 1 }?.let { deckRun[it + 1] }

    /** "7 of 99", for somebody halfway down a deck. */
    val cardPlace: String? get() = cardAt.takeIf { it >= 0 }?.let { "${it + 1} of ${deckRun.size}" }

    /** Where the card's back button goes. The library, for a link with nothing behind it. */
    fun leaveCard(): AppState = navigate(from ?: Route(View.DEFAULT))

    /**
     * What the header says you are looking at.
     *
     * In the bar rather than repeated as a heading at the top of
     * every page — a label floating over the content was saying the
     * same thing the address bar already said, twice.
     */
    val title: String
        get() = when {
            view == View.DECKS && decks.open != null -> decks.open!!.title
            view == View.CARD -> card?.name.orEmpty().ifBlank { View.CARD.label }
            else -> view.label
        }

    fun say(message: String?, failed: Boolean = false) =
        copy(toast = message, toastFailed = failed && message != null)

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
        View.CARD -> copy(card = card?.loading())
        View.CONSOLE, View.ENTRY -> this
    }

    /** And the same screen, told why it has nothing to show. */
    fun fetchFailed(message: String, view: View = this.view): AppState = when (view) {
        View.LIBRARY -> copy(library = library.failed(message))
        View.DECKS -> copy(decks = decks.failed(message))
        View.STATS -> copy(stats = stats.failed(message))
        View.LOGS -> copy(logs = logs.failed(message))
        View.CARD -> copy(card = card?.failed(message))
        View.CONSOLE, View.ENTRY -> say(message, failed = true)
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

    fun opening(o: Overlay) = copy(overlays = overlays.open(o), toast = null, toastFailed = false)

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

    /**
     * One press of back, wherever you are.
     *
     * Returns null when there is nothing left to go back to and the
     * app should close.
     *
     * The order is the one a browser's history produces, which is
     * what the website gets for free and the phone has to be told:
     * put the suggestion list away, then take off whatever is on top,
     * then leave a card the way its own Close does, then come out of
     * an open deck, then back to the default view, then out.
     *
     * The suggestion list goes first, and it goes first for the same
     * reason an overlay does: it floats over the screen, and while it
     * is up it is what a press is aimed at. Nothing used to ask about
     * it at all — `dismissTop` has never heard of `complete` — so a
     * press on Back with the card-name list open skipped it and acted
     * on whatever was behind it instead: a deck closing, or the app
     * leaving, while the suggestions stayed exactly where they were.
     *
     * A card is checked before a deck, and that ordering is the whole
     * point. Reading a card from inside a deck, the deck is still
     * open behind it, so a rule that asked about the deck first threw
     * the deck away while leaving the card on screen — one press that
     * visibly did nothing, and a second that fell through to Library.
     * `leaveCard` goes to the route the card was opened from, which
     * is the deck.
     */
    fun back(): AppState? = when {
        // `closed()` and not a rebuilt `Completion`: closing has to
        // leave the typed term alone. A press that handed back a whole
        // new `Completion` would carry whatever term the frame it was
        // built in happened to be drawing, which is how the web's
        // version used to put a search you had just cleared back on
        // the screen.
        complete.open -> copy(complete = complete.closed())
        overlays.any -> dismissTop()
        view == View.CARD -> leaveCard()
        // Back to the list as a route, not just by emptying `decks`.
        // The open deck is in the address — that is what lets a card
        // opened from it know where it came from — so closing it has
        // to put the address back too, or the screen says list and
        // the address still says deck.
        decks.openSlug != null -> navigate(Route(View.DECKS))
        view != View.DEFAULT -> navigate(View.DEFAULT)
        else -> null
    }

    /**
     * Would the next `back()` actually leave the app, with a pasted
     * list still sitting unsent in Mass Entry?
     *
     * Matt: "if you get to exit early, i want you to alert the user
     * that the changes will not be saved." `back()` only answers
     * *where* the press goes; a platform that just forwards a null
     * straight to its own exit — which is what Android's `BackHandler`
     * did — closes over whatever is unsaved without ever asking.
     * `entry.unsaved` is already the one rule for what counts as work
     * worth losing (`MassEntry`'s own gate, not a second one invented
     * here); this only tells a shell *when* that rule is the thing to
     * check — on the press that would otherwise walk out the door,
     * not on every press, and not on a press that is only moving to
     * another tab. The web's `beforeunload` asks the same question a
     * different way, by checking `entry.unsaved` against an event
     * that only fires on an actual tab close — this is that same
     * check, written so a second platform does not have to re-derive
     * when "leaving" is.
     */
    val wouldExitWithUnsavedEntry: Boolean get() = back() == null && entry.unsaved

    /** Closing an overlay throws away whatever it was holding. */
    private fun forget(o: Overlay): AppState = when (o) {
        Overlay.PALETTE -> copy(palette = palette.closed())
        Overlay.DECK_EDIT -> copy(deckEdit = null)
        Overlay.DECK_TWEAK -> copy(deckTweak = null)
        Overlay.DISASSEMBLE -> copy(disassemble = null)
        Overlay.NEW_DECK -> copy(newDeck = NewDeck())
        Overlay.RENAME -> copy(rename = null)
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

    companion object {
        /**
         * How long a toast says its piece before it goes on its own.
         *
         * One number, shared by both platforms, because a `5000`
         * hard-coded twice is exactly how a web fade and an Android
         * fade drift apart. Long enough to read a sentence, short
         * enough to stop mattering.
         */
        const val TOAST_MS = 5_000L
    }

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
    fun card(nameNorm: String): List<Sql> = listOf(
        CardQueries.printings(nameNorm),
        CardQueries.usedIn(nameNorm),
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
        View.CARD -> listOf("card")
        View.CONSOLE, View.ENTRY -> emptyList()
    }

    /** Stats scoping lives in the route: `#/stats/matt`. */
    fun scopeFrom(rest: String): StatsScope =
        StatsScope(Owner.entries.firstOrNull { it.slug == rest })
}
