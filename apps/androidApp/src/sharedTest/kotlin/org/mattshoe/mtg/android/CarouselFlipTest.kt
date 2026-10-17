package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
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
 * Turning a two-faced card over in the carousel.
 *
 * Matt: "the carousel should have an icon overlaid on double sided
 * cards that allows you to flip the card so you can see the opposite
 * side back and forth".
 *
 * The real `AppShell` over a deck holding Delver of Secrets, which
 * transforms, and Sol Ring, which does not. The picture asserted is
 * the address handed to the image loader, read back off the card's
 * semantics. The website's `CarouselFlipTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class CarouselFlipTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun card(name: String, id: String, layout: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = "Creature",
        scryfallId = id,
        layout = layout,
    )

    private val delver = card("Delver of Secrets", "d1e2-delver", "transform")
    private val sol = card("Sol Ring", "50a1-sol", "normal")

    private fun peeking(name: String, size: DpSize?) {
        val state = AppState(admin = Admin().signIn(Account("matt"), "t"))
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 2, null)))
                        .opened("alela", listOf(delver, sol)),
                )
            }
        val start = state.peekAt(state.decks.pageOrder.indexOfFirst { it.name == name })
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
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
            if (size == null) shell() else Tablet.Sized(size) { shell() }
        }
        rule.waitForIdle()
    }

    private fun shown(title: String): String? {
        val node = rule.onNode(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf(title))
                .and(SemanticsMatcher.keyIsDefined(CardArt)),
        ).fetchSemanticsNode()
        return node.config[CardArt]
    }

    private fun flipsBackAndForth(size: DpSize?) {
        peeking("Delver of Secrets", size)
        val front = "https://cards.scryfall.io/normal/front/d/1/d1e2-delver.jpg"
        val back = "https://cards.scryfall.io/normal/back/d/1/d1e2-delver.jpg"
        assertEquals(front, shown("Delver of Secrets"), "the carousel did not open on Delver's front")

        val flip = rule.onNodeWithContentDescription("Flip card")
        // Overlaid: on the card, not on the sheet under it.
        val icon = flip.getUnclippedBoundsInRoot()
        val face = rule.onNode(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Delver of Secrets"))
                .and(SemanticsMatcher.keyIsDefined(CardArt)),
        ).getUnclippedBoundsInRoot()
        assertTrue(
            icon.left >= face.left && icon.right <= face.right && icon.top >= face.top && icon.bottom <= face.bottom,
            "the flip icon at $icon is not over the card at $face",
        )

        flip.performClick()
        rule.waitForIdle()
        assertEquals(back, shown("Delver of Secrets"), "flipped, the carousel still shows the front")
        rule.onNodeWithContentDescription("Flip card").performClick()
        rule.waitForIdle()
        assertEquals(front, shown("Delver of Secrets"), "flipped twice, the carousel did not come back to the front")
    }

    @Test
    fun aTwoFacedCardTurnsOverAndBackOnAPhone() = flipsBackAndForth(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aTwoFacedCardTurnsOverAndBackOnAFoldingPhoneOpenedOut() = flipsBackAndForth(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aTwoFacedCardTurnsOverAndBackOnATabletHeldSideways() = flipsBackAndForth(Tablet.LANDSCAPE)

    @Test
    fun aOneFacedCardHasNoFlipIcon() {
        peeking("Sol Ring", null)
        assertEquals("https://cards.scryfall.io/normal/front/5/0/50a1-sol.jpg", shown("Sol Ring"), "the carousel did not open on Sol Ring")
        rule.onNodeWithContentDescription("Flip card").assertDoesNotExist()
    }
}
