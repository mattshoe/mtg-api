package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The carousel's card on a tablet.
 *
 * Matt: "in tablet mode the carousel looks broken and zoomed in". The
 * card was as wide as the screen allowed and as tall as that made it,
 * so on a screen nearly as wide as it is tall the card ran far past
 * the space above the sheet and was clipped top and bottom: half the
 * art and most of the rules text gone, which reads as zoomed in.
 *
 * The real `AppShell`, on a screen forced to a tablet's size.
 */
@RunWith(AndroidJUnit4::class)
class CarouselOnATabletTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 3,
        nameNorm = name.lowercase(),
        typeLine = "Creature",
        scryfallId = "abcdef12-3456",
    )

    private fun peeking(size: DpSize) {
        val state = AppState(admin = Admin().signIn(Account("matt"), "t"))
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                        .opened("alela", listOf(card("Bloodghast"), card("Cultivate"), card("Sol Ring"))),
                )
            }
        rule.setContent {
            Tablet.Sized(size) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(state.peekAt(0))
                        }
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
        }
        rule.waitForIdle()
    }

    private fun wholeCardShows(size: DpSize) {
        peeking(size)
        val pager = rule.onNodeWithTag("carousel-pager").getUnclippedBoundsInRoot()
        val sheet = rule.onNodeWithTag("carousel-sheet").getUnclippedBoundsInRoot()
        val title = held.value.peeked?.title ?: error("the carousel did not open")
        val face = rule.onNodeWithContentDescription(title).getUnclippedBoundsInRoot()
        val room = sheet.top - pager.top
        assertTrue(
            face.bottom <= sheet.top,
            "on a ${size.width} by ${size.height} screen the card runs ${face.bottom - sheet.top} " +
                "behind the sheet: ${face.height} tall with $room above the sheet",
        )
        assertTrue(face.top >= pager.top, "the card starts ${pager.top - face.top} above the space it has")
        assertTrue(face.left >= pager.left && face.right <= pager.right, "the card is wider than the screen")
        // And not shrunk to a stamp to get there: it fills the height
        // it has, which is the short side on a tablet.
        assertTrue(
            face.height >= room * 0.85f,
            "the card is ${face.height} tall with $room to fill, so it is smaller than it needs to be",
        )
        assertEquals(488f / 680f, face.width.value / face.height.value, 0.02f, "the card is not card-shaped")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theWholeCardShowsOnAFoldingPhoneOpenedOut() = wholeCardShows(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theWholeCardShowsOnATabletHeldSideways() = wholeCardShows(Tablet.LANDSCAPE)
}
