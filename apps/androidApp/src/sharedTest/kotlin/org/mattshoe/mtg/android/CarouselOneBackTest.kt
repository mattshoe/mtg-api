package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One press of back closes the carousel.
 *
 * Matt: "i always have to hit back twice to get out of the carousel".
 * The Library's suggestion list stayed open under the carousel, and
 * after a trip to Decks it stayed open where it is not drawn at all,
 * and `AppState.back` closes that list before anything else. So the
 * first press closed a list nobody could see and the second closed
 * the carousel.
 *
 * The real `AppShell` over a `ComponentActivity`, so back is the
 * dispatcher `MainActivity` uses and the tile is the one a finger
 * taps. On a phone and on both tablet shapes.
 */
@RunWith(AndroidJUnit4::class)
class CarouselOneBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var held: MutableState<AppState>

    private fun row(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = null, scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = null, rarity = "uncommon", setCode = "m3c",
        setName = "Modern Horizons 3 Commander", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null, artist = null,
        qty = 1, printings = 1, free = 1, price = 1.75, value = 1.75,
    )

    private val rows = listOf(row("Sol Ring"), row("Solemn Simulacrum"))

    /** Typed "sol" in the Library and the suggestions came up. */
    private fun suggestingInTheLibrary() = AppState()
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows, rows.size)) }
        .typedCardName(Completion().typed("sol").suggested(listOf("Sol Ring", "Solemn Simulacrum")))

    private fun shell(start: AppState, size: DpSize?) {
        rule.setContent {
            val app = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = remember { mutableStateOf(start) }
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
            if (size == null) app() else Tablet.Sized(size) { app() }
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    private fun carouselIsUp(): Boolean =
        rule.onAllNodesWithTag("card-carousel").fetchSemanticsNodes().isNotEmpty()

    private fun oneBackFromTheLibrary(size: DpSize?) {
        shell(suggestingInTheLibrary(), size)
        assertTrue(held.value.complete.open, "the fixture never opened the suggestion list")

        // The grid sits under the filters, below the fold when a name
        // is typed: the Text section opens to show it.
        rule.onNodeWithTag("library").performScrollToNode(hasTestTag("card-tile"))
        rule.onAllNodesWithTag("card-tile")[0].performClick()
        rule.waitForIdle()
        assertTrue(carouselIsUp(), "tapping the tile did not open the carousel")

        pressBack()
        assertFalse(
            carouselIsUp(),
            "one back left the carousel up: it closed the suggestion list behind it instead",
        )
        assertEquals(View.LIBRARY, held.value.view, "one back went past the carousel and out of the Library")
    }

    private fun oneBackFromADeckAfterTheLibrary(size: DpSize?) {
        shell(suggestingInTheLibrary(), size)
        assertTrue(held.value.complete.open, "the fixture never opened the suggestion list")

        // Off to a deck the way the nav does it, then a card in it.
        val onADeck = held.value.navigate(Route(View.DECKS, "alela")).let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring"))),
            )
        }
        held.value = onADeck.peekAt(0)
        rule.waitForIdle()
        assertTrue(carouselIsUp(), "the deck's carousel did not open")

        pressBack()
        assertFalse(
            carouselIsUp(),
            "one back left the deck's carousel up: it closed the Library's suggestion list, which is not on screen",
        )
        assertTrue(Overlay.CARD_PEEK !in held.value.overlays)
        assertEquals("alela", held.value.decks.openKey, "one back went past the carousel and out of the deck")
    }

    @Test
    fun oneBackClosesTheCarouselOverTheLibraryOnAPhone() = oneBackFromTheLibrary(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun oneBackClosesTheCarouselOverTheLibraryOnAFoldingPhoneOpenedOut() = oneBackFromTheLibrary(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun oneBackClosesTheCarouselOverTheLibraryOnATabletHeldSideways() = oneBackFromTheLibrary(Tablet.LANDSCAPE)

    @Test
    fun oneBackClosesADecksCarouselOnAPhone() = oneBackFromADeckAfterTheLibrary(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun oneBackClosesADecksCarouselOnAFoldingPhoneOpenedOut() = oneBackFromADeckAfterTheLibrary(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun oneBackClosesADecksCarouselOnATabletHeldSideways() = oneBackFromADeckAfterTheLibrary(Tablet.LANDSCAPE)
}
