package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same carousel, over the Library.
 *
 * Matt: "Now let's use the same carousel for the library page."
 *
 * Which means the carousel stops being a thing about decks. It is
 * over a *run* of cards — the deck's page order, or the Library's
 * current page — and `Peek` carries which, because the two lists are
 * different types and only one of them has a quantity in a deck or
 * anything to swap.
 *
 * `PeekCard` is what both lists flatten to, so the sheet asks one
 * shape its questions rather than asking two and agreeing with
 * itself. `inDeck` is the one that is null over the Library, and it
 * is what the Count, Swap and Remove buttons hang off — not an
 * `admin` flag, because being admin in the Library still leaves
 * nothing to count.
 */
class LibraryPeekTest {

    private fun row(name: String, qty: Int = 2, free: Int? = 1) = CardRow(
        id = 1,
        owner = "matt",
        name = name,
        nameNorm = name.lowercase(),
        face2 = null,
        layout = null,
        scryfallId = "abcdef12-3456",
        manaCost = "{1}",
        cmc = 1.0,
        typeLine = "Artifact",
        colorIdentity = null,
        rarity = "uncommon",
        setCode = "m3c",
        setName = "Modern Horizons 3 Commander",
        collectorNumber = "409",
        edhrecRank = null,
        releasedAt = null,
        finish = "nonfoil",
        power = null,
        toughness = null,
        artist = null,
        qty = qty,
        printings = 1,
        free = free,
        price = 1.75,
        value = 3.5,
    )

    private val rows = listOf(row("Sol Ring"), row("Counterspell"), row("Cultivate"))

    private fun inTheLibrary() = AppState()
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows, rows.size)) }

    @Test
    fun tappingACardOpensTheCarouselOverTheLibrary() {
        val s = inTheLibrary().peekRow(rows[1])
        assertTrue(Overlay.CARD_PEEK in s.overlays)
        assertEquals(View.LIBRARY, s.view, "it navigated away from the Library")
        assertEquals("Counterspell", assertNotNull(s.peeked).title)
    }

    @Test
    fun theRunIsTheLibraryAndNotWhateverDeckWasOpen() {
        // The trap this exists for: a deck loaded from an earlier
        // visit is still in state, and a carousel that read
        // `pageOrder` regardless would show that deck's cards over
        // the Library.
        val withADeck = inTheLibrary().copy(
            decks = DecksState()
                .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                .opened("alela", listOf(DeckCard("Black Lotus", 1, null, 0, "black lotus"))),
        )
        val s = withADeck.peekRow(rows[0])
        assertEquals(listOf("Sol Ring", "Counterspell", "Cultivate"), s.peekRun.map { it.title })
        assertEquals("1 of 3", s.peekPlace)
    }

    @Test
    fun swipingMovesAlongTheLibrarysOwnOrder() {
        val s = inTheLibrary().peekRow(rows[0]).peekTo(2)
        assertEquals("Cultivate", assertNotNull(s.peeked).title)
    }

    @Test
    fun theSheetHasTheBasicsOffTheLibraryRow() {
        val c = assertNotNull(inTheLibrary().peekRow(rows[0]).peeked)
        assertEquals("M3C · 409", c.printing)
        assertEquals(1.75, c.price)
        assertEquals("Artifact", c.typeLine)
    }

    @Test
    fun aLibraryCardHasNothingToCountOrSwap() {
        // Not an admin check. Admin in the Library still leaves
        // nothing to set a count on, because there is no deck.
        assertNull(assertNotNull(inTheLibrary().peekRow(rows[0]).peeked).inDeck)
    }

    @Test
    fun aDeckCardStillCarriesItsRow() {
        val deck = AppState().navigate(Route(View.DECKS, "alela")).let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(DeckCard("Sol Ring", 2, null, 3, "sol ring"))),
            )
        }
        val c = assertNotNull(deck.peekAt(0).peeked)
        assertEquals("Sol Ring", assertNotNull(c.inDeck).name)
        assertEquals(2, c.inDeck!!.qty)
    }

    @Test
    fun theLibrarysTagsCountWhatYouOwnAndWhatIsFree() {
        // The counts come off the row the Library is holding, not
        // off the one handed to `peekRow` — which only says *which*
        // row was tapped. The first version of this loaded one set
        // of rows and asked about another.
        val four = row("Sol Ring", qty = 4, free = 2)
        val s = AppState().navigate(View.LIBRARY)
            .let { it.copy(library = it.library.loaded(listOf(four), 1)) }
        val c = assertNotNull(s.peekRow(four).peeked)
        assertTrue(c.tags.any { it.text == "4 owned" }, "it does not say how many you own: ${c.tags}")
        assertTrue(c.tags.any { it.text == "2 free" }, "it does not say how many are spare: ${c.tags}")
    }

    @Test
    fun aDecksTagsCountTheDeckAndTheCollection() {
        val deck = AppState().navigate(Route(View.DECKS, "alela")).let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(DeckCard("Sol Ring", 2, null, 3, "sol ring"))),
            )
        }
        val c = assertNotNull(deck.peekAt(0).peeked)
        assertTrue(c.tags.any { it.text == "2× in deck" }, "it does not say what the deck wants: ${c.tags}")
        assertTrue(c.tags.any { it.text == "3 owned" }, "it does not say what you own: ${c.tags}")
    }

    @Test
    fun aDeckShortOfCopiesSaysSoWithoutRelyingOnColour() {
        val deck = AppState().navigate(Route(View.DECKS, "alela")).let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(DeckCard("Sol Ring", 4, null, 1, "sol ring"))),
            )
        }
        val owned = assertNotNull(deck.peekAt(0).peeked).tags.first { it.text.endsWith("owned") }
        assertTrue(owned.bad, "being three copies short is not marked at all")
    }

    @Test
    fun aSearchThatReplacesTheRowsDoesNotStrandTheCarousel() {
        val s = inTheLibrary().peekRow(rows[2])
        val fewer = s.copy(library = s.library.loaded(listOf(row("Sol Ring")), 1))
        assertEquals("Sol Ring", assertNotNull(fewer.peeked).title)
        assertNull(s.copy(library = s.library.loaded(emptyList(), 0)).peeked)
    }

    @Test
    fun backTakesTheCarouselOffAndLeavesTheLibrary() {
        val s = inTheLibrary().peekRow(rows[1])
        val back = assertNotNull(s.back())
        assertFalse(Overlay.CARD_PEEK in back.overlays)
        assertEquals(View.LIBRARY, back.view)
    }

    @Test
    fun tappingACardTheLibraryDoesNotHaveDoesNothing() {
        assertFalse(Overlay.CARD_PEEK in inTheLibrary().peekRow(row("Black Lotus")).overlays)
    }
}
