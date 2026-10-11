package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
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

/**
 * A bar in the Creature types chart opens the carousel over that type.
 *
 * Matt: "tapping a 'human' bar would bring up a carousel with all
 * human creatures". The real `AppShell` over an open deck, a click on
 * the real bar, and the cards the carousel actually draws. The phone's
 * sibling is `TribeCarouselParityTest`.
 */
class TribeCarouselTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

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

    private fun card(name: String, type: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
    )

    private fun onADeck() = AppState(admin = Admin().signIn(Account("matt"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "WG", 3, null)))
                    .opened(
                        "alela",
                        listOf(
                            card("Champion of the Parish", "Creature — Human Soldier"),
                            card("Llanowar Elves", "Creature — Elf Druid"),
                            card("Thalia", "Legendary Creature — Human Soldier"),
                            card("Sol Ring", "Artifact"),
                        ),
                    ),
            )
        }

    private fun all(css: String): List<HTMLElement> =
        document.querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    @Test
    fun tappingTheHumanBarOpensACarouselOfTheHumans() = runTest {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(onADeck()) }
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
        val tribes = assertNotNull(
            all("div.stats-card").firstOrNull { it.querySelector("h3")?.textContent == "Creature types" },
            "the deck page has no Creature types chart",
        )
        val human = assertNotNull(
            tribes.querySelectorAll("div.hbar").let { n -> (0 until n.length).map { n[it] as HTMLElement } }
                .firstOrNull { it.querySelector(".k")?.textContent == "Human" },
            "no Human bar",
        )
        human.click()
        settle()
        assertEquals(
            listOf("Champion of the Parish", "Thalia"),
            held.value.peekRun.map { it.title },
            "tapping the Human bar did not open a carousel of the deck's Humans",
        )
        assertEquals(2, all(".peek-card").size, "the carousel on screen does not hold the two Humans")
    }
}
