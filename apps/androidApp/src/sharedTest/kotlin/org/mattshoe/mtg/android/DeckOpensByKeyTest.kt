package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals

/**
 * A deck is opened by its key, so two decks with one name are two decks.
 *
 * Matt: "WE'RE GOING TO HAVE FUCKING COLLISIONS IN URLS ALL OVER THE
 * FUCKING PLACE". A deck's address was a slug made from its name, and
 * the slug was unique across every account, so a second Milly Moth
 * could not exist. Now each has a random key, and the key is what a
 * tap hands to the activity and what the address says.
 *
 * Through the real `AppShell`, pressing the real tile.
 */
@RunWith(AndroidJUnit4::class)
class DeckOpensByKeyTest {

    @get:Rule
    val rule = createComposeRule()

    private val mine = Deck("q8ytka9m", "Milly Moth", "e7de0cb1", "Alela", "UB", 3, null, ownerName = "Matt")
    private val alsoMilly = Deck("b3zz7f0c", "Milly Moth", "e7de0cb1", "Alela", "UB", 3, null, ownerName = "Matt")

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private val opened = mutableListOf<String>()

    private fun shell() {
        val start = AppState(
            route = Route(View.DECKS),
            admin = Admin().signIn(Account(key = "e7de0cb1", name = "Matt"), "t"),
            decks = DecksState().loaded(listOf(mine, alsoMilly)),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = { key ->
                            opened += key
                            held.value = held.value
                                .copy(decks = held.value.decks.opened(key, emptyList()))
                                .navigate(Route(View.DECKS, key))
                        },
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun twoDecksWithOneNameOpenAtTheirOwnKeys() {
        shell()
        val tiles = rule.onAllNodes(hasText("Milly Moth") and hasClickAction())
        assertEquals(2, tiles.fetchSemanticsNodes().size, "two decks of one name did not both get a tile")

        tiles[1].performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf("b3zz7f0c"), opened, "the tile did not hand over its own deck's key")
        assertEquals("b3zz7f0c", held.value.route.rest, "the address is not the deck's key")
        assertEquals("b3zz7f0c", held.value.decks.open?.key, "the open deck is not the one that was tapped")
    }
}
