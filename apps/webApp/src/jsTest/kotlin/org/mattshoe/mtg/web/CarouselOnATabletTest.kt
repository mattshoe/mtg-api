package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The carousel's card on a tablet, the website's half.
 *
 * Matt: "in tablet mode the carousel looks broken and zoomed in". The
 * card was as wide as its cell and as tall as that made it, so on a
 * screen nearly as wide as it is tall it ran past the space above the
 * sheet and was clipped: half the art and the rules text gone.
 *
 * The real `AppShell` in a frame of a tablet's size with the real
 * stylesheet, and the assertions are the boxes the browser laid out.
 * Two cards in the rail: one with a picture, one nobody owns, which
 * is drawn as its name on a card-shaped blank. The phone's
 * `CarouselOnATabletTest` is the same claim.
 */
class CarouselOnATabletTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun onADeck() = AppState(admin = Admin().signIn(Account("matt"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 2, null)))
                    .opened(
                        "alela",
                        listOf(
                            DeckCard("Bloodghast", 1, null, 3, "bloodghast", typeLine = "Creature", scryfallId = "abcdef12-3456"),
                            DeckCard("Cultivate", 1, null, 0, "cultivate", typeLine = "Sorcery", scryfallId = null),
                        ),
                    ),
            )
        }

    private suspend fun wholeCardShows(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(onADeck().peekAt(0)) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onTweak = { _, _ -> },
            )
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val sheet = assertNotNull(doc.querySelector(".peek-sheet") as? HTMLElement, "the carousel did not open")
            .getBoundingClientRect()
        val rail = assertNotNull(doc.querySelector(".peek-rail") as? HTMLElement).getBoundingClientRect()
        val cells = doc.querySelectorAll(".peek-card")
        assertTrue(cells.length == 2, "expected two cards in the rail, found ${cells.length}")
        val room = sheet.top - rail.top
        for (i in 0 until cells.length) {
            val cell = (cells[i] as HTMLElement).getBoundingClientRect()
            val face = assertNotNull((cells[i] as HTMLElement).firstElementChild as? HTMLElement).getBoundingClientRect()
            val what = if (i == 0) "the card with a picture" else "the card nobody owns"
            val where = "on a ${size.first} by ${size.second} screen"
            assertTrue(
                face.bottom <= sheet.top + 0.5,
                "$where $what runs ${face.bottom - sheet.top}px behind the sheet: ${face.height}px tall with ${room}px above it",
            )
            assertTrue(face.top >= rail.top - 0.5, "$where $what starts ${rail.top - face.top}px above the space it has")
            assertTrue(
                face.left >= cell.left - 0.5 && face.right <= cell.right + 0.5,
                "$where $what is wider than its place in the rail",
            )
            assertTrue(
                face.height >= room * 0.85,
                "$where $what is ${face.height}px tall with ${room}px to fill, so it is smaller than it needs to be",
            )
            assertTrue(
                abs(face.width / face.height - 488.0 / 680.0) < 0.02,
                "$where $what is not card-shaped: ${face.width} by ${face.height}",
            )
        }
    }

    @Test
    fun theWholeCardShowsOnAFoldingPhoneOpenedOut() = runTest { wholeCardShows(Tablet.UNFOLDED) }

    @Test
    fun theWholeCardShowsOnATabletHeldSideways() = runTest { wholeCardShows(Tablet.LANDSCAPE) }
}
