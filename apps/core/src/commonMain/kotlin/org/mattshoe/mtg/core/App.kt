package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray

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
    /** Where the card carousel is over an open deck. See [peekAt]. */
    val peek: Peek = Peek(),
    /**
     * Whose collection is on screen, as the owner's public key, once
     * `GET /c/:key` has said that key belongs to somebody.
     *
     * `Route.collection` is what the address says; this is the same
     * key after the server confirmed it. Neither is permission: the
     * account behind it is an id the app never holds, and a write is
     * decided by the session alone.
     *
     * Empty means nobody named a collection — see [viewing].
     */
    val resolvedCollection: String = "",
    /** Who is there, for the Admin Settings screen. */
    val people: People = People(),
    /** Every build that shipped, for the release notes on Admin Settings. */
    val releases: Releases = Releases(),
    /** Intake requests and where each one is, for Admin Settings. */
    val tasks: Tasks = Tasks(),
    /** The task being written on Admin Settings' New task page. */
    val newTask: NewTask = NewTask(),
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
            val key = from?.takeIf { it.view == View.DECKS }?.rest ?: return emptyList()
            if (key.isEmpty() || key != decks.openKey) return emptyList()
            return decks.pageOrder
        }

    /** Where this card sits in that run, or -1 if it is not in one. */
    val cardAt: Int
        get() = cardRef?.nameNorm?.let { norm -> deckRun.indexOfFirst { it.nameNorm == norm } } ?: -1

    val previousCard: DeckCard? get() = cardAt.takeIf { it > 0 }?.let { deckRun[it - 1] }
    val nextCard: DeckCard? get() = cardAt.takeIf { it in 0 until deckRun.size - 1 }?.let { deckRun[it + 1] }

    // ------------------------------------------------ whose collection

    /**
     * The collection on screen.
     *
     * The address wins, because a public collection having an address
     * is the whole point of it. Otherwise it is your own — which is
     * the bit that was missing, and why somebody signed in was being
     * shown everybody's decks at once.
     *
     * Empty for a visitor who has named nobody: every collection, the
     * way it has always been for somebody who has not said who they
     * are.
     */
    val viewing: String
        get() = resolvedCollection.ifEmpty { admin.account?.key.orEmpty() }

    /**
     * Where to send somebody who arrived without naming a collection.
     *
     * Their own, with its key in the address, so the link in the bar
     * is one they can copy and hand to somebody else. Null when there
     * is nothing to do: nobody signed in, or an address that already
     * names a collection — being bounced off a shared link onto your
     * own is exactly what makes a shared link worthless.
     */
    fun homeRoute(): Route? {
        if (route.collection.isNotEmpty()) return null
        val key = admin.account?.key?.takeIf { it.isNotEmpty() } ?: return null
        return route.copy(collection = key)
    }

    /**
     * The person whose page is open, when the address names one.
     *
     * `#/admin` is the list and `#/admin/<key>` is one account, the
     * same shape the decks page has. Matt: "tapping one needs to open
     * a user details page where i can assign roles".
     */
    val person: Person?
        get() = if (view != View.ADMIN || route.rest.isBlank()) null
        else people.rows.firstOrNull { it.key == route.rest }

    val writingTask: Boolean get() = false

    fun startingATask(): AppState = this

    fun taskSent(): AppState = this

    /** Look at somebody's collection. Theirs or anybody's. */
    fun browsing(key: String): AppState = copy(resolvedCollection = key)

    /**
     * Whether it is yet known whose collection this is.
     *
     * False for the moment between the page opening and the server
     * answering who this session is. Nothing collection-scoped is
     * fetched while it is false, and that is the fix for Matt: "why
     * are kaylas decks showing for me in the web app?!?!?!"
     *
     * The answer was not that the app asked the wrong question, it is
     * that it asked before there was an answer. An unanswered
     * `/auth/me` and "nobody is signed in" are the same empty owner,
     * every scoped query read an empty owner as "no WHERE clause",
     * and so the first load of the decks page was every deck in the
     * database — Kayla's included. On a slow answer that unscoped
     * result is the one that lands last and stays.
     *
     * A key in the address settles it without an account, because
     * that key names a collection outright: a link somebody sent
     * works for a stranger, which is the whole point of having one.
     */
    val collectionKnown: Boolean get() = admin.settled || resolvedCollection.isNotEmpty()

    /**
     * Which collection the stats are about: the one on screen.
     *
     * Not a choice. The page used to carry a Both / Matt / Kayla
     * switch, which was a list of the only two people there would
     * ever be and a third option nobody owns.
     *
     * A rest on a stats route still wins, because that is how the
     * switch used to spell itself into the address and somebody's
     * bookmark still says `#/stats/kayla`.
     */
    fun statsScope(): StatsScope =
        if (view == View.STATS && route.rest.isNotBlank()) Load.scopeFrom(route.rest)
        else StatsScope(viewing.takeIf { it.isNotEmpty() })

    /**
     * May you change what is on screen?
     *
     * About *this* collection and no other. It used to ask
     * `admin.unlocked` — "are you unlocked" — which a stored password
     * answered yes for everybody's cards, so the edit buttons were on
     * Kayla's decks too. The server never allowed the write; the app
     * was offering it.
     */
    val canEdit: Boolean get() = admin.account?.owns(viewing) == true

    /**
     * The Library, scoped to the collection being looked at.
     *
     * With no collection it keeps `both`, which is the deliberate
     * pooled read — the word, not an empty string. An empty owner is
     * "nobody said", and `conditions` refuses it rather than
     * quietly dropping the clause.
     */
    fun scopedLibrary(): Library =
        viewing.takeIf { it.isNotEmpty() }
            ?.let { library.copy(filters = library.filters.copy(owner = it)) }
            ?: library

    // --------------------------------------------------- the carousel

    /**
     * Where the carousel is, as a position in whichever run it is
     * over.
     *
     * A number and not the card itself. The sheet under the carousel
     * sets counts, swaps cards and removes them, so a held copy
     * would be stale the moment it was used and the carousel would
     * be showing a card the deck no longer has.
     */
    fun peekAt(index: Int, of: PeekOf = PeekOf.DECK): AppState {
        if (index !in runFor(of).indices) return this
        return opening(Overlay.CARD_PEEK).copy(peek = Peek(index, of))
    }

    /** The deck row that was tapped, by the card on it. */
    fun peekCard(card: DeckCard): AppState =
        peekAt(decks.pageOrder.indexOfFirst { it.nameNorm == card.nameNorm }, PeekOf.DECK)

    /** The Library tile that was tapped, by the row behind it. */
    fun peekRow(row: CardRow): AppState =
        peekAt(
            library.rows.indexOfFirst { it.owner == row.owner && it.nameNorm == row.nameNorm },
            PeekOf.LIBRARY,
        )

    /** A swipe. Clamped, because a carousel has two ends. */
    fun peekTo(index: Int): AppState {
        val run = runFor(peek.of)
        if (run.isEmpty() || !peek.open) return this
        return copy(peek = peek.copy(at = index.coerceIn(0, run.lastIndex)))
    }

    private fun runFor(of: PeekOf): List<DeckCard> = when (of) {
        PeekOf.DECK -> decks.pageOrder
        // Sized only; the Library's own rows are mapped in `peekRun`.
        PeekOf.LIBRARY -> library.rows.map { DeckCard(it.name, 0, null, 0, it.nameNorm) }
    }

    /** Every card the carousel can reach from here, in the order it draws them. */
    val peekRun: List<PeekCard>
        get() = when (peek.of) {
            PeekOf.DECK -> decks.pageOrder.map { card ->
                PeekCard(
                    title = card.shown,
                    nameNorm = card.nameNorm,
                    scryfallId = card.scryfallId,
                    typeLine = card.knownTypeLine,
                    printing = card.printing,
                    price = card.price,
                    tags = listOf(
                        PeekTag("${card.qty}× in deck"),
                        // The one bad fact a deck row can state: the
                        // deck wants more than the collection holds.
                        PeekTag("${card.owned} owned", bad = card.short > 0),
                        PeekTag.edhrec(card.edhrecRank),
                    ),
                    inDeck = card,
                )
            }

            PeekOf.LIBRARY -> library.rows.map { row ->
                PeekCard(
                    title = row.fullName,
                    nameNorm = row.nameNorm,
                    scryfallId = row.scryfallId,
                    typeLine = row.typeLine,
                    printing = row.printing,
                    price = row.price,
                    tags = listOfNotNull(
                        PeekTag("${row.qty} owned"),
                        row.free?.let { PeekTag("$it free") },
                        PeekTag.edhrec(row.edhrecRank),
                    ),
                )
            }
        }

    /**
     * The card under the carousel.
     *
     * Clamped rather than nulled when the run gets shorter: removing
     * the card you are looking at, or searching again under an open
     * carousel, should show whatever took its place. The only
     * alternative is a blank screen at the exact moment you pressed
     * something.
     */
    val peeked: PeekCard?
        get() {
            if (!peek.open) return null
            val run = peekRun
            return run.getOrNull(peek.at.coerceAtMost(run.lastIndex))
        }

    /** "7 of 99", under the carousel. */
    val peekPlace: String?
        get() = peeked?.let {
            val run = peekRun
            "${peek.at.coerceAtMost(run.lastIndex) + 1} of ${run.size}"
        }

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

    /**
     * The title, for a chrome that already names where you are.
     *
     * Android has a bottom bar now, and the bar prints the current
     * view's label four inches below the header — so every page read
     * its own name twice, "Library / Library". Matt: "Why THE FUCK
     * does it say library twice?!?!"
     *
     * A title that only repeats a tab gives way to the app's name. A
     * title the bar cannot say — which deck, which card, a screen
     * that is not in the bar at all — is still worth printing, and
     * still printed. Pass an empty bar and nothing changes, which is
     * the web: it shows no list of places, so it has nothing to
     * duplicate.
     */
    fun titleBeside(bar: List<View>): String =
        if (view in bar && title == view.label) Brand.NAME else title

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
        View.ADMIN -> copy(people = people.loading())
        View.CARD -> copy(card = card?.loading())
        View.ENTRY -> this
    }

    /** And the same screen, told why it has nothing to show. */
    fun fetchFailed(message: String, view: View = this.view): AppState = when (view) {
        View.LIBRARY -> copy(library = library.failed(message))
        View.DECKS -> copy(decks = decks.failed(message))
        View.STATS -> copy(stats = stats.failed(message))
        View.LOGS -> copy(logs = logs.failed(message))
        View.ADMIN -> copy(people = people.failed(message))
        View.CARD -> copy(card = card?.failed(message))
        View.ENTRY -> say(message, failed = true)
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

    /**
     * The entry wizard's "New deck", taking whatever is in the box
     * with it.
     *
     * Matt shared a list out of ManaBox to start a deck, picked "New
     * deck", and the wizard opened on an empty card list.
     */
    fun startingADeck(): AppState {
        val opened = opening(Overlay.NEW_DECK)
        return if (entry.list.isBlank() || opened.newDeck.list.isNotBlank()) opened
        else opened.copy(newDeck = opened.newDeck.type(entry.list))
    }

    /**
     * A file read off disk, into whichever box is on screen.
     *
     * Android had one picker for both and always answered into the
     * entry box, so a file picked on the deck wizard went behind it.
     */
    fun uploaded(names: List<String>, text: String): AppState {
        val landed = if (Overlay.NEW_DECK in overlays) {
            copy(newDeck = newDeck.type(Upload.merge(newDeck.list, text)))
        } else {
            copy(entry = entry.type(Upload.merge(entry.list, text)))
        }
        return landed.say(Upload.describe(names, text)).shareUsed()
    }

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
        // Inside the deck wizard, Back is a step first and a close
        // second — the same rule the entry wizard gets below, and
        // for the same reason: one press used to throw away every
        // answer in a seven-step flow.
        overlays.top == Overlay.NEW_DECK && newDeck.previousStep != null ->
            copy(newDeck = newDeck.goTo(newDeck.previousStep!!))
        overlays.any -> dismissTop()
        view == View.CARD -> leaveCard()
        // Back to the list as a route, not just by emptying `decks`.
        // The open deck is in the address — that is what lets a card
        // opened from it know where it came from — so closing it has
        // to put the address back too, or the screen says list and
        // the address still says deck.
        decks.openKey != null -> navigate(Route(View.DECKS))
        // Inside the entry wizard, Back is a step and not an exit.
        // It had never heard of the wizard, so the gesture went
        // straight from step four to the Library and whatever was
        // half-filled in went with it. `previousStep` is null on the
        // first question and on the receipt, which is how this falls
        // through to leaving the screen at the ends.
        view == View.ENTRY && entry.previousStep != null ->
            copy(entry = entry.goTo(entry.previousStep!!))
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
        Overlay.CARD_PEEK -> copy(peek = Peek())
        Overlay.CHEATSHEET -> this
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
        else copy(history = history.remember(EntryHistory.of(entry, now, viewing)))

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

    /** Every collection's, for a reader the server has said is nobody. */
    fun decks(): Sql = DeckQueries.all(DeckQueries.EVERY)

    /** The decks of one collection. */
    fun decks(owner: String): Sql = DeckQueries.all(owner)

    fun deck(key: String): Sql = DeckQueries.cards(key)

    fun stats(scope: StatsScope): Sql = StatsQueries.totals(scope)

    /**
     * Everything the card page shows: the card itself, then the
     * printings, the decks, the legality and the rulings.
     *
     * The face is first because it is the card. Every other query
     * here is about the collection's relationship to it, and for a
     * long time those four were the only ones, which is how both
     * platforms ended up with a card page that never said what the
     * card does.
     */
    fun card(nameNorm: String): List<Sql> = listOf(
        CardQueries.face(nameNorm),
        CardQueries.printings(nameNorm),
        CardQueries.usedIn(nameNorm),
        CardQueries.legalities(nameNorm),
        CardQueries.rulings(nameNorm),
        CardQueries.facts(nameNorm),
    )

    /**
     * The answers to [card], handed back in its order, as the page.
     *
     * Here rather than in each shell: the website and the phone each
     * picked the five answers apart themselves, and only the website
     * remembered to take the card's real name off its printings.
     */
    fun cardDetail(
        nameNorm: String,
        label: String,
        answers: List<Pair<List<String>, List<JsonArray>>>,
    ): CardDetail {
        val (f, p, u, l, r) = answers
        val owned = CardQueries.decodePrintings(p.first, p.second)
        return CardDetail(
            name = label,
            nameNorm = nameNorm,
            printings = owned,
            usedIn = CardQueries.decodeUses(u.first, u.second),
            legalities = CardQueries.decodeLegalities(l.first, l.second),
            rulings = CardQueries.decodeRulings(r.first, r.second),
            faces = CardQueries.decodeFaces(f.first, f.second),
            facts = answers.getOrNull(5)?.let { CardQueries.decodeFacts(it.first, it.second) } ?: CardFacts(),
        ).named(owned)
    }

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
    /**
     * What a route has to fetch before its page means anything.
     *
     * `collectionKnown` is the gate on the three scoped pages. There
     * is nothing for them to show until there is a collection to show
     * — see `AppState.collectionKnown` — and asking anyway is what
     * read every collection at once. A card is not a collection, so
     * its page still loads for a stranger following a link.
     */
    fun needs(route: Route, collectionKnown: Boolean = true): List<String> = when (route.view) {
        View.LIBRARY -> if (collectionKnown) listOf("cards", "count") else emptyList()
        View.DECKS -> when {
            !collectionKnown -> emptyList()
            route.rest.isEmpty() -> listOf("decks")
            else -> listOf("decks", "deck")
        }
        View.STATS -> if (collectionKnown) listOf("totals") else emptyList()
        View.LOGS -> listOf("logs")
        // Not collection-scoped: it is every account there is, which
        // is why only an admin can ask for it.
        View.ADMIN -> listOf("people")
        View.CARD -> listOf("card")
        View.ENTRY -> emptyList()
    }

    /**
     * Stats scoping used to live in the route — `#/stats/matt` — back
     * when the page had a switch to set it with. The page is one
     * collection's now, so the scope comes from which collection is
     * on screen; this stays only so an old bookmark still names one.
     */
    fun scopeFrom(rest: String): StatsScope = StatsScope(rest.takeIf { it.isNotBlank() })
}
