package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import androidx.compose.ui.test.assertTextEquals
import kotlin.test.assertEquals

/**
 * A bar in the Creature types chart opens the carousel over that type.
 *
 * Matt: "tapping a 'human' bar would bring up a carousel with all
 * human creatures". Through the real shell over an open deck, a tap on
 * the real bar, and the place the carousel says it is at. The
 * website's sibling is `TribeCarouselTest`.
 */
@RunWith(AndroidJUnit4::class)
class TribeCarouselParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun card(name: String, type: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
    )

    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState()
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

    @Test
    fun tappingTheHumanBarOpensACarouselOfTheHumans() {
        val start = opened()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val bar = rule.onNodeWithTag("tribe-Human", useUnmergedTree = true)
        runCatching { bar.performScrollTo() }
        bar.performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("Champion of the Parish", "Thalia"),
            held.value.peekRun.map { it.title },
            "tapping the Human bar did not open a carousel of the deck's Humans",
        )
        rule.onNodeWithTag("carousel-place", useUnmergedTree = true).assertTextEquals("1 of 2")
    }
}
