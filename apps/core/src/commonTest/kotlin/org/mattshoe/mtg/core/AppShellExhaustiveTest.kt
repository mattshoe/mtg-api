package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shell, exhaustively.
 *
 * `AppTest`, `ShellTest`, `ShellExtrasTest` and `DeckRunTest` walk the
 * happy path through routing, the lock and the overlay stack. What is
 * left is every view against every rule: which of them a lock bounces,
 * what a navigation resets and what it must not, which overlay forgets
 * what, and what a route spells for each of them. Written as loops over
 * `entries` wherever the rule is about all of them, so a view or an
 * overlay cannot be added without being covered.
 */

// ---------------------------------------------------------------- fixtures

private fun deckCard(name: String, type: String = "Creature", qty: Int = 1, role: String? = null) =
    DeckCard(
        name = name,
        qty = qty,
        role = role,
        owned = qty,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = null,
    )

private fun deck(slug: String, name: String = slug.replaceFirstChar(Char::uppercase)) =
    Deck(slug, name, "matt", commander = null, colors = null, bracket = null, artId = null)

/** Five creatures, already alphabetical, so page order is the obvious one. */
private val FIVE = listOf("Avacyn", "Bitterblossom", "Craterhoof", "Dragonlord", "Eidolon")

private fun fiveCardDeck(slug: String = "alela") = DecksState(
    decks = listOf(deck(slug)),
    openKey = slug,
    cards = FIVE.map { deckCard(it) },
)

private fun onDeck(slug: String = "alela", decks: DecksState = fiveCardDeck(slug)) =
    AppState(decks = decks).navigate(Route(View.DECKS, slug))

private val aFound = Found(id = 7, name = "Lightning Bolt", scryfallId = null, typeLine = null, qty = 1, owner = "matt")

/** Everything an overlay could be holding, all at once. */
private fun holding(): AppState = AppState(
    palette = PaletteState(term = "bolt", items = listOf(aFound), active = 0, open = true),
    newDeck = NewDeck(name = "Half typed"),
    deckEdit = DeckEditState(key = "alela", deckName = "Alela"),
    deckTweak = DeckTweak(key = "alela", deckName = "Alela"),
    disassemble = DisassembleState(key = "alela", deckName = "Alela"),
    rename = RenameState(key = "alela", was = "Alela"),
    peek = Peek(2),
)

/** Which field each overlay is the lid on. */
private val HOLDERS: Map<Overlay, (AppState) -> Boolean> = mapOf(
    Overlay.PALETTE to { s: AppState -> s.palette != PaletteState() },
    Overlay.DECK_EDIT to { s: AppState -> s.deckEdit != null },
    Overlay.DECK_TWEAK to { s: AppState -> s.deckTweak != null },
    Overlay.DISASSEMBLE to { s: AppState -> s.disassemble != null },
    Overlay.NEW_DECK to { s: AppState -> s.newDeck != NewDeck() },
    Overlay.RENAME to { s: AppState -> s.rename != null },
    Overlay.CARD_PEEK to { s: AppState -> s.peek != Peek() },
)

/** The two that are pure chrome. */
private val HOLDS_NOTHING = setOf(Overlay.CHEATSHEET)

private fun busyOn(s: AppState, v: View): Boolean = when (v) {
    View.LIBRARY -> s.library.busy
    View.DECKS -> s.decks.busy
    View.STATS -> s.stats.busy
    View.LOGS -> s.logs.busy
    View.ADMIN -> s.people.busy
    View.CARD -> s.card?.busy == true
    View.ENTRY -> s.entry.busy != null
}

private fun errorOn(s: AppState, v: View): String? = when (v) {
    View.LIBRARY -> s.library.error
    View.DECKS -> s.decks.error
    View.STATS -> s.stats.error
    View.LOGS -> s.logs.error
    View.ADMIN -> s.people.error
    View.CARD -> s.card?.error
    View.ENTRY -> s.entry.error
}

/** A state with something on every screen, so a reset shows up. */
private fun populated(): AppState = AppState(
    admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
    library = Library(filters = Filters(q = "bolt"), rows = emptyList(), total = 12),
    decks = fiveCardDeck(),
    stats = StatsState(scope = StatsScope("matt")),
    console = ConsoleState(sql = "select 1"),
    logs = LogsState(onlyErrors = true),
    entry = MassEntry(list = "1 Sol Ring", direction = Direction.ADD),
    history = EntryHistory(listOf(HistoryEntry("2026-01-01", "add", "matt", 1, "1 Sol Ring"))),
    complete = Completion(term = "bol"),
    facets = Facets(types = listOf("Creature")),
    card = CardDetail(name = "Bolt", nameNorm = "lightning bolt"),
    sharedList = "1 Sol Ring",
    from = Route(View.STATS, "matt"),
    toast = "something happened",
)

// ---------------------------------------------------------------- navigate

/** Where a tap lands, for every view, locked and unlocked. */
class NavigateLandingTest {

    @Test
    fun everyViewLandsOnItselfUnlocked() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach { assertEquals(it, s.navigate(it).view, it.slug) }
    }

    @Test
    fun everyGatedViewBouncesWhileLocked() {
        View.entries.filter { it.gated }.forEach {
            assertEquals(View.DEFAULT, AppState(admin = Admin().settle()).navigate(it).view, it.slug)
        }
    }

    @Test
    fun everyUngatedViewLandsOnItselfWhileLocked() {
        View.entries.filterNot { it.gated }.forEach {
            assertEquals(it, AppState().navigate(it).view, it.slug)
        }
    }

    @Test
    fun theRestSurvivesForEveryViewThatIsReachable() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach { assertEquals("thing", s.navigate(it, "thing").route.rest, it.slug) }
    }

    @Test
    fun andIsThrownAwayWithTheBounceForEveryGatedOne() {
        View.entries.filter { it.gated }.forEach {
            assertEquals("", AppState(admin = Admin().settle()).navigate(it, "thing").route.rest, it.slug)
        }
    }

    @Test
    fun aQueryStringRidesAlongForEveryReachableView() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach {
            assertEquals("x=1", s.navigate(Route(it, "", "x=1")).route.query, it.slug)
        }
    }

    @Test
    fun andSurvivesTheBounceForEveryGatedOne() {
        View.entries.filter { it.gated }.forEach {
            // Nothing typed is lost by the lock getting in the way.
            assertEquals("x=1", AppState().navigate(Route(it, "r", "x=1")).route.query, it.slug)
        }
    }

    @Test
    fun theTwoArgumentFormIsTheRouteForm() {
        View.entries.forEach {
            assertEquals(
                AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(Route(it, "r")),
                AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(it, "r"),
                it.slug,
            )
        }
    }

    @Test
    fun theRestDefaultsToNothing() {
        assertEquals("", AppState().navigate(View.DECKS).route.rest)
    }

    @Test
    fun navigatingToTheSamePlaceTwiceChangesNothingMore() {
        View.entries.forEach {
            val once = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(Route(it, "r", "x=1"))
            assertEquals(once, once.navigate(Route(it, "r", "x=1")), it.slug)
        }
    }

    @Test
    fun landingIsTheSameDecisionTheAdminWouldMake() {
        listOf(Admin(), Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), Admin()).forEach { admin ->
            View.entries.forEach { v ->
                val route = Route(v, "r", "x=1")
                assertEquals(admin.land(route), AppState(admin = admin).navigate(route).route, "$admin $v")
            }
        }
    }
}

/** What a navigation clears, and what it has no business touching. */
class NavigateResetsTest {

    @Test
    fun itClearsTheToastWhereverItGoes() {
        View.entries.forEach {
            assertNull(AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).say("done").navigate(it).toast, it.slug)
        }
    }

    @Test
    fun itClearsTheOpenCardWhereverItGoes() {
        View.entries.forEach {
            val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), card = CardDetail(name = "Bolt"))
            assertNull(s.navigate(it).card, it.slug)
        }
    }

    @Test
    fun itTakesEveryOverlayWithIt() {
        Overlay.entries.forEach { o ->
            View.entries.forEach { v ->
                val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).opening(o).navigate(v)
                assertFalse(s.overlays.any, "$o survived a trip to ${v.slug}")
                assertEquals(0, s.overlays.historyDepth)
            }
        }
    }

    @Test
    fun itTakesAWholeStackOfThemWithIt() {
        val s = Overlay.entries.fold(AppState()) { acc, o -> acc.opening(o) }
        assertEquals(Overlay.entries.size, s.overlays.stack.size)
        assertFalse(s.navigate(View.DECKS).overlays.any)
    }

    @Test
    fun itLeavesTheLockAlone() {
        View.entries.forEach {
            val s = populated().navigate(it)
            assertTrue(s.admin.unlocked, it.slug)
            assertEquals("t", s.admin.token)
        }
    }

    @Test
    fun itLeavesTheLibrarySearchAlone() {
        // Coming back to the Library should find the search still on it.
        View.entries.forEach {
            assertEquals("bolt", populated().navigate(it).library.filters.q, it.slug)
        }
    }

    @Test
    fun itLeavesTheHalfFinishedEntryAlone() {
        View.entries.forEach {
            val s = populated().navigate(it)
            assertEquals("1 Sol Ring", s.entry.list, it.slug)
            assertEquals(Direction.ADD, s.entry.direction)
        }
    }

    @Test
    fun itLeavesAnUnusedShareAlone() {
        View.entries.forEach {
            assertEquals("1 Sol Ring", populated().navigate(it).sharedList, it.slug)
        }
    }

    @Test
    fun itLeavesTheHistoryAlone() {
        View.entries.forEach {
            assertEquals(1, populated().navigate(it).history.entries.size, it.slug)
        }
    }

    @Test
    fun itLeavesTheFacetsAlone() {
        View.entries.forEach {
            assertEquals(listOf("Creature"), populated().navigate(it).facets.types, it.slug)
        }
    }

    @Test
    fun itLeavesTheConsoleAndTheLogSettingsAlone() {
        View.entries.forEach {
            val s = populated().navigate(it)
            assertEquals("select 1", s.console.sql, it.slug)
            assertTrue(s.logs.onlyErrors, it.slug)
        }
    }

    @Test
    fun itLeavesTheStatsScopeAlone() {
        View.entries.forEach {
            assertEquals("matt", populated().navigate(it).stats.scope.owner, it.slug)
        }
    }

    @Test
    fun itLeavesTheCardNameBoxAlone() {
        View.entries.forEach {
            assertEquals("bol", populated().navigate(it).complete.term, it.slug)
        }
    }

    @Test
    fun itLeavesWhereTheCardWasOpenedFromAlone() {
        // `openCard` is what decides that, not the route change.
        View.entries.forEach {
            assertEquals(Route(View.STATS, "matt"), populated().navigate(it).from, it.slug)
        }
    }
}

/** The open deck: kept where it is still on screen, closed where it is not. */
class NavigateAndTheOpenDeckTest {

    @Test
    fun aRouteThatNamesADeckKeepsIt() {
        val s = onDeck()
        assertEquals("alela", s.decks.openKey)
        assertEquals(5, s.decks.cards.size)
    }

    @Test
    fun theDeckListClosesIt() {
        val s = AppState(decks = fiveCardDeck()).navigate(View.DECKS)
        assertNull(s.decks.openKey, "the detail stayed on screen over an address that said list")
        assertTrue(s.decks.cards.isEmpty())
    }

    @Test
    fun aCardKeepsIt() {
        // A card is opened from a deck as often as from the Library, and
        // closing the deck here would re-read its cards on the way back.
        val s = onDeck().navigate(Route(View.CARD, "avacyn"))
        assertEquals("alela", s.decks.openKey)
        assertEquals(5, s.decks.cards.size)
    }

    @Test
    fun aCardKeepsItEvenWithNothingAfterTheSlash() {
        assertEquals("alela", onDeck().navigate(Route(View.CARD)).decks.openKey)
    }

    @Test
    fun everyOtherViewClosesIt() {
        val keeps = setOf(View.CARD)
        View.entries.filterNot { it in keeps }.forEach { v ->
            val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), decks = fiveCardDeck()).navigate(v)
            assertNull(s.decks.openKey, v.slug)
            assertTrue(s.decks.cards.isEmpty(), v.slug)
        }
    }

    @Test
    fun theDeckTilesThemselvesAreNeverThrownAway() {
        View.entries.forEach {
            val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), decks = fiveCardDeck()).navigate(it)
            assertEquals(1, s.decks.decks.size, it.slug)
        }
    }

    @Test
    fun aDeckRouteBouncedByTheLockWouldCloseIt() {
        // DECKS is not gated, so this is the shape of the rule rather
        // than a live case: it is `landed` that decides, not `to`.
        val s = AppState(admin = Admin().settle(), decks = fiveCardDeck()).navigate(Route(View.ENTRY, "alela"))
        assertEquals(View.LIBRARY, s.view)
        assertNull(s.decks.openKey)
    }

    @Test
    fun switchingDecksKeepsTheOldOneUntilTheNewOneArrives() {
        val s = onDeck().navigate(Route(View.DECKS, "boros"))
        assertEquals("alela", s.decks.openKey, "it threw the list away before the next one was read")
        assertEquals("boros", s.route.rest)
    }

    @Test
    fun closingTakesTheTokensWithTheCards() {
        val with = fiveCardDeck().withTokens(listOf())
        assertTrue(AppState(decks = with).navigate(View.STATS).decks.tokens.isEmpty())
    }
}

/**
 * A route change takes the overlays with it, so it has to take what they
 * were holding as well.
 */
class NavigateForgetsWhatItClosesTest {

    @Test
    fun aHalfTypedNewDeckDoesNotComeBackOnTheNextScreen() {
        val s = holding().opening(Overlay.NEW_DECK).navigate(View.DECKS)
        assertEquals(NewDeck(), s.newDeck, "the wizard reopened half filled in")
    }

    @Test
    fun theFinderDoesNotReopenShowingAStaleAnswer() {
        val s = holding().opening(Overlay.PALETTE).navigate(View.DECKS)
        assertEquals(PaletteState(), s.palette, "`/` showed rows answering a search from two screens ago")
    }

    @Test
    fun aDeckEditDoesNotSurviveTheTrip() {
        assertNull(holding().opening(Overlay.DECK_EDIT).navigate(View.STATS).deckEdit)
    }

    @Test
    fun norDoesATweak() {
        assertNull(holding().opening(Overlay.DECK_TWEAK).navigate(View.STATS).deckTweak)
    }

    @Test
    fun norDoesADisassembleConfirmation() {
        assertNull(holding().opening(Overlay.DISASSEMBLE).navigate(View.STATS).disassemble)
    }

    @Test
    fun norDoesARename() {
        assertNull(holding().opening(Overlay.RENAME).navigate(View.STATS).rename)
    }

    @Test
    fun everyOverlayLetsGoOnTheWayOut() {
        HOLDERS.forEach { (o, holds) ->
            val s = holding().opening(o).navigate(View.DECKS)
            assertFalse(holds(s), "$o kept what it was holding through a navigation")
        }
    }

    @Test
    fun andLetsGoOfNothingElse() {
        HOLDERS.forEach { (o, _) ->
            val s = holding().opening(o).navigate(View.DECKS)
            (HOLDERS.keys - o).forEach { other ->
                assertTrue(HOLDERS[other]!!(s), "closing $o also emptied $other")
            }
        }
    }

    @Test
    fun theChromeOnlyOverlaysChangeNothingOnTheWayOut() {
        HOLDS_NOTHING.forEach { o ->
            val before = holding()
            val after = before.opening(o).navigate(View.LIBRARY)
            assertEquals(before.palette, after.palette, o.name)
            assertEquals(before.newDeck, after.newDeck, o.name)
            assertEquals(before.deckEdit, after.deckEdit, o.name)
            assertEquals(before.deckTweak, after.deckTweak, o.name)
            assertEquals(before.disassemble, after.disassemble, o.name)
            assertEquals(before.rename, after.rename, o.name)
        }
    }

    @Test
    fun whatNoOverlayWasShowingIsNotThrownAway() {
        // Only what was open is let go of. A navigation is not a reset.
        val s = holding().navigate(View.DECKS)
        assertEquals(holding().palette, s.palette)
        assertEquals(holding().newDeck, s.newDeck)
        assertNotNull(s.deckEdit)
        assertNotNull(s.rename)
    }
}

// -------------------------------------------------------------------- hash

/** Where the address bar should point. */
class HashTest {

    @Test
    fun theLibraryWritesItsFiltersRatherThanItsRoute() {
        val s = AppState().navigate(View.LIBRARY).copy(library = Library(filters = Filters(q = "bolt")))
        assertEquals(FilterUrl.toHash(Filters(q = "bolt")), s.hash())
        assertEquals("#/search?q=bolt", s.hash())
    }

    @Test
    fun aLibraryWithNothingNarrowedIsABareSearch() {
        assertEquals("#/search", AppState().hash())
    }

    @Test
    fun andTheRoutesOwnQueryStringIsNotWhatItWrites() {
        // The filters are the truth for that screen; the route's query
        // is only how it arrived.
        val s = AppState().navigate(Route(View.LIBRARY, "", "q=stale"))
        assertEquals("#/search", s.hash())
    }

    @Test
    fun norIsAnythingAfterTheSlash() {
        assertEquals("#/search", AppState().navigate(Route(View.LIBRARY, "junk")).hash())
    }

    @Test
    fun everyOtherViewWritesItsRoute() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.filterNot { it == View.LIBRARY }.forEach {
            val at = s.navigate(Route(it, "r", "x=1"))
            assertEquals(at.route.toHash(), at.hash(), it.slug)
        }
    }

    @Test
    fun aDeckIsItsSlug() {
        assertEquals("#/decks/alela", onDeck().hash())
    }

    @Test
    fun aCardIsItsName() {
        assertEquals("#/card/sol+ring", AppState().openCard(CardRef("sol ring")).hash())
    }

    @Test
    fun statsCarriesItsScope() {
        assertEquals("#/stats/matt", AppState().navigate(View.STATS, "matt").hash())
    }

    @Test
    fun aGatedViewBouncedWhileLockedWritesWhereItActuallyLanded() {
        assertEquals(
            "#/search",
            AppState(admin = Admin().settle()).navigate(Route(View.ENTRY, "", "x=1")).hash(),
        )
    }

    @Test
    fun theHashRoundTripsBackToTheSameViewForEveryScreen() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach {
            assertEquals(it, Route.parse(s.navigate(it).hash()).view, it.slug)
        }
    }

    @Test
    fun aFilteredSearchRoundTripsThroughTheHash() {
        val f = Filters(q = "bolt", page = 3, sort = Sort.NAME, descending = false)
        val s = AppState().copy(library = Library(filters = f))
        assertEquals(f, FilterUrl.fromHash(s.hash().substringAfter('?', "")))
    }
}

// ------------------------------------------------------------------- steps

/** Which changes the address bar should gain an entry for. */
class IsAStepFromTest {

    @Test
    fun nowhereIsAStepFromItself() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach {
            val at = s.navigate(Route(it, "r", "x=1"))
            assertFalse(at.isAStepFrom(at), it.slug)
        }
    }

    @Test
    fun everyOtherViewIsAStepFromEveryView() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach { a ->
            View.entries.filterNot { it == a }.forEach { b ->
                assertTrue(s.navigate(b).isAStepFrom(s.navigate(a)), "${a.slug} to ${b.slug}")
            }
        }
    }

    @Test
    fun andItReadsTheSameBothWays() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.forEach { a ->
            View.entries.forEach { b ->
                assertEquals(
                    s.navigate(b).isAStepFrom(s.navigate(a)),
                    s.navigate(a).isAStepFrom(s.navigate(b)),
                    "${a.slug} / ${b.slug}",
                )
            }
        }
    }

    @Test
    fun anotherDeckIsAStep() {
        assertTrue(onDeck("boros").isAStepFrom(onDeck("alela")))
    }

    @Test
    fun theDeckListIsAStepFromADeck() {
        val list = AppState().navigate(View.DECKS)
        assertTrue(list.isAStepFrom(onDeck()))
        assertTrue(onDeck().isAStepFrom(list))
    }

    @Test
    fun anotherCardIsAStep() {
        val a = AppState().openCard(CardRef("sol ring"))
        assertTrue(a.openCard(CardRef("mana crypt")).isAStepFrom(a))
    }

    @Test
    fun theSameCardAgainIsNot() {
        val a = AppState().openCard(CardRef("sol ring"))
        assertFalse(a.openCard(CardRef("sol ring")).isAStepFrom(a))
    }

    @Test
    fun aQueryStringChangingIsNotAStep() {
        // A filter changes on every keystroke. The address is rewritten,
        // not added to.
        val was = AppState().navigate(Route(View.LIBRARY, "", "q=b"))
        assertFalse(AppState().navigate(Route(View.LIBRARY, "", "q=bo")).isAStepFrom(was))
    }

    @Test
    fun aFilterChangingIsNotAStep() {
        val was = AppState().copy(library = Library(filters = Filters(q = "b")))
        val now = was.copy(library = was.library.where(Filters(q = "bolt")))
        assertFalse(now.isAStepFrom(was))
    }

    @Test
    fun aToastIsNotAStep() {
        val was = AppState().navigate(View.STATS)
        assertFalse(was.say("done").isAStepFrom(was))
    }

    @Test
    fun anOverlayOpeningIsNotAStep() {
        val was = AppState().navigate(View.STATS)
        Overlay.entries.forEach { assertFalse(was.opening(it).isAStepFrom(was), it.name) }
    }

    @Test
    fun aDeckLoadingIsNotAStep() {
        val was = onDeck()
        assertFalse(was.copy(decks = was.decks.loading()).isAStepFrom(was))
    }

    @Test
    fun openingACardIsAStep() {
        val was = AppState().navigate(View.LIBRARY)
        assertTrue(was.openCard(CardRef("sol ring")).isAStepFrom(was))
    }

    @Test
    fun andLeavingItIsOneToo() {
        val card = onDeck().openCard(CardRef("avacyn"))
        assertTrue(card.leaveCard().isAStepFrom(card))
    }

    @Test
    fun aBouncedGatedRouteIsNoStepAtAllFromTheDefault() {
        val was = AppState(admin = Admin().settle()).navigate(View.LIBRARY)
        assertFalse(was.navigate(View.ENTRY).isAStepFrom(was), "a lock bounce added a history entry")
    }

    @Test
    fun theSameDeckTwiceIsNot() {
        assertFalse(onDeck().isAStepFrom(onDeck()))
    }
}

// ----------------------------------------------------------------- cardRef

/** Which card the address names. */
class CardRefFromTheRouteTest {

    @Test
    fun nothingButACardRouteNamesACard() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.filterNot { it == View.CARD }.forEach {
            assertNull(s.navigate(it, "sol+ring").cardRef, it.slug)
        }
    }

    @Test
    fun aCardRouteNamesOne() {
        assertEquals(CardRef("sol ring"), AppState().navigate(View.CARD, "sol+ring").cardRef)
    }

    @Test
    fun aCardRouteWithNothingAfterItNamesNothing() {
        assertNull(AppState().navigate(View.CARD).cardRef)
    }

    @Test
    fun norDoesOneThatIsAllSpaces() {
        assertNull(AppState().navigate(View.CARD, "+++").cardRef)
        assertNull(AppState().navigate(View.CARD, "%20").cardRef)
    }

    @Test
    fun aSlashInTheNameSurvives() {
        assertEquals(CardRef("a/b"), AppState().navigate(View.CARD, "a%2Fb").cardRef)
    }

    @Test
    fun theQueryStringIsNotPartOfTheName() {
        assertEquals(
            CardRef("sol ring"),
            AppState().navigate(Route(View.CARD, "sol+ring", "x=1")).cardRef,
        )
    }

    @Test
    fun everyAwkwardNameRoundTripsThroughTheAddress() {
        listOf(
            "sol ring",
            "a/b",
            "jace, the mind sculptor",
            "ach! hans, run!",
            "borrowing 100,000 arrows",
            "kongming, \"sleeping dragon\"",
            "lim-dûl's vault",
            "æther vial",
            "plus+sign",
            "percent%sign",
            "hash#sign",
            "amp&sign",
            "eq=sign",
            "question?mark",
        ).forEach { name ->
            val s = AppState().openCard(CardRef(name))
            assertEquals(CardRef(name), s.cardRef, name)
            assertEquals(CardRef(name), Route.parse(s.hash()).let { CardRef.parse(it.rest) }, name)
        }
    }

    @Test
    fun theNameIsTakenLiterallyRatherThanLowercased() {
        assertEquals(CardRef("Sol Ring"), AppState().openCard(CardRef("Sol Ring")).cardRef)
    }
}

// ---------------------------------------------------------------- openCard

/** Opening a card, and what back remembers. */
class OpenCardTest {

    @Test
    fun itIsAnOrdinaryNavigationToTheCardsOwnAddress() {
        val s = AppState().openCard(CardRef("sol ring"))
        assertEquals(View.CARD, s.view)
        assertEquals("sol+ring", s.route.rest)
        assertEquals("", s.route.query, "the link is the card alone")
    }

    @Test
    fun theDrawerStartsLoading() {
        val s = AppState().openCard(CardRef("sol ring"))
        assertNotNull(s.card)
        assertTrue(s.card!!.busy)
        assertNull(s.card!!.error)
        assertEquals("sol ring", s.card!!.nameNorm)
    }

    @Test
    fun theNameDefaultsToTheNormalisedOne() {
        assertEquals("sol ring", AppState().openCard(CardRef("sol ring")).card?.name)
    }

    @Test
    fun andIsTakenWhenTheCallerKnowsTheRealSpelling() {
        val s = AppState().openCard(CardRef("lim-dûl's vault"), "Lim-Dûl's Vault")
        assertEquals("Lim-Dûl's Vault", s.card?.name)
        assertEquals("lim-dûl's vault", s.card?.nameNorm)
    }

    @Test
    fun fromTheLibraryBackGoesToTheLibrary() {
        val s = AppState().navigate(View.LIBRARY).openCard(CardRef("sol ring"))
        assertEquals(Route(View.LIBRARY), s.from)
    }

    @Test
    fun fromADeckBackGoesToTheDeck() {
        assertEquals(Route(View.DECKS, "alela"), onDeck().openCard(CardRef("avacyn")).from)
    }

    @Test
    fun fromEveryOtherScreenBackGoesToThatScreen() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.filterNot { it == View.CARD }.forEach {
            val at = s.navigate(Route(it, "r"))
            assertEquals(at.route, at.openCard(CardRef("sol ring")).from, it.slug)
        }
    }

    @Test
    fun fromALinkWithNothingBehindItThereIsNowhereToGoBackTo() {
        val cold = AppState(route = Route.parse("#/card/sol+ring"))
        assertNull(cold.from)
        assertNull(cold.openCard(CardRef("mana crypt")).from, "it invented somewhere to go back to")
    }

    @Test
    fun cardToCardRemembersWhereTheRunStartedRatherThanTheLastCard() {
        var s = onDeck().openCard(CardRef("avacyn"))
        listOf("bitterblossom", "craterhoof", "dragonlord", "eidolon").forEach {
            s = s.openCard(CardRef(it))
        }
        assertEquals(Route(View.DECKS, "alela"), s.from, "one step along the run left back with nowhere to go")
    }

    @Test
    fun andSoDoesALongChainFromTheLibrary() {
        var s = AppState().navigate(View.LIBRARY).openCard(CardRef("a"))
        repeat(20) { s = s.openCard(CardRef("card$it")) }
        assertEquals(Route(View.LIBRARY), s.from)
    }

    @Test
    fun theRunStartKeepsItsQueryString() {
        val s = AppState().navigate(Route(View.STATS, "matt", "x=1")).openCard(CardRef("sol ring"))
        assertEquals(Route(View.STATS, "matt", "x=1"), s.from)
    }

    @Test
    fun openingTheSameCardAgainDoesNotMoveTheRunStart() {
        val s = onDeck().openCard(CardRef("avacyn"))
        assertEquals(Route(View.DECKS, "alela"), s.openCard(CardRef("avacyn")).from)
    }

    @Test
    fun itClearsAStaleToast() {
        assertNull(AppState().say("done").openCard(CardRef("sol ring")).toast)
    }

    @Test
    fun itTakesEveryOverlayWithIt() {
        Overlay.entries.forEach { o ->
            assertFalse(AppState().opening(o).openCard(CardRef("sol ring")).overlays.any, o.name)
        }
    }

    @Test
    fun andLetsGoOfWhatTheyWereHolding() {
        HOLDERS.forEach { (o, holds) ->
            assertFalse(holds(holding().opening(o).openCard(CardRef("sol ring"))), o.name)
        }
    }

    @Test
    fun itKeepsTheDeckUnderneathLoaded() {
        val s = onDeck().openCard(CardRef("avacyn"))
        assertEquals("alela", s.decks.openKey)
        assertEquals(5, s.decks.cards.size)
    }

    @Test
    fun theOldCardIsReplacedRatherThanAddedTo() {
        val s = AppState().openCard(CardRef("sol ring")).let { first ->
            first.copy(card = first.card!!.copy(busy = false, printings = emptyList()))
        }
        val next = s.openCard(CardRef("mana crypt"))
        assertEquals("mana crypt", next.card?.nameNorm)
        assertTrue(next.card!!.busy)
    }

    @Test
    fun itKeepsTheLibrarySearchItWasOpenedOutOf() {
        val s = AppState().copy(library = Library(filters = Filters(q = "bolt")))
        assertEquals("bolt", s.openCard(CardRef("sol ring")).library.filters.q)
    }

    @Test
    fun aCardIsReachableEvenWhileLocked() {
        // It is not a gated view, and a link to one has to work for
        // anybody it is sent to.
        assertEquals(View.CARD, AppState().openCard(CardRef("sol ring")).view)
    }

    @Test
    fun openingACardFromAGatedScreenRemembersThatScreen() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(View.ENTRY).openCard(CardRef("sol ring"))
        assertEquals(Route(View.ENTRY), s.from)
    }
}

// --------------------------------------------------------------- leaveCard

class LeaveCardTest {

    @Test
    fun withNothingBehindItTheBackButtonGoesToTheLibrary() {
        val cold = AppState(route = Route.parse("#/card/sol+ring"))
        assertEquals(View.DEFAULT, cold.leaveCard().view)
        assertEquals("", cold.leaveCard().route.rest)
    }

    @Test
    fun fromADeckItGoesBackToTheDeckWithItsCardsStillRead() {
        val s = onDeck().openCard(CardRef("avacyn")).leaveCard()
        assertEquals(Route(View.DECKS, "alela"), s.route)
        assertEquals(5, s.decks.cards.size, "it threw the deck away and would re-read it")
    }

    @Test
    fun fromEveryScreenItGoesBackToThatScreen() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.filterNot { it == View.CARD }.forEach {
            val at = s.navigate(Route(it, "r"))
            assertEquals(at.route, at.openCard(CardRef("sol ring")).leaveCard().route, it.slug)
        }
    }

    @Test
    fun itClosesTheCard() {
        assertNull(onDeck().openCard(CardRef("avacyn")).leaveCard().card)
    }

    @Test
    fun itClearsTheToastAndTheOverlays() {
        val s = onDeck().openCard(CardRef("avacyn")).say("saved").opening(Overlay.CHEATSHEET).leaveCard()
        assertNull(s.toast)
        assertFalse(s.overlays.any)
    }

    @Test
    fun aRunGoesBackToWhereItStarted() {
        var s = onDeck().openCard(CardRef("avacyn"))
        listOf("bitterblossom", "craterhoof").forEach { s = s.openCard(CardRef(it)) }
        assertEquals(Route(View.DECKS, "alela"), s.leaveCard().route)
    }

    @Test
    fun goingBackToAScreenTheLockHasSinceClosedBounces() {
        val open = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(View.ENTRY).openCard(CardRef("sol ring"))
        val locked = open.copy(admin = open.admin.signOut())
        assertEquals(View.DEFAULT, locked.leaveCard().view, "it rendered a shell that cannot do anything")
    }

    @Test
    fun theLibrarySearchIsStillThereWhenYouGetBack() {
        val s = AppState()
            .copy(library = Library(filters = Filters(q = "bolt"), total = 99, loadedFor = Filters(q = "bolt")))
            .navigate(View.LIBRARY)
            .openCard(CardRef("sol ring"))
            .leaveCard()
        assertEquals("bolt", s.library.filters.q)
        assertTrue(s.library.fresh, "coming back re-ran the whole search")
    }

    @Test
    fun leavingACardYouNeverOpenedIsStillHarmless() {
        assertEquals(View.DEFAULT, AppState().leaveCard().view)
    }
}

// ----------------------------------------------------------------- deckRun

class DeckRunEdgeTest {

    private fun atCard(norm: String, decks: DecksState = fiveCardDeck()) =
        onDeck("alela", decks).openCard(CardRef(norm))

    @Test
    fun theRunIsThePageOrder() {
        assertEquals(FIVE, atCard("avacyn").deckRun.map { it.name })
    }

    @Test
    fun everyPositionKnowsWhereItIs() {
        FIVE.forEachIndexed { i, name ->
            val s = atCard(name.lowercase())
            assertEquals(i, s.cardAt, name)
            assertEquals("${i + 1} of 5", s.cardPlace, name)
            assertEquals(FIVE.getOrNull(i - 1), s.previousCard?.name, name)
            assertEquals(FIVE.getOrNull(i + 1), s.nextCard?.name, name)
        }
    }

    @Test
    fun theFirstHasNothingBeforeIt() {
        assertNull(atCard("avacyn").previousCard)
    }

    @Test
    fun theLastHasNothingAfterIt() {
        assertNull(atCard("eidolon").nextCard)
    }

    @Test
    fun aOneCardDeckIsBothEndsAtOnce() {
        val one = DecksState(decks = listOf(deck("alela")), openKey = "alela", cards = listOf(deckCard("Avacyn")))
        val s = atCard("avacyn", one)
        assertEquals("1 of 1", s.cardPlace)
        assertNull(s.previousCard)
        assertNull(s.nextCard)
    }

    @Test
    fun anEmptyDeckIsNoRunAtAll() {
        val none = DecksState(decks = listOf(deck("alela")), openKey = "alela", cards = emptyList())
        val s = atCard("avacyn", none)
        assertTrue(s.deckRun.isEmpty())
        assertEquals(-1, s.cardAt)
        assertNull(s.cardPlace)
        assertNull(s.previousCard)
        assertNull(s.nextCard)
    }

    @Test
    fun aCardTheDeckDoesNotHoldSitsNowhereInIt() {
        val s = atCard("sol ring")
        assertEquals(5, s.deckRun.size)
        assertEquals(-1, s.cardAt)
        assertNull(s.cardPlace)
        assertNull(s.previousCard)
        assertNull(s.nextCard)
    }

    @Test
    fun aDeckThatIsNoLongerOpenOffersNoNeighbours() {
        val s = atCard("avacyn", fiveCardDeck().copy(openKey = null))
        assertTrue(s.deckRun.isEmpty())
        assertNull(s.nextCard)
    }

    @Test
    fun norDoesOneWhoseSlugDoesNotMatchTheRouteItWasOpenedFrom() {
        val s = atCard("avacyn", fiveCardDeck().copy(openKey = "boros"))
        assertTrue(s.deckRun.isEmpty(), "it offered neighbours out of a deck that is not open")
    }

    @Test
    fun theDeckListIsNotADeck() {
        val s = AppState(decks = fiveCardDeck()).navigate(View.DECKS).openCard(CardRef("avacyn"))
        assertTrue(s.deckRun.isEmpty())
        assertNull(s.cardPlace)
    }

    @Test
    fun aCardOpenedFromAnyOtherScreenBelongsToNoRun() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), decks = fiveCardDeck())
        View.entries.filterNot { it == View.DECKS }.forEach { v ->
            val at = s.navigate(Route(v, "alela")).openCard(CardRef("avacyn"))
            assertTrue(at.deckRun.isEmpty(), v.slug)
            assertNull(at.cardPlace, v.slug)
        }
    }

    @Test
    fun aCardOpenedFromALinkBelongsToNoRun() {
        val cold = AppState(route = Route.parse("#/card/avacyn"), decks = fiveCardDeck())
        assertNull(cold.from)
        assertTrue(cold.deckRun.isEmpty())
        assertEquals(-1, cold.cardAt)
    }

    @Test
    fun nothingOffACardPageHasAPlace() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), decks = fiveCardDeck(), from = Route(View.DECKS, "alela"))
        View.entries.filterNot { it == View.CARD }.forEach { v ->
            val at = s.navigate(Route(v, if (v == View.DECKS) "alela" else ""))
            assertEquals(-1, at.cardAt, v.slug)
            assertNull(at.cardPlace, v.slug)
            assertNull(at.previousCard, v.slug)
            assertNull(at.nextCard, v.slug)
        }
    }

    @Test
    fun walkingForwardReachesTheEndAndStops() {
        var s = atCard("avacyn")
        val seen = mutableListOf(s.cardRef!!.nameNorm)
        while (s.nextCard != null) {
            s = s.openCard(CardRef(s.nextCard!!.nameNorm))
            seen += s.cardRef!!.nameNorm
        }
        assertEquals(FIVE.map { it.lowercase() }, seen)
        assertEquals("5 of 5", s.cardPlace)
    }

    @Test
    fun andWalkingBackwardReachesTheStart() {
        var s = atCard("eidolon")
        val seen = mutableListOf(s.cardRef!!.nameNorm)
        while (s.previousCard != null) {
            s = s.openCard(CardRef(s.previousCard!!.nameNorm))
            seen += s.cardRef!!.nameNorm
        }
        assertEquals(FIVE.map { it.lowercase() }.reversed(), seen)
        assertEquals("1 of 5", s.cardPlace)
    }

    @Test
    fun theRunFollowsTheSectionsRatherThanTheRowsThatArrived() {
        val mixed = DecksState(
            decks = listOf(deck("alela")),
            openKey = "alela",
            cards = listOf(
                deckCard("Command Tower", "Land"),
                deckCard("Zealous Conscripts"),
                deckCard("Arcane Signet", "Artifact"),
                deckCard("Alela", "Legendary Creature", role = "commander"),
            ),
        )
        assertEquals(
            listOf("Alela", "Zealous Conscripts", "Arcane Signet", "Command Tower"),
            atCard("alela", mixed).deckRun.map { it.name },
        )
        assertEquals("1 of 4", atCard("alela", mixed).cardPlace)
    }

    @Test
    fun aNameInTheDeckTwiceTakesTheFirstOne() {
        val twice = DecksState(
            decks = listOf(deck("alela")),
            openKey = "alela",
            cards = listOf(deckCard("Avacyn"), deckCard("Avacyn"), deckCard("Bitterblossom")),
        )
        assertEquals(0, atCard("avacyn", twice).cardAt)
        assertEquals("1 of 3", atCard("avacyn", twice).cardPlace)
    }

    @Test
    fun theRunIsKeptAcrossAWholeWalkRatherThanRebuiltFromTheLastCard() {
        var s = atCard("avacyn")
        repeat(4) { s = s.openCard(CardRef(s.nextCard!!.nameNorm)) }
        assertEquals(5, s.deckRun.size)
        assertEquals(Route(View.DECKS, "alela"), s.from)
    }

    @Test
    fun comingBackFromARunAndOpeningAnotherCardStartsAFreshRun() {
        var s = atCard("avacyn")
        s = s.openCard(CardRef(s.nextCard!!.nameNorm))
        val back = s.leaveCard()
        assertEquals(Route(View.DECKS, "alela"), back.route)
        assertEquals(0, back.openCard(CardRef("avacyn")).cardAt)
    }
}

// ---------------------------------------------------------------- overlays

/** The stack itself, with no app around it. */
class OverlaysStackTest {

    @Test
    fun anEmptyStackHasNoTopAndNoDepth() {
        val o = Overlays()
        assertNull(o.top)
        assertFalse(o.any)
        assertEquals(0, o.historyDepth)
        assertTrue(o.stack.isEmpty())
    }

    @Test
    fun anEmptyStackContainsNothing() {
        Overlay.entries.forEach { assertFalse(it in Overlays(), it.name) }
    }

    @Test
    fun openingAnyOneOfThemPutsItOnTop() {
        Overlay.entries.forEach {
            val o = Overlays().open(it)
            assertEquals(it, o.top, it.name)
            assertTrue(o.any)
            assertEquals(1, o.historyDepth)
            assertTrue(it in o)
        }
    }

    @Test
    fun andLeavesEveryOtherOneOut() {
        Overlay.entries.forEach { open ->
            val o = Overlays().open(open)
            (Overlay.entries - open).forEach { assertFalse(it in o, "$open / $it") }
        }
    }

    @Test
    fun openingTheSameOneTwiceDoesNotStackItTwice() {
        Overlay.entries.forEach {
            val o = Overlays().open(it).open(it)
            assertEquals(1, o.stack.size, it.name)
            assertEquals(listOf(it), o.stack)
        }
    }

    @Test
    fun andDoesNotMoveItBackToTheTop() {
        val o = Overlays().open(Overlay.PALETTE).open(Overlay.CHEATSHEET).open(Overlay.PALETTE)
        assertEquals(listOf(Overlay.PALETTE, Overlay.CHEATSHEET), o.stack)
        assertEquals(Overlay.CHEATSHEET, o.top)
    }

    @Test
    fun allOfThemCanBeUpAtOnce() {
        val o = Overlay.entries.fold(Overlays()) { acc, it -> acc.open(it) }
        assertEquals(Overlay.entries.toList(), o.stack, "the stack is outermost first")
        assertEquals(Overlay.entries.size, o.historyDepth)
        assertEquals(Overlay.entries.last(), o.top)
    }

    @Test
    fun theDepthIsTheSizeAtEveryStepUp() {
        var o = Overlays()
        Overlay.entries.forEachIndexed { i, it ->
            o = o.open(it)
            assertEquals(i + 1, o.historyDepth, it.name)
            assertEquals(o.stack.size, o.historyDepth)
        }
    }

    @Test
    fun backTakesTheTopOneOff() {
        val o = Overlays().open(Overlay.PALETTE).open(Overlay.CHEATSHEET).pop()
        assertEquals(listOf(Overlay.PALETTE), o.stack)
    }

    @Test
    fun backOnAnEmptyStackIsTheSameEmptyStack() {
        val o = Overlays()
        assertSame(o, o.pop())
    }

    @Test
    fun popTakesThemAllOffOneAtATime() {
        var o = Overlay.entries.fold(Overlays()) { acc, it -> acc.open(it) }
        Overlay.entries.reversed().forEach {
            assertEquals(it, o.top, it.name)
            o = o.pop()
        }
        assertFalse(o.any)
    }

    @Test
    fun closingByIdentityWorksForEveryOneOfThem() {
        Overlay.entries.forEach {
            val o = Overlay.entries.fold(Overlays()) { acc, x -> acc.open(x) }.close(it)
            assertFalse(it in o, it.name)
            assertEquals(Overlay.entries.size - 1, o.stack.size)
            (Overlay.entries - it).forEach { other -> assertTrue(other in o, "$it took $other with it") }
        }
    }

    @Test
    fun closingOneUnderneathLeavesTheTopAlone() {
        val o = Overlays().open(Overlay.PALETTE).open(Overlay.CHEATSHEET).close(Overlay.PALETTE)
        assertEquals(listOf(Overlay.CHEATSHEET), o.stack)
        assertEquals(Overlay.CHEATSHEET, o.top)
    }

    @Test
    fun closingOneThatIsNotUpChangesNothing() {
        val o = Overlays().open(Overlay.PALETTE)
        (Overlay.entries - Overlay.PALETTE).forEach {
            assertEquals(listOf(Overlay.PALETTE), o.close(it).stack, it.name)
        }
    }

    @Test
    fun closingOnAnEmptyStackIsStillEmpty() {
        Overlay.entries.forEach { assertEquals(Overlays(), Overlays().close(it), it.name) }
    }

    @Test
    fun aRouteChangeTakesThemAll() {
        assertEquals(Overlays(), Overlay.entries.fold(Overlays()) { acc, it -> acc.open(it) }.clear())
    }

    @Test
    fun clearingAnEmptyStackIsHarmless() {
        assertEquals(Overlays(), Overlays().clear())
    }

    @Test
    fun theStackKeepsTheOrderTheyWereOpenedIn() {
        val order = listOf(Overlay.RENAME, Overlay.PALETTE, Overlay.CHEATSHEET)
        assertEquals(order, order.fold(Overlays()) { acc, it -> acc.open(it) }.stack)
    }

    @Test
    fun openCloseOpenPutsItBackOnTop() {
        val o = Overlays().open(Overlay.PALETTE).open(Overlay.CHEATSHEET)
            .close(Overlay.PALETTE).open(Overlay.PALETTE)
        assertEquals(listOf(Overlay.CHEATSHEET, Overlay.PALETTE), o.stack)
    }

    @Test
    fun thereAreEightOfThemAndEachIsAccountedFor() {
        // Eight since UNLOCK went: a password box, for a shared
        // secret that accounts replaced.
        assertEquals(8, Overlay.entries.size)
        Overlay.entries.forEach {
            assertTrue(it in HOLDERS || it in HOLDS_NOTHING, "${it.name} is not covered by the forget tests")
        }
    }
}

/** What closing one throws away, and what it must leave alone. */
class OverlayForgetsTest {

    @Test
    fun closingTheFinderEmptiesIt() {
        val s = holding().opening(Overlay.PALETTE).closing(Overlay.PALETTE)
        assertEquals(PaletteState(), s.palette, "it reopened showing a stale answer")
        assertFalse(s.palette.open)
        assertEquals("", s.palette.term)
        assertTrue(s.palette.items.isEmpty())
    }

    @Test
    fun closingTheDeckEditorThrowsTheEditAway() {
        assertNull(holding().opening(Overlay.DECK_EDIT).closing(Overlay.DECK_EDIT).deckEdit)
    }

    @Test
    fun closingTheTweakSheetThrowsTheTweakAway() {
        assertNull(holding().opening(Overlay.DECK_TWEAK).closing(Overlay.DECK_TWEAK).deckTweak)
    }

    @Test
    fun closingTheDisassembleConfirmationThrowsThePlanAway() {
        assertNull(holding().opening(Overlay.DISASSEMBLE).closing(Overlay.DISASSEMBLE).disassemble)
    }

    @Test
    fun closingTheNewDeckWizardThrowsTheHalfTypedFormAway() {
        assertEquals(NewDeck(), holding().opening(Overlay.NEW_DECK).closing(Overlay.NEW_DECK).newDeck)
    }

    @Test
    fun closingTheRenameBoxThrowsTheNameAway() {
        assertNull(holding().opening(Overlay.RENAME).closing(Overlay.RENAME).rename)
    }

    @Test
    fun theCheatsheetHoldsNothingToThrowAway() {
        val before = holding().opening(Overlay.CHEATSHEET)
        assertEquals(before.copy(overlays = Overlays()), before.closing(Overlay.CHEATSHEET))
    }

    @Test
    fun andNorDoesTheCardPeek() {
        val before = holding().opening(Overlay.CHEATSHEET)
        assertEquals(before.copy(overlays = Overlays()), before.closing(Overlay.CHEATSHEET))
    }

    @Test
    fun everyOneOfThemForgetsItsOwnAndOnlyItsOwn() {
        HOLDERS.forEach { (o, holds) ->
            val s = holding().opening(o).closing(o)
            assertFalse(holds(s), "$o did not let go")
            (HOLDERS.keys - o).forEach { other ->
                assertTrue(HOLDERS[other]!!(s), "closing $o also emptied $other")
            }
        }
    }

    @Test
    fun theTwoThatHoldNothingLeaveEverythingAlone() {
        HOLDS_NOTHING.forEach { o ->
            val s = holding().opening(o).closing(o)
            HOLDERS.forEach { (other, holds) -> assertTrue(holds(s), "closing $o emptied $other") }
        }
    }

    @Test
    fun backForgetsTheSameThingsAsAnX() {
        Overlay.entries.forEach { o ->
            val open = holding().opening(o)
            assertEquals(open.closing(o), open.dismissTop(), o.name)
        }
    }

    @Test
    fun backOnlyForgetsWhatIsActuallyOnTop() {
        val s = holding().opening(Overlay.NEW_DECK).opening(Overlay.CHEATSHEET)
        val once = s.dismissTop()
        assertNotNull(once)
        assertEquals(NewDeck(name = "Half typed"), once.newDeck, "it emptied the wizard underneath")
        assertEquals(Overlay.NEW_DECK, once.overlays.top)
        assertEquals(NewDeck(), once.dismissTop()!!.newDeck)
    }

    @Test
    fun backWithNothingUpIsNotHandledAtAll() {
        assertNull(holding().dismissTop(), "it swallowed a gesture that should have navigated")
    }

    @Test
    fun backTakesThemOffOneAtATimeAllTheWayDown() {
        var s: AppState? = Overlay.entries.fold(holding()) { acc, o -> acc.opening(o) }
        Overlay.entries.reversed().forEach {
            assertEquals(it, s!!.overlays.top, it.name)
            s = s!!.dismissTop()
        }
        assertFalse(s!!.overlays.any)
        assertNull(s!!.dismissTop())
    }

    @Test
    fun andForgetsEveryOneOfThemOnTheWayDown() {
        var s: AppState? = Overlay.entries.fold(holding()) { acc, o -> acc.opening(o) }
        repeat(Overlay.entries.size) { s = s!!.dismissTop() }
        HOLDERS.forEach { (o, holds) -> assertFalse(holds(s!!), "$o was never let go of") }
    }

    @Test
    fun closingOneThatWasNeverUpStillLetsGoOfWhatItHeld() {
        // The X is the only way it is ever pressed, and it is only
        // drawn while the thing is open — but the rule does not depend
        // on the stack, so the state cannot be left behind either way.
        HOLDERS.forEach { (o, holds) ->
            assertFalse(holds(holding().closing(o)), o.name)
        }
    }

    @Test
    fun openingOneClearsAStaleToast() {
        Overlay.entries.forEach {
            assertNull(AppState().say("done").opening(it).toast, it.name)
        }
    }

    @Test
    fun closingOneDoesNotClearTheToastItJustPutUp() {
        Overlay.entries.forEach {
            assertEquals("saved", AppState().opening(it).say("saved").closing(it).toast, it.name)
        }
    }

    @Test
    fun andNorDoesBack() {
        Overlay.entries.forEach {
            assertEquals("saved", AppState().opening(it).say("saved").dismissTop()?.toast, it.name)
        }
    }

    @Test
    fun openingOneDoesNotChangeWhereYouAre() {
        Overlay.entries.forEach {
            val was = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(Route(View.STATS, "matt"))
            assertEquals(was.route, was.opening(it).route, it.name)
        }
    }

    @Test
    fun andNorDoesClosingIt() {
        Overlay.entries.forEach {
            val was = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(Route(View.STATS, "matt"))
            assertEquals(was.route, was.opening(it).closing(it).route, it.name)
        }
    }
}

// ------------------------------------------------------------------- admin

/** The gate, view by view. */
class AdminGateTest {

    private val locked = Admin()

    /** Signed in, with no role: Entry but not the log. */
    private val open = Admin().signIn(Account(key = "e7de0cb1"), "0.abc")

    /** Signed in with the role Matt hands out: everything. */
    private val operator = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "0.abc")

    @Test
    fun lockedReachesEverythingThatIsNotGated() {
        View.entries.forEach { assertEquals(!it.gated, locked.reachable(it), it.slug) }
    }

    @Test
    fun signedInReachesEverythingButTheOperatorsOwn() {
        View.entries.forEach { assertEquals(!it.operator, open.reachable(it), it.slug) }
    }

    @Test
    fun anOperatorReachesEverything() {
        View.entries.forEach { assertTrue(operator.reachable(it), it.slug) }
    }

    @Test
    fun theMenuOffersTheUngatedOnesWhileLocked() {
        assertEquals(View.entries.filter { it.inNav && !it.gated }, locked.visible)
        assertEquals(3, locked.visible.size)
    }

    @Test
    fun andEveryOneInTheNavForAnOperator() {
        assertEquals(View.entries.filter { it.inNav }, operator.visible)
        assertEquals(6, operator.visible.size)
        // Four for an ordinary account: the admin half is not theirs.
        assertEquals(4, open.visible.size)
    }

    @Test
    fun theMenuNeverOffersACard() {
        listOf(locked, open, operator).forEach {
            assertFalse(View.CARD in it.visible, "$it offered a card in the menu")
        }
    }

    @Test
    fun theMenuIsInDeclarationOrder() {
        listOf(locked, open, operator).forEach {
            assertEquals(it.visible.sortedBy { v -> v.ordinal }, it.visible, "$it")
        }
    }

    @Test
    fun aReachableRouteLandsExactlyWhereItSaid() {
        View.entries.filterNot { it.gated }.forEach {
            val r = Route(it, "rest", "x=1")
            assertEquals(r, locked.land(r), it.slug)
            assertEquals(r, open.land(r), it.slug)
            assertEquals(r, operator.land(r), it.slug)
        }
    }

    @Test
    fun andEveryRouteDoesForAnOperator() {
        View.entries.forEach {
            val r = Route(it, "rest", "x=1")
            assertEquals(r, operator.land(r), it.slug)
        }
    }

    @Test
    fun aGatedRouteBouncesToTheDefaultWhileLocked() {
        View.entries.filter { it.gated }.forEach {
            assertEquals(View.DEFAULT, locked.settle().land(Route(it, "rest", "x=1")).view, it.slug)
        }
    }

    @Test
    fun theBounceKeepsTheQueryString() {
        View.entries.filter { it.gated }.forEach {
            assertEquals("q=bolt", locked.land(Route(it, "rest", "q=bolt")).query, it.slug)
        }
    }

    @Test
    fun andDropsAnythingAfterTheSlash() {
        View.entries.filter { it.gated }.forEach {
            assertEquals("", locked.settle().land(Route(it, "rest")).rest, it.slug)
        }
    }

    @Test
    fun landingIsIdempotent() {
        listOf(locked, open, operator).forEach { admin ->
            View.entries.forEach {
                val once = admin.land(Route(it, "rest", "x=1"))
                assertEquals(once, admin.land(once), "$admin ${it.slug}")
            }
        }
    }

    @Test
    fun theDefaultIsSomewhereALockedPersonCanActuallyGo() {
        assertTrue(locked.reachable(View.DEFAULT))
        assertFalse(View.DEFAULT.gated)
        assertTrue(View.DEFAULT.inNav)
    }
}

// `AdminLifecycleTest` was here — 13 tests over the password
// lifecycle: try, unlock, give up, lock, and the gate following a
// token through all of it. There is no password and no lock. You
// sign in with Google and you sign out, and `OneWayInTest` holds
// the gate to an account and a role.

// ------------------------------------------------------------------- route

class RouteSpellingTest {

    @Test
    fun everyViewHasASlugThatParsesBackToIt() {
        View.entries.forEach { assertEquals(it, Route.parse("#/${it.slug}").view, it.slug) }
    }

    @Test
    fun everyViewRoundTripsOnItsOwn() {
        View.entries.forEach {
            val r = Route(it)
            assertEquals(r, Route.parse(r.toHash()), it.slug)
        }
    }

    @Test
    fun everyViewRoundTripsWithSomethingAfterIt() {
        View.entries.forEach {
            val r = Route(it, "rest")
            assertEquals(r, Route.parse(r.toHash()), it.slug)
        }
    }

    @Test
    fun everyViewRoundTripsWithAQueryString() {
        View.entries.forEach {
            val r = Route(it, "", "a=1&b=2")
            assertEquals(r, Route.parse(r.toHash()), it.slug)
        }
    }

    @Test
    fun everyViewRoundTripsWithBoth() {
        View.entries.forEach {
            val r = Route(it, "rest", "a=1")
            assertEquals(r, Route.parse(r.toHash()), it.slug)
        }
    }

    @Test
    fun aRestWithSlashesInItRoundTrips() {
        val r = Route(View.DECKS, "a/b/c")
        assertEquals(r, Route.parse(r.toHash()))
        assertEquals("a/b/c", Route.parse("#/decks/a/b/c").rest)
    }

    @Test
    fun aStaleBookmarkLandsSomewhereUseful() {
        listOf("#/nonsense", "#/Search", "#/DECKS", "#/searchx", "#/x/y/z").forEach {
            assertEquals(View.DEFAULT, Route.parse(it).view, it)
        }
    }

    @Test
    fun soDoesNothingAtAll() {
        listOf(null, "", "#", "#/", "/", "//", "#//").forEach {
            val r = Route.parse(it)
            assertEquals(View.DEFAULT, r.view, "[$it]")
            assertEquals("", r.rest, "[$it]")
            assertEquals("", r.query, "[$it]")
        }
    }

    @Test
    fun aTrailingSlashIsNotSomethingAfterTheSlash() {
        View.entries.forEach {
            assertEquals("", Route.parse("#/${it.slug}/").rest, it.slug)
            assertEquals(it, Route.parse("#/${it.slug}/").view, it.slug)
        }
    }

    @Test
    fun andNorAreSeveralOfThem() {
        assertEquals("", Route.parse("#/decks///").rest)
        assertEquals("alela", Route.parse("#/decks//alela").rest)
        assertEquals("a/b", Route.parse("#/decks//a//b//").rest)
    }

    @Test
    fun theHashItselfIsOptional() {
        listOf("#/stats/matt", "/stats/matt", "stats/matt").forEach {
            assertEquals(View.STATS, Route.parse(it).view, it)
            assertEquals("matt", Route.parse(it).rest, it)
        }
    }

    @Test
    fun theOldAddAndRemoveNamesLandOnTheWizardWithWhateverTheyCarried() {
        assertEquals(View.ENTRY, Route.parse("#/add").view)
        assertEquals(View.ENTRY, Route.parse("#/remove").view)
        assertEquals("csv", Route.parse("#/add/csv").rest)
        assertEquals("x=1", Route.parse("#/remove?x=1").query)
    }

    @Test
    fun butOnlyInTheSpellingTheyWereActuallyUsedIn() {
        listOf("#/Add", "#/ADD", "#/adds", "#/removed").forEach {
            assertEquals(View.DEFAULT, Route.parse(it).view, it)
        }
    }

    @Test
    fun everyMovedNameActuallyGoesSomewhere() {
        View.MOVED.forEach { (from, to) ->
            assertTrue(to in View.entries, "$from points at nothing")
            assertEquals(to, Route.parse("#/$from").view, from)
            assertNull(View.of(from), "$from is both a moved name and a live slug")
        }
    }

    @Test
    fun aQueryStringKeepsItsAmpersandsAndEquals() {
        val r = Route.parse("#/search?q=a+b&sort=name&dir=desc")
        assertEquals("q=a+b&sort=name&dir=desc", r.query)
        assertEquals("", r.rest)
    }

    @Test
    fun anEqualsInsideAValueIsPartOfTheValue() {
        assertEquals("q=a=b", Route.parse("#/search?q=a=b").query)
    }

    @Test
    fun aSecondQuestionMarkIsPartOfTheQuery() {
        assertEquals("q=a?b", Route.parse("#/search?q=a?b").query)
    }

    @Test
    fun aQuestionMarkWithNothingAfterItIsNoQuery() {
        assertEquals("", Route.parse("#/search?").query)
        assertEquals("#/search", Route.parse("#/search?").toHash())
    }

    @Test
    fun aQueryWithNoPathIsStillTheDefaultView() {
        val r = Route.parse("#?q=1")
        assertEquals(View.DEFAULT, r.view)
        assertEquals("q=1", r.query)
    }

    @Test
    fun theQueryIsNotTakenOutOfTheRest() {
        val r = Route.parse("#/decks/alela?x=1")
        assertEquals("alela", r.rest)
        assertEquals("x=1", r.query)
        assertTrue(r.namesADeck)
    }

    @Test
    fun percentEncodingIsCarriedThroughVerbatim() {
        val r = Route.parse("#/card/lim-d%C3%BBl%27s+vault")
        assertEquals("lim-d%C3%BBl%27s+vault", r.rest)
        assertEquals(CardRef("lim-dûl's vault"), CardRef.parse(r.rest))
    }

    @Test
    fun aHashIsWrittenPathThenQuery() {
        assertEquals("#/stats", Route(View.STATS).toHash())
        assertEquals("#/stats/matt", Route(View.STATS, "matt").toHash())
        assertEquals("#/stats?x=1", Route(View.STATS, "", "x=1").toHash())
        assertEquals("#/stats/matt?x=1", Route(View.STATS, "matt", "x=1").toHash())
    }

    @Test
    fun aRouteDefaultsToNothingAfterTheSlashAndNoQuery() {
        val r = Route(View.LOGS)
        assertEquals("", r.rest)
        assertEquals("", r.query)
    }

    @Test
    fun onlyADeckRouteWithASlugNamesADeck() {
        View.entries.forEach {
            assertEquals(it == View.DECKS, Route(it, "alela").namesADeck, it.slug)
            assertFalse(Route(it).namesADeck, it.slug)
        }
    }

    @Test
    fun andOnlyACardRouteWithANameNamesACard() {
        View.entries.forEach {
            assertEquals(it == View.CARD, Route(it, "sol+ring").namesACard, it.slug)
            assertFalse(Route(it).namesACard, it.slug)
        }
    }

    @Test
    fun aQueryStringAloneDoesNotNameADeckOrACard() {
        assertFalse(Route(View.DECKS, "", "x=1").namesADeck)
        assertFalse(Route(View.CARD, "", "x=1").namesACard)
    }

    @Test
    fun parsingIsIdempotentThroughTheHash() {
        listOf(
            "#/search", "#/search?q=bolt", "#/decks", "#/decks/alela", "#/decks/alela?x=1",
            "#/stats/matt", "#/entry", "#/logs", "#/card/sol+ring", "#/card/a%2Fb",
        ).forEach {
            assertEquals(it, Route.parse(it).toHash(), it)
            assertEquals(it, Route.parse(Route.parse(it).toHash()).toHash(), it)
        }
    }
}

class ViewTableTest {

    @Test
    fun thereAreSevenOfThem() {
        // Seven again, with Admin Settings. It was six after the
        // Query page went.
        assertEquals(7, View.entries.size)
    }

    @Test
    fun everySlugIsItsOwn() {
        assertEquals(View.entries.size, View.entries.map { it.slug }.distinct().size)
    }

    @Test
    fun everyLabelIsItsOwnAndSaysSomething() {
        assertEquals(View.entries.size, View.entries.map { it.label }.distinct().size)
        View.entries.forEach { assertTrue(it.label.isNotBlank(), it.name) }
    }

    @Test
    fun everySlugIsSomethingAHashCanHold() {
        View.entries.forEach {
            assertTrue(it.slug.isNotBlank(), it.name)
            assertFalse('/' in it.slug, it.name)
            assertFalse('?' in it.slug, it.name)
            assertFalse('#' in it.slug, it.name)
            assertEquals(it.slug.lowercase(), it.slug, it.name)
        }
    }

    @Test
    fun exactlyThreeNeedAnAccountAndTwoOfThoseNeedTheRole() {
        assertEquals(listOf(View.ENTRY, View.ADMIN, View.LOGS), View.entries.filter { it.gated })
        assertEquals(listOf(View.ADMIN, View.LOGS), View.entries.filter { it.operator })
    }

    @Test
    fun andExactlyOneIsNotOfferedByTheMenu() {
        assertEquals(listOf(View.CARD), View.entries.filterNot { it.inNav })
    }

    @Test
    fun nothingIsBothGatedAndHiddenFromTheMenu() {
        View.entries.forEach { assertFalse(it.gated && !it.inNav, it.name) }
    }

    @Test
    fun theDefaultIsTheLibrary() {
        assertEquals(View.LIBRARY, View.DEFAULT)
    }

    @Test
    fun lookingUpEverySlugFindsItsView() {
        View.entries.forEach { assertEquals(it, View.of(it.slug), it.slug) }
    }

    @Test
    fun andAnythingElseFindsNothing() {
        listOf(null, "", " ", "Search", "SEARCH", "searc", "searchx", "/search", "card/x").forEach {
            assertNull(View.of(it), "[$it]")
        }
    }

    @Test
    fun theMovedNamesAreNotThemselvesSlugs() {
        View.MOVED.keys.forEach { assertNull(View.of(it), it) }
    }

    @Test
    fun bothMovedNamesLandOnTheWizard() {
        assertEquals(mapOf("add" to View.ENTRY, "remove" to View.ENTRY), View.MOVED)
    }

    @Test
    fun noViewIsLostBetweenTheNavAndTheRouter() {
        View.entries.filter { it.inNav }.forEach {
            assertTrue(it in Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t").visible, it.slug)
            assertEquals(it, View.of(it.slug), it.slug)
        }
    }
}

// --------------------------------------------------------------- fetching

class FetchingTest {

    private val withACard = populated().copy(card = CardDetail(name = "Bolt", nameNorm = "bolt"))

    @Test
    fun everyScreenThatFetchesSaysSo() {
        val fetches = setOf(View.LIBRARY, View.DECKS, View.STATS, View.LOGS, View.ADMIN, View.CARD)
        View.entries.forEach {
            assertEquals(it in fetches, busyOn(withACard.fetching(it), it), it.slug)
        }
    }

    @Test
    fun andTheOnesThatDoNotAreLeftExactlyAsTheyWere() {
        listOf(View.ENTRY).forEach {
            assertEquals(withACard, withACard.fetching(it), it.slug)
        }
    }

    @Test
    fun theDefaultIsWhereYouActuallyAre() {
        View.entries.forEach {
            val at = withACard.copy(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(it)
            // `navigate` closes the card, so put one back for the card view.
            val ready = if (it == View.CARD) at.copy(card = CardDetail(name = "Bolt")) else at
            assertEquals(busyOn(ready.fetching(it), it), busyOn(ready.fetching(), it), it.slug)
        }
    }

    @Test
    fun fetchingClearsWhateverTheLastFailureSaid() {
        View.entries.forEach {
            val failed = withACard.fetchFailed("gone", it)
            assertNull(errorOn(failed.fetching(it), it), it.slug)
        }
    }

    @Test
    fun fetchingOneScreenLeavesTheOthersAlone() {
        View.entries.forEach { asked ->
            val s = withACard.fetching(asked)
            View.entries.filterNot { it == asked }.forEach { other ->
                assertFalse(busyOn(s, other), "${asked.slug} put ${other.slug} to work")
            }
        }
    }

    @Test
    fun aCardViewWithNoCardOpenHasNothingToMarkBusy() {
        val s = populated().copy(card = null)
        assertEquals(s, s.fetching(View.CARD))
        assertNull(s.fetching(View.CARD).card)
    }

    @Test
    fun fetchingNeverPutsUpAToast() {
        View.entries.forEach {
            assertNull(withACard.say(null).fetching(it).toast, it.slug)
        }
    }

    @Test
    fun fetchingDoesNotMoveYou() {
        View.entries.forEach {
            assertEquals(withACard.route, withACard.fetching(it).route, it.slug)
        }
    }
}

class FetchFailedTest {

    private val withACard = populated().copy(card = CardDetail(name = "Bolt", nameNorm = "bolt"))

    @Test
    fun everyScreenWithSomewhereToSayItSaysIt() {
        val says = setOf(View.LIBRARY, View.DECKS, View.STATS, View.LOGS, View.ADMIN, View.CARD)
        View.entries.forEach {
            assertEquals(
                if (it in says) "the database said no" else null,
                errorOn(withACard.fetchFailed("the database said no", it), it),
                it.slug,
            )
        }
    }

    @Test
    fun theTwoWithNowhereToSayItToastInstead() {
        listOf(View.ENTRY).forEach {
            assertEquals("the database said no", withACard.fetchFailed("the database said no", it).toast, it.slug)
        }
    }

    @Test
    fun andTheOthersDoNotToastBecauseTheyAreShowingIt() {
        // "No decks yet" over a failed load read as the decks having
        // vanished; the failure belongs on the screen, not in a toast.
        setOf(View.LIBRARY, View.DECKS, View.STATS, View.LOGS, View.CARD).forEach {
            assertNull(withACard.say(null).fetchFailed("gone", it).toast, it.slug)
        }
    }

    @Test
    fun aFailureStopsTheScreenLookingBusy() {
        View.entries.forEach {
            assertFalse(busyOn(withACard.fetching(it).fetchFailed("gone", it), it), it.slug)
        }
    }

    @Test
    fun aCardViewWithNoCardOpenHasNowhereToPutIt() {
        val s = populated().copy(card = null, toast = null)
        assertEquals(s, s.fetchFailed("gone", View.CARD))
    }

    @Test
    fun failingOneScreenLeavesTheOthersWithNothingToSay() {
        View.entries.forEach { asked ->
            val s = withACard.fetchFailed("gone", asked)
            View.entries.filterNot { it == asked }.forEach { other ->
                assertNull(errorOn(s, other), "${asked.slug} failed ${other.slug} as well")
            }
        }
    }

    @Test
    fun theDefaultIsWhereYouActuallyAre() {
        View.entries.forEach {
            val at = withACard.copy(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).navigate(it)
            val ready = if (it == View.CARD) at.copy(card = CardDetail(name = "Bolt")) else at
            assertEquals(ready.fetchFailed("gone", it), ready.fetchFailed("gone"), it.slug)
        }
    }

    @Test
    fun aFailureDoesNotMoveYou() {
        View.entries.forEach {
            assertEquals(withACard.route, withACard.fetchFailed("gone", it).route, it.slug)
        }
    }

    @Test
    fun anEmptyMessageIsStillAFailure() {
        assertEquals("", withACard.fetchFailed("", View.DECKS).decks.error)
    }
}

// ------------------------------------------------------------------- load

class LoadNeedsTest {

    @Test
    fun everyRouteSaysWhatItNeeds() {
        View.entries.forEach {
            val expected = when (it) {
                View.LIBRARY -> listOf("cards", "count")
                View.DECKS -> listOf("decks")
                View.STATS -> listOf("totals")
                View.LOGS -> listOf("logs")
                View.ADMIN -> listOf("people")
                View.CARD -> listOf("card")
                View.ENTRY -> emptyList()
            }
            assertEquals(expected, Load.needs(Route(it)), it.slug)
        }
    }

    @Test
    fun aNamedDeckNeedsTheListAsWellAsTheTiles() {
        assertEquals(listOf("decks", "deck"), Load.needs(Route(View.DECKS, "alela")))
    }

    @Test
    fun andAnEmptySlugIsStillJustTheTiles() {
        assertEquals(listOf("decks"), Load.needs(Route(View.DECKS, "")))
    }

    @Test
    fun anythingAfterTheSlashOnlyMattersForDecks() {
        View.entries.filterNot { it == View.DECKS }.forEach {
            assertEquals(Load.needs(Route(it)), Load.needs(Route(it, "rest")), it.slug)
        }
    }

    @Test
    fun andNorDoesAQueryString() {
        View.entries.forEach {
            assertEquals(Load.needs(Route(it)), Load.needs(Route(it, "", "x=1")), it.slug)
        }
    }

    @Test
    fun onlyTheTwoScreensThatFetchNothingAskForNothing() {
        View.entries.forEach {
            val quiet = it == View.ENTRY
            assertEquals(quiet, Load.needs(Route(it)).isEmpty(), it.slug)
        }
    }

    @Test
    fun nothingIsAskedForTwice() {
        View.entries.forEach {
            val needs = Load.needs(Route(it, "alela"))
            assertEquals(needs.distinct(), needs, it.slug)
        }
    }

    @Test
    fun theStatsScopeComesOutOfTheRoute() {
        // A slug now, not one of two names. An old `#/stats/matt`
        // bookmark still names a collection; there is simply no list
        // of the collections there could be.
        listOf("matt", "kayla").forEach { assertEquals(it, Load.scopeFrom(it).owner, it) }
    }

    @Test
    fun onlyAnEmptyRestIsEverybody() {
        // There is no list of collections to check a slug against, so
        // a rest that says something is taken at its word and the
        // numbers come back empty if nobody owns that. Blank is the
        // one case that means every collection at once.
        listOf("", " ", "\t").forEach {
            assertNull(Load.scopeFrom(it).owner, "[$it]")
        }
        listOf("Matt", "nonsense", "matt/kayla").forEach {
            assertEquals(it, Load.scopeFrom(it).owner, "[$it]")
        }
    }

    @Test
    fun theLibraryAsksForItsPageAndItsCountOffTheSameSearch() {
        val lib = Library(filters = Filters(q = "bolt", page = 2))
        val (page, count) = Load.library(lib)
        assertEquals(lib.queries(), page to count)
        assertEquals(page.params, count.params)
    }

    @Test
    fun aCardAsksForEverythingTheDrawerShows() {
        // Six: the sixth is everything else `cards` has on it. Before
        // that, five, not four. The fifth is the card itself — the face
        // query. For a long time this was four, and a card page that
        // asks four questions about the collection and none about the
        // card is how both platforms ended up showing no mana cost,
        // no type line, no rules text and no power and toughness.
        assertEquals(6, Load.card("sol ring").size)
        assertEquals(6, Load.card("sol ring").distinct().size)
        assertTrue(
            Load.card("sol ring").first().sql.contains("oracle_text"),
            "the card's own face is not the first thing asked for",
        )
    }

    @Test
    fun aDeckAsksForItsOwnCards() {
        assertEquals(DeckQueries.cards("alela"), Load.deck("alela"))
        assertEquals(DeckQueries.all(), Load.decks())
    }

    @Test
    fun theStatsQueryFollowsTheScope() {
        listOf("matt", "kayla").forEach {
            assertEquals(StatsQueries.totals(StatsScope(it)), Load.stats(StatsScope(it)), it)
        }
        assertEquals(StatsQueries.totals(StatsScope()), Load.stats(StatsScope()))
    }
}

// ------------------------------------------------------------------ share

class WithShareTest {

    private val csv = "Name,Quantity\nSol Ring,1\nMana Crypt,2"

    @Test
    fun itOpensTheWizardWhenThereIsATokenForIt() {
        assertEquals(View.ENTRY, AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare(csv).view)
    }

    @Test
    fun andBouncesButKeepsTheListWhenThereIsNot() {
        val s = AppState(admin = Admin().settle()).withShare(csv)
        assertEquals(View.DEFAULT, s.view, "the wizard is gated")
        assertEquals(csv, s.sharedList, "the list did not survive the bounce")
        assertEquals(csv, s.entry.list)
    }

    @Test
    fun theListIsInTheBoxWithNothingElseDecided() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare(csv)
        assertEquals(MassEntry.fromShare(csv), s.entry)
        assertNull(s.entry.direction, "it decided whether this was an add or a remove")
        // The wizard no longer decides whose collection this lands in:
        // an account owns one, and the server refuses a write to any other.
        assertEquals(Step.WHICH, s.entry.step)
        assertNull(s.entry.preview)
        assertNull(s.entry.result)
    }

    @Test
    fun theHeaderRowIsNotACard() {
        assertEquals(2, AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare(csv).entry.cardCount)
    }

    @Test
    fun anEmptyShareIsStillAShare() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare("")
        assertEquals("", s.sharedList)
        assertEquals(0, s.entry.cardCount)
    }

    @Test
    fun aSecondShareReplacesTheFirst() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare("1 Sol Ring").withShare("1 Mana Crypt")
        assertEquals("1 Mana Crypt", s.sharedList)
        assertEquals("1 Mana Crypt", s.entry.list)
    }

    @Test
    fun aShareThrowsAwayWhateverWasHalfTypedInTheWizard() {
        val busy = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), entry = MassEntry(list = "old", direction = Direction.REMOVE))
        assertEquals("1 Sol Ring", busy.withShare("1 Sol Ring").entry.list)
        assertNull(busy.withShare("1 Sol Ring").entry.direction)
    }

    @Test
    fun itLeavesTheRestOfTheAppWhereItWas() {
        val s = populated().withShare("1 Sol Ring")
        assertEquals("bolt", s.library.filters.q)
        assertEquals(1, s.history.entries.size)
        assertTrue(s.admin.unlocked)
        assertEquals("matt", s.stats.scope.owner)
    }

    @Test
    fun itLandsByTheSameRuleEverythingElseDoes() {
        listOf(Admin(), Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"), Admin()).forEach {
            assertEquals(
                it.land(Route(View.ENTRY)),
                AppState(admin = it).withShare("1 Sol Ring").route,
                "$it",
            )
        }
    }

    @Test
    fun aShareIsSpentOnce() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")).withShare(csv).shareUsed()
        assertNull(s.sharedList)
        assertEquals(csv, s.entry.list, "spending the share emptied the box")
    }

    @Test
    fun spendingItTwiceIsHarmless() {
        assertNull(AppState().withShare("1 Sol Ring").shareUsed().shareUsed().sharedList)
    }

    @Test
    fun spendingAShareThatNeverArrivedIsHarmless() {
        assertEquals(AppState(), AppState().shareUsed())
    }

    @Test
    fun aShareWhileSignedOutCanBeUsedAfterSigningIn() {
        val s = AppState().withShare(csv)
        val open = s.copy(admin = s.admin.signIn(Account(key = "e7de0cb1"), "t"))
        assertEquals(View.ENTRY, open.navigate(View.ENTRY).view)
        assertEquals(csv, open.navigate(View.ENTRY).entry.list)
    }
}

// ----------------------------------------------------------------- entry

class RecordEntryTest {

    private val done = MassEntry(
        direction = Direction.ADD,
        list = "1 Sol Ring\n2 Mana Crypt",
        result = Applied(applied = true, resolved = 3),
        step = Step.DONE,
    )

    @Test
    fun nothingIsRecordedUntilTheServerHasAnswered() {
        listOf(
            MassEntry(),
            MassEntry(list = "1 Sol Ring"),
            MassEntry(list = "1 Sol Ring", direction = Direction.ADD),
            MassEntry(list = "1 Sol Ring", preview = Applied(dryRun = true)),
        ).forEach {
            val s = AppState(entry = it)
            assertEquals(s, s.recordEntry("2026-01-01T00:00:00Z"), "$it")
        }
    }

    @Test
    fun aFinishedEntryBecomesARow() {
        val s = AppState(entry = done).browsing("matt").recordEntry("2026-01-01T00:00:00Z")
        assertEquals(1, s.history.entries.size)
        val row = s.history.entries.first()
        assertEquals("2026-01-01T00:00:00Z", row.at)
        assertEquals("add", row.direction)
        assertEquals("matt", row.owner)
        // Lines, the way `countCards` counts them — the number the
        // request size is limited by, not the number of copies.
        assertEquals(2, row.count)
        assertEquals("1 Sol Ring\n2 Mana Crypt", row.list)
    }

    @Test
    fun aRemovalIsRecordedAsOne() {
        val s = AppState(entry = done.copy(direction = Direction.REMOVE))
            .browsing("kayla")
            .recordEntry("now")
        assertEquals("remove", s.history.entries.first().direction)
        assertEquals("kayla", s.history.entries.first().owner)
        assertFalse(s.history.entries.first().isAdd)
    }

    @Test
    fun everyDirectionAndCollectionSurvivesTheRoundTrip() {
        Direction.entries.forEach { d ->
            listOf("matt", "kayla").forEach { o ->
                val row = AppState(entry = done.copy(direction = d))
                    .browsing(o)
                    .recordEntry("now").history.entries.first()
                assertEquals(d, row.asDirection(), "$d/$o")
                assertEquals(o, row.asOwner(), "$d/$o")
            }
        }
    }

    @Test
    fun aResultWithNoDirectionOrOwnerStillRecordsSomething() {
        val row = AppState(entry = MassEntry(list = "1 Sol Ring", result = Applied(applied = true)))
            .recordEntry("now").history.entries.first()
        assertEquals("", row.direction)
        assertEquals("", row.owner)
        assertNull(row.asDirection())
        assertNull(row.asOwner())
    }

    @Test
    fun theNewestRowIsFirst() {
        var s = AppState(entry = done)
        s = s.recordEntry("first")
        s = s.copy(entry = done.copy(list = "1 Black Lotus")).recordEntry("second")
        assertEquals("second", s.history.entries.first().at)
        assertEquals(2, s.history.entries.size)
    }

    @Test
    fun theHistoryStopsGrowingAtItsCap() {
        var s = AppState(entry = done)
        repeat(EntryHistory.MAX + 10) { s = s.recordEntry("row$it") }
        assertEquals(EntryHistory.MAX, s.history.entries.size)
        assertEquals("row${EntryHistory.MAX + 9}", s.history.entries.first().at)
    }

    @Test
    fun onlyTheFirstDozenAreEverShown() {
        var s = AppState(entry = done)
        repeat(20) { s = s.recordEntry("row$it") }
        assertEquals(EntryHistory.SHOWN, s.history.recent.size)
    }

    @Test
    fun recordingLeavesTheWizardItselfAlone() {
        val s = AppState(entry = done).recordEntry("now")
        assertEquals(done, s.entry, "recording the row emptied the box it came from")
    }

    @Test
    fun recordingMovesNothingAndSaysNothing() {
        val was = populated().copy(entry = done, toast = null)
        val now = was.recordEntry("now")
        assertEquals(was.route, now.route)
        assertNull(now.toast)
        assertEquals(was.copy(history = now.history), now)
    }

    @Test
    fun aRecordedRowCanBeReusedWithoutItsOwner() {
        val s = AppState(entry = done).recordEntry("now")
        val again = s.history.reuse(s.history.entries.first())
        assertEquals("1 Sol Ring\n2 Mana Crypt", again.list)
        assertEquals(Direction.ADD, again.direction)
        // The wizard no longer decides whose collection this lands in:
        // an account owns one, and the server refuses a write to any other.
        assertEquals(Step.LIST, again.step)
    }
}

// ------------------------------------------------------------------ toast

class SayTest {

    @Test
    fun itSaysWhatItIsTold() {
        assertEquals("done", AppState().say("done").toast)
    }

    @Test
    fun andNothingClearsIt() {
        assertNull(AppState().say("done").say(null).toast)
    }

    @Test
    fun theLastThingSaidIsTheOneShowing() {
        assertEquals("second", AppState().say("first").say("second").toast)
    }

    @Test
    fun anEmptyMessageIsNotTheSameAsNoMessage() {
        assertEquals("", AppState().say("").toast)
    }

    @Test
    fun sayingSomethingMovesNothing() {
        val was = populated().copy(toast = null)
        assertEquals(was.copy(toast = "hello"), was.say("hello"))
    }

    @Test
    fun aRouteChangeClearsIt() {
        View.entries.forEach {
            assertNull(populated().navigate(it).toast, it.slug)
        }
    }

    @Test
    fun openingAnythingClearsIt() {
        Overlay.entries.forEach { assertNull(populated().opening(it).toast, it.name) }
    }

    @Test
    fun openingACardClearsIt() {
        assertNull(populated().openCard(CardRef("sol ring")).toast)
    }

    @Test
    fun andSoDoesLeavingOne() {
        assertNull(populated().openCard(CardRef("sol ring")).say("hi").leaveCard().toast)
    }

    @Test
    fun aFailureOnAScreenWithNowhereToShowItBecomesOne() {
        // Mass Entry is the only screen left with nowhere of its own
        // to put an error, and it is gated — so this needs to be
        // unlocked, or `navigate` bounces straight back to the
        // Library, which does have somewhere and never toasts.
        val admin = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        assertEquals("boom", admin.navigate(View.ENTRY).fetchFailed("boom").toast)
    }
}

// ------------------------------------------------------------------ title

class TitleTest {

    @Test
    fun everyScreenIsCalledWhatTheMenuCallsIt() {
        val s = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))
        View.entries.filterNot { it == View.CARD }.forEach {
            assertEquals(it.label, s.navigate(it).title, it.slug)
        }
    }

    @Test
    fun anOpenDeckIsCalledByItsName() {
        val s = onDeck()
        assertEquals("Alela", s.title)
    }

    @Test
    fun aDeckWithProseAfterItsNameIsCalledTheNamePart() {
        val wordy = DecksState(
            decks = listOf(deck("explorers", "Explorers of the Deep — a very long Precon name")),
            openKey = "explorers",
        )
        assertEquals("Explorers of the Deep", AppState(decks = wordy).navigate(Route(View.DECKS, "explorers")).title)
    }

    @Test
    fun theDeckListIsCalledDecks() {
        assertEquals("Decks", AppState(decks = fiveCardDeck()).navigate(View.DECKS).title)
    }

    @Test
    fun aCardIsCalledByItsName() {
        assertEquals("Sol Ring", AppState().openCard(CardRef("sol ring"), "Sol Ring").title)
    }

    @Test
    fun aCardWithNoNameYetIsJustCalledCard() {
        assertEquals("Card", AppState().openCard(CardRef("sol ring"), " ").title)
        assertEquals("Card", AppState().navigate(View.CARD, "sol+ring").title)
    }

    @Test
    fun aDeckRouteWithNoDeckReadIsStillCalledDecks() {
        assertEquals("Decks", AppState().navigate(Route(View.DECKS, "alela")).title)
    }
}

// ------------------------------------------------------------- the name box

class RestoredSearchTest {

    @Test
    fun aSearchFromALinkFillsTheBoxAsWellAsTheFilter() {
        val s = AppState().restoredSearch(Filters(q = "bolt"))
        assertEquals("bolt", s.library.filters.q)
        assertEquals("bolt", s.complete.term, "the search applied with an empty box above it")
    }

    @Test
    fun andClosesWhateverTheListWasShowing() {
        val was = AppState(complete = Completion(term = "ab", items = listOf("Abrade"), open = true, active = 0))
        val s = was.restoredSearch(Filters(q = "bolt"))
        assertFalse(s.complete.open)
        assertTrue(s.complete.items.isEmpty())
    }

    @Test
    fun thePageInTheLinkIsNotThrownAway() {
        assertEquals(4, AppState().restoredSearch(Filters(q = "bolt", page = 4)).library.filters.page)
    }

    @Test
    fun everyPartOfTheSearchArrives() {
        val f = Filters(q = "bolt", sort = Sort.NAME, descending = false, page = 2, colors = listOf("R"))
        assertEquals(f, AppState().restoredSearch(f).library.filters)
    }

    @Test
    fun anEmptySearchEmptiesTheBox() {
        val s = AppState(complete = Completion(term = "bolt")).restoredSearch(Filters())
        assertEquals("", s.complete.term)
        assertEquals(Filters(), s.library.filters)
    }

    @Test
    fun itMovesNothingElse() {
        val was = populated()
        val now = was.restoredSearch(Filters(q = "x"))
        assertEquals(was.route, now.route)
        assertEquals(was.decks, now.decks)
        assertEquals(was.toast, now.toast)
    }
}

class TypedCardNameTest {

    @Test
    fun theBoxAndTheFilterMoveTogether() {
        val s = AppState().typedCardName(Completion(term = "b"))
        assertEquals("b", s.complete.term)
        assertEquals("b", s.library.filters.q, "the box accepted a character the filter never saw")
    }

    @Test
    fun oneCharacterAtATimeAllArrive() {
        var s = AppState()
        var c = Completion()
        "bolt".forEachIndexed { i, _ ->
            c = c.typed("bolt".substring(0, i + 1))
            s = s.typedCardName(c)
            assertEquals("bolt".substring(0, i + 1), s.complete.term)
            assertEquals("bolt".substring(0, i + 1), s.library.filters.q)
        }
    }

    @Test
    fun typingGoesBackToPageOne() {
        val s = AppState(library = Library(filters = Filters(q = "a", page = 7)))
        assertEquals(1, s.typedCardName(Completion(term = "ab")).library.filters.page)
    }

    @Test
    fun theSuggestionListIsKeptAsGiven() {
        val c = Completion(term = "bo", items = listOf("Bolt"), open = true, active = 0)
        assertEquals(c, AppState().typedCardName(c).complete)
    }

    @Test
    fun clearingTheBoxClearsTheFilter() {
        val s = AppState(library = Library(filters = Filters(q = "bolt"))).typedCardName(Completion(term = ""))
        assertEquals("", s.library.filters.q)
    }

    @Test
    fun itLeavesTheRestOfTheFilterAlone() {
        val f = Filters(q = "a", colors = listOf("R"), sort = Sort.NAME)
        val s = AppState(library = Library(filters = f)).typedCardName(Completion(term = "ab"))
        assertEquals(listOf("R"), s.library.filters.colors)
        assertEquals(Sort.NAME, s.library.filters.sort)
    }

    @Test
    fun itMovesNothingElse() {
        val was = populated()
        val now = was.typedCardName(Completion(term = "x"))
        assertEquals(was.route, now.route)
        assertEquals(was.decks, now.decks)
    }
}

// -------------------------------------------------------------- the keyboard

class OnKeyShellTest {

    private val locked = AppState()

    /** An operator, so every letter that names a screen has one. */
    private val open = AppState(admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"))

    @Test
    fun everyLetterThatNamesAReachableViewGoesThere() {
        mapOf(
            "s" to View.LIBRARY, "d" to View.DECKS, "g" to View.STATS,
            "e" to View.ENTRY, "v" to View.LOGS,
        ).forEach { (key, view) ->
            assertEquals(view, open.onKey(key)?.view, key)
        }
    }

    @Test
    fun andTheGatedOnesDoNothingAtAllWhileLocked() {
        listOf("e", "v").forEach {
            assertNull(locked.onKey(it), "$it told you the screen exists")
        }
    }

    @Test
    fun aViewKeyClearsTheToastAndTheOverlays() {
        val s = Overlay.entries.fold(open.say("hi")) { acc, o -> acc.opening(o) }.onKey("d")
        assertNotNull(s)
        assertNull(s.toast)
        assertFalse(s.overlays.any)
    }

    @Test
    fun nothingAtAllFiresWhileTyping() {
        listOf("s", "d", "e", "g", "c", "v", "l", "/", "?", "k").forEach {
            assertNull(open.onKey(it, typing = true), it)
        }
    }

    @Test
    fun exceptTheChordWhichIsTheWholePointOfIt() {
        listOf("k", "K").forEach {
            assertNotNull(open.onKey(it, typing = true, meta = true), it)
            assertNotNull(open.onKey(it, typing = true, ctrl = true), it)
        }
    }

    @Test
    fun theChordOpensTheFinderReadyToType() {
        val s = open.onKey("k", meta = true)
        assertNotNull(s)
        assertTrue(s.palette.open)
        assertEquals(Overlay.PALETTE, s.overlays.top)
    }

    @Test
    fun soDoesSlash() {
        val s = open.onKey("/")
        assertNotNull(s)
        assertTrue(s.palette.open)
    }

    @Test
    fun openingTheFinderTwiceDoesNotStackIt() {
        val s = open.onKey("/")!!.onKey("/")
        assertNotNull(s)
        assertEquals(1, s.overlays.stack.size)
    }

    @Test
    fun escapeDoesNothingUntilSomethingIsUp() {
        assertNull(open.onKey("Escape"))
        assertNull(open.onKey("Escape", typing = true))
    }

    @Test
    fun andThenItClosesWhateverIsOnTop() {
        Overlay.entries.forEach { o ->
            val s = open.opening(o).onKey("Escape")
            assertNotNull(s, o.name)
            assertFalse(s.overlays.any, o.name)
        }
    }

    @Test
    fun escapeForgetsWhatTheOverlayWasHolding() {
        HOLDERS.forEach { (o, holds) ->
            val s = holding().opening(o).onKey("Escape")
            assertNotNull(s, o.name)
            assertFalse(holds(s), o.name)
        }
    }

    @Test
    fun escapeWorksWhileTypingWhichIsWhyItIsThere() {
        assertNotNull(open.opening(Overlay.PALETTE).onKey("Escape", typing = true))
    }

    @Test
    fun theHelpKeySaysOnlyWhatIsReachable() {
        val shut = locked.onKey("?")
        assertNotNull(shut)
        assertEquals(Shortcuts.help(Admin()), shut.toast)
        assertFalse(shut.toast!!.contains("e entry"))

        val on = open.onKey("?")
        assertNotNull(on)
        assertTrue(on.toast!!.contains("e entry"))
        assertTrue(on.toast!!.contains("v logs"))
    }

    @Test
    fun theHelpKeyDoesNotMoveYou() {
        View.entries.forEach {
            val at = open.navigate(it)
            assertEquals(at.route, at.onKey("?")?.route, it.slug)
        }
    }

    /**
     * `l` toggled the shared password, and there is no password. It
     * is an unknown key now, which is the only honest answer: signing
     * in goes to Google and is not something a stray keystroke should
     * start.
     */
    @Test
    fun unknownKeysAreHandedBack() {
        listOf("l", "q", "z", "1", "", "Enter", "ArrowDown", "S", "D").forEach {
            assertNull(open.onKey(it), it)
        }
    }

    @Test
    fun aViewKeyIsNotHandledAnyDifferentlyFromTheNavBar() {
        mapOf("s" to View.LIBRARY, "d" to View.DECKS, "g" to View.STATS)
            .forEach { (key, view) ->
                assertEquals(locked.navigate(view), locked.onKey(key), key)
            }
    }
}
