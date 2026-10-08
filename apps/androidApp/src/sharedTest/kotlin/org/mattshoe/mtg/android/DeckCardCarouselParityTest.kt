package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tapping a card in a deck opens the carousel.
 *
 * Matt: "When tapping my cards in a deck, i want them to show in a
 * 'carousel' [...] It should be an overlay so u can see the screen
 * behind it. The 'bottom sheet' thingy underneath it should only
 * have some very very basic information like name, value, set, etc.
 * But it should have a button or something to navigate to the full
 * details. The bottom sheet thingy should also let you change count,
 * swap the card, remove it etc"; "this should be what happens when
 * you tap a card in the deck list."
 *
 * Driven through `AppShell`, because the thing being claimed is what
 * a press on a row does — which is exactly the claim a test that
 * mounts the carousel on its own cannot make.
 */
@RunWith(AndroidJUnit4::class)
class DeckCardCarouselParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private var tweaked: Pair<DeckCard, Tweak?>? = null

    private fun card(
        name: String,
        qty: Int = 1,
        owned: Int = 3,
        price: Double? = 1.75,
        set: String? = "m3c",
        number: String? = "409",
    ) = DeckCard(
        name = name,
        qty = qty,
        role = null,
        owned = owned,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
        price = price,
        setCode = set,
        setName = "Modern Horizons 3 Commander",
        collectorNumber = number,
    )

    /** In `pageOrder`, which groups by type and sorts by name inside a group. */
    private val run = listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring", qty = 2))

    private fun onADeck(admin: Boolean = true): AppState {
        // Signed in as the deck's own owner: the sheet's Count, Swap
        // and Remove ask whether you own this collection.
        val base = AppState(admin = if (admin) Admin().signIn(Account("matt"), "t") else Admin())
        return base.navigate(Route(View.DECKS, "alela")).let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", run),
            )
        }
    }

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onTweak = { c, t -> tweaked = c to t },
                        // Wired the way `MainActivity` wires it: the
                        // carousel closes and then the card opens.
                        onOpenPeeked = { c ->
                            held.value = held.value.closing(Overlay.CARD_PEEK)
                                .openCard(CardRef(c.nameNorm), c.title)
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * Scroll to a control if it is in something that scrolls, then
     * press it.
     *
     * The deck's rows are in a scrolling column and the carousel's
     * sheet is not — it is pinned to the bottom of an overlay — so a
     * plain `performScrollTo` fails on exactly the buttons this test
     * exists to press.
     */
    private fun tap(text: String) {
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    /**
     * A press on bare scrim, in the corner.
     *
     * Not `performClick` on the carousel: that lands in the middle,
     * where the pager is, and the pager eats it — so the first
     * version of this test was green against a scrim that did
     * dismiss, because the press never reached it.
     */
    private fun pressTheScrim() {
        rule.onNodeWithTag("card-carousel").performTouchInput { click(Offset(4f, 4f)) }
        rule.waitForIdle()
    }

    private fun says(text: String) =
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun peeking() = shell(onADeck()).also { tap("Cultivate") }

    // --------------------------------------------------- what a tap does

    @Test
    fun tappingACardOpensTheCarousel() {
        shell(onADeck())
        tap("Cultivate")
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the row did not open a carousel")
        rule.onNodeWithTag("card-carousel").assertExists()
    }

    @Test
    fun tappingACardDoesNotLeaveTheDeck() {
        // It used to navigate to `#/card/...`. The claim is that it
        // is an overlay now, so the deck has to still be there.
        shell(onADeck())
        tap("Cultivate")
        assertEquals(View.DECKS, held.value.view)
        rule.onNodeWithTag("deck-detail").assertExists()
    }

    @Test
    fun theCarouselOpensOnTheCardYouTapped() {
        peeking()
        assertEquals("Cultivate", held.value.peeked?.title)
        assertTrue(says("Cultivate"), "the sheet does not name the card")
    }

    // ------------------------------------------------------- the sheet

    @Test
    fun theSheetSaysTheBasicsAndNothingMore() {
        peeking()
        assertTrue(says("M3C · 409 · $1.75"), "the sheet does not say the printing and the value")
        assertTrue(says("1× in deck"), "the sheet does not say how many the deck wants")
        assertTrue(says("3 owned"), "the sheet does not say how many you have")
        // The card's own page is where the rest lives.
        assertTrue(!says("Rulings"), "the sheet is trying to be the card page")
        assertTrue(!says("Legality"), "the sheet is trying to be the card page")
    }

    @Test
    fun theSheetSaysWhereYouAreInTheDeck() {
        peeking()
        assertTrue(says("2 of 3"), "nothing says which of the deck's cards this is")
    }

    @Test
    fun fullDetailsOpensTheCardsOwnPage() {
        peeking()
        tap("Full details")
        assertEquals(View.CARD, held.value.view)
        assertTrue(
            Overlay.CARD_PEEK !in held.value.overlays,
            "the carousel is still over the card page",
        )
    }

    @Test
    fun theSheetChangesTheDeck() {
        peeking()
        tap("Count")
        assertEquals(Tweak.QUANTITY, tweaked?.second)
        assertEquals("Cultivate", tweaked?.first?.name)
    }

    @Test
    fun theSheetSwapsAndRemoves() {
        peeking()
        tap("Swap")
        assertEquals(Tweak.SWAP, tweaked?.second)
        tap("Remove")
        assertEquals(Tweak.REMOVE, tweaked?.second)
    }

    @Test
    fun aLockedAppCanLookButNotTouch() {
        shell(onADeck(admin = false))
        tap("Cultivate")
        assertTrue(says("Full details"), "a locked app cannot even read the card")
        listOf("Count", "Swap", "Remove").forEach {
            assertTrue(!says(it), "a locked app is offered \"$it\"")
        }
    }

    @Test
    fun theCardDoesNotSitUnderTheSheet() {
        // The card was aligned to the top of the screen and the
        // sheet to the bottom, which on a short phone put the
        // card's lower third behind the sheet — the part with the
        // rules text on it.
        peeking()
        val card = rule.onNodeWithTag("carousel-pager").getUnclippedBoundsInRoot()
        val sheet = rule.onNodeWithTag("carousel-sheet").getUnclippedBoundsInRoot()
        assertTrue(
            card.bottom.value <= sheet.top.value + 1f,
            "the card runs to ${card.bottom} and the sheet starts at ${sheet.top}",
        )
    }

    @Test
    fun theCardDoesNotSitUnderTheClock() {
        peeking()
        val card = rule.onNodeWithTag("carousel-pager").getUnclippedBoundsInRoot()
        assertTrue(card.top.value > 0f, "the card starts at ${card.top}, behind the status bar")
    }

    // ------------------------------------------------------- the picture

    @Test
    fun theLibrarysCarouselIsPhotographed() {
        Parity.needsRealRendering()
        shell(inTheLibrary())
        rule.onNodeWithTag("library").performScrollToNode(hasTestTag("card-tile"))
        rule.onAllNodes(hasTestTag("card-tile")).onFirst().performClick()
        rule.waitForIdle()
        rule.onRoot().shoot("library-carousel")
    }

    @Test
    fun theCarouselIsPhotographed() {
        // Evidence, not an assertion. Everything above already says
        // what the carousel does; this is so a person can look at it.
        Parity.needsRealRendering()
        peeking()
        rule.onRoot().shoot("deck-carousel")
    }

    // ------------------------------------------------- and in the Library

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
        return AppState(admin = Admin().signIn(Account("matt"), "t")).navigate(View.LIBRARY)
            .let { it.copy(library = it.library.loaded(rows, rows.size)) }
    }

    @Test
    fun tappingACardInTheLibraryOpensTheSameCarousel() {
        // Matt: "let's use the same carousel for the library page."
        shell(inTheLibrary())
        rule.onNodeWithTag("library").performScrollToNode(hasTestTag("card-tile"))
        rule.onAllNodes(hasTestTag("card-tile")).onFirst().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the tile did not open a carousel")
        rule.onNodeWithTag("card-carousel").assertExists()
        assertEquals(View.LIBRARY, held.value.view, "it navigated away from the Library")
    }

    @Test
    fun theLibrarysSheetCountsTheCollectionAndOffersNothingToEdit() {
        shell(inTheLibrary())
        rule.onNodeWithTag("library").performScrollToNode(hasTestTag("card-tile"))
        rule.onAllNodes(hasTestTag("card-tile")).onFirst().performClick()
        rule.waitForIdle()
        assertTrue(says("2 owned"), "the sheet does not say how many you own")
        assertTrue(says("1 free"), "the sheet does not say how many are spare")
        assertTrue(says("Full details"), "no way through to the card's own page")
        // Admin is on and there is still no deck to change.
        listOf("Count", "Swap", "Remove").forEach {
            assertTrue(!says(it), "the Library's sheet offers \"$it\"")
        }
    }

    // ------------------------------------------------------- getting out

    @Test
    fun pressingTheBackgroundDoesNotCloseIt() {
        // Matt: "i don't want click throughs to dismiss the
        // carousel. Only the back button." A card you are reading is
        // not a menu you dismiss by looking away, and the scrim is
        // most of the screen.
        peeking()
        pressTheScrim()
        assertTrue(
            Overlay.CARD_PEEK in held.value.overlays,
            "a press on the background closed the carousel",
        )
    }

    @Test
    fun pressingTheBackgroundDoesNotReachTheDeckUnderIt() {
        // The other half of "click throughs": the press has to stop
        // at the scrim. Falling through to the deck would open a
        // different card under the one you are looking at.
        peeking()
        pressTheScrim()
        assertEquals("Cultivate", held.value.peeked?.title, "the press reached a row behind it")
    }

    @Test
    fun backTakesTheCarouselOffAndLeavesTheDeck() {
        peeking()
        val back = held.value.back()
        assertTrue(back != null && Overlay.CARD_PEEK !in back.overlays)
        assertEquals("alela", back!!.decks.openSlug)
    }
}
