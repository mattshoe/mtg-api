package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three things the phone grew that the website had not.
 *
 * Matt: "Have you not been updating the web app?????? PARITY IS
 * PARAMOUNT!!!"
 *
 * Fair. Three changes landed on Android alone and each of them is a
 * rule about what the app does rather than how one of them looks:
 * a deck is started from the entry wizard's first question and not
 * from a button on the decks page; tapping a card in a deck opens a
 * carousel over the deck rather than navigating to the card's page;
 * and the carousel's background swallows a press rather than
 * dismissing on one.
 *
 * `:core` already decides all three — `MassEntry.startingADeck`,
 * `AppState.peekAt`, `AppState.back` — so what is missing here is
 * only the website obeying them. Which is exactly the kind of gap
 * that goes unnoticed, because the shared tests are green either
 * way.
 */
class ParityWithThePhoneTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private var tweaked: Pair<DeckCard, Tweak?>? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun card(name: String, qty: Int = 1) = DeckCard(
        name = name,
        qty = qty,
        role = null,
        owned = 3,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
        price = 1.75,
        setCode = "m3c",
        setName = "Modern Horizons 3 Commander",
        collectorNumber = "409",
    )

    /** In page order: grouped by type, sorted by name inside a group. */
    private val run = listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring", qty = 2))

    private fun onADeck() = AppState(admin = Admin(token = "t").unlock("t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", run),
            )
        }

    private fun shell(start: AppState): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(start) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onUnlock = {},
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onTweak = { c, t -> tweaked = c to t },
            )
        }
        return frame
    }

    private fun buttons(): List<HTMLButtonElement> =
        document.querySelectorAll("button").let { list ->
            (0 until list.length).mapNotNull { list[it] as? HTMLButtonElement }
        }

    private fun button(label: String): HTMLButtonElement? =
        buttons().firstOrNull { it.textContent?.trim() == label }

    /**
     * One of the wizard's option rows, by its label.
     *
     * Not `button(...)`: an `.opt` carries a tick, a label and a line
     * of help, so its `textContent` is all three run together and an
     * exact match never finds it.
     */
    private fun option(label: String): HTMLButtonElement? =
        buttons().firstOrNull {
            it.classList.contains("opt") &&
                (it.querySelector(".opt-label") as? HTMLElement)?.textContent?.trim() == label
        }

    private fun says(text: String): Boolean =
        document.body?.textContent?.contains(text) == true

    // ------------------------------------- a deck starts from the entry page

    @Test
    fun theEntryWizardOffersANewDeck() = runTest {
        shell(AppState(admin = Admin(token = "t").unlock("t")).navigate(View.ENTRY))
        settle()
        assertTrue(says("New deck"), "the website's entry wizard does not offer a new deck")
        assertTrue(says("What are you doing?"), "the first question still asks the old one")
    }

    @Test
    fun pickingItAndContinuingOpensTheWizard() = runTest {
        shell(AppState(admin = Admin(token = "t").unlock("t")).navigate(View.ENTRY))
        settle()
        assertNotNull(option("New deck")).click()
        settle()
        assertTrue(held.value.entry.startingADeck, "the pick did not register")
        assertNotNull(button("Continue →")).click()
        settle()
        assertTrue(Overlay.NEW_DECK in held.value.overlays, "Continue did not launch the wizard")
    }

    @Test
    fun theDecksPageNoLongerOffersOne() = runTest {
        shell(onADeck().copy(decks = DecksState().loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))))
        settle()
        assertEquals(null, button("New deck"), "the decks page still has its own New deck button")
    }

    // ------------------------------------------------- the card carousel

    private suspend fun peek(): Unit {
        shell(onADeck())
        settle()
        val row = document.querySelectorAll(".deck-line").let { l ->
            (0 until l.length).map { l[it] as HTMLElement }
        }.firstOrNull { it.textContent?.contains("Cultivate") == true }
        assertNotNull(row).click()
        settle()
    }

    @Test
    fun tappingACardOpensTheCarouselRatherThanTheCardsPage() = runTest {
        peek()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the row did not open a carousel")
        assertEquals(View.DECKS, held.value.view, "it navigated away from the deck")
        assertEquals("Cultivate", held.value.peeked?.title)
    }

    @Test
    fun theDeckIsStillBehindIt() = runTest {
        peek()
        // The deck's own rows are still in the document under the
        // scrim. The website has no single wrapper to look for, and
        // a row is the thing that would be gone if this were a
        // navigation rather than an overlay.
        assertTrue(
            document.querySelectorAll(".deck-line").length > 0,
            "the deck is gone, so this is a page and not an overlay",
        )
    }

    @Test
    fun theSheetSaysTheBasicsAndOffersTheWayToTheCardsPage() = runTest {
        peek()
        assertTrue(says("M3C · 409"), "the sheet does not say which printing it is")
        assertTrue(says("$1.75"), "the sheet does not say what it is worth")
        assertTrue(says("1× in deck"), "the sheet does not say how many the deck wants")
        assertTrue(says("2 of 3"), "nothing says where in the deck this is")
        assertNotNull(button("Full details"), "no way through to the card's own page")
    }

    @Test
    fun theSheetChangesTheDeck() = runTest {
        peek()
        assertNotNull(button("Count")).click()
        settle()
        assertEquals(Tweak.QUANTITY, tweaked?.second)
        assertEquals("Cultivate", tweaked?.first?.name)
    }

    // --------------------------------------------- and over the Library

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

    private fun inTheLibrary(): AppState {
        val rows = listOf(row("Sol Ring"), row("Counterspell"))
        return AppState(admin = Admin(token = "t").unlock("t")).navigate(View.LIBRARY)
            .let { it.copy(library = it.library.loaded(rows, rows.size)) }
    }

    @Test
    fun tappingACardInTheLibraryOpensTheSameCarousel() = runTest {
        shell(inTheLibrary())
        settle()
        val tile = document.querySelectorAll("div.card").let { l ->
            (0 until l.length).map { l[it] as HTMLElement }
        }.first()
        tile.click()
        settle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the tile did not open a carousel")
        assertEquals(View.LIBRARY, held.value.view, "it navigated away from the Library")
        assertNotNull(document.querySelector(".peek-scrim"))
    }

    @Test
    fun theLibrarysSheetCountsTheCollectionAndOffersNothingToEdit() = runTest {
        shell(inTheLibrary())
        settle()
        (document.querySelectorAll("div.card")[0] as HTMLElement).click()
        settle()
        assertTrue(says("2 owned"), "the sheet does not say how many you own")
        assertTrue(says("1 free"), "the sheet does not say how many are spare")
        assertNotNull(button("Full details"), "no way through to the card's own page")
        // Admin is on and there is still no deck to change.
        listOf("Count", "Swap", "Remove").forEach {
            assertEquals(null, button(it), "the Library's sheet offers \"$it\"")
        }
    }

    @Test
    fun aPressOnTheBackgroundNeitherClosesItNorReachesTheDeck() = runTest {
        peek()
        (assertNotNull(document.querySelector(".peek-scrim")) as HTMLElement).click()
        settle()
        assertTrue(
            Overlay.CARD_PEEK in held.value.overlays,
            "a press on the background closed the carousel",
        )
        assertEquals("Cultivate", held.value.peeked?.title, "the press reached a row behind it")
    }
}
