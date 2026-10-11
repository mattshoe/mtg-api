package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A swipe on the carousel must not write back the state it opened with.
 *
 * Matt: "the more cards i swap, the glitchier the UI becomes". The
 * rail's scroll listener is attached once, in `ref`, so the `at` and
 * the `onSwipe` it called were the first frame's — and `onSwipe` is
 * `onState(state.peekTo(it))`, a whole `AppState` as it was when the
 * carousel opened. After a swap that is the deck before the swap, put
 * back on screen and used to plan the next one.
 *
 * The phone's `NoStaleStateAfterASwapTest` is the same claim with a
 * real finger on the pager.
 */
class NoStaleStateAfterASwapTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: MutableState<AppState>

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

    /** Longer than the rail's 80ms wait for a scroll to come to rest. */
    private suspend fun rest() =
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 250) }.await()

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Sorcery",
        scryfallId = "abcdef12-3456",
    )

    private val before = listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring"))
    private val after = listOf(card("Counterspell"), card("Rampant Growth"), card("Sol Ring"))

    private fun onADeck() = AppState(admin = Admin().signIn(Account("matt", role = "admin"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", before),
            )
        }

    private fun names() = held.value.decks.cards.map { it.name }

    @Test
    fun swipingTheCarouselAfterASwapKeepsTheSwap() = runTest {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = remember { mutableStateOf(onADeck()) }
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
        settle()
        document.querySelectorAll(".deck-line").let { l -> (0 until l.length).map { l[it] as HTMLElement } }
            .first { it.textContent?.contains("Cultivate") == true }
            .click()
        settle()
        assertEquals("Cultivate", held.value.peeked?.title, "the carousel did not open on Cultivate")

        // The swap lands: `applyTweak` reopens the deck with what is stored now.
        held.value = held.value.copy(decks = held.value.decks.opened("alela", after))
        settle()

        val rail = assertNotNull(document.querySelector(".peek-rail") as? HTMLElement, "no rail")
        rail.scrollLeft = 2.0 * rail.clientWidth
        rest()
        settle()

        assertEquals(2, held.value.peek.at, "the swipe to the third card never reached the state")
        assertTrue(
            "Rampant Growth" in names(),
            "a swipe put the deck back the way it was when the carousel opened: ${names()}",
        )
    }
}
