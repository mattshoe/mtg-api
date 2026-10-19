package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list, i want to be able to swipe through the
 * missing cards just like the ones in my collection. however, it is a
 * different carousel of its own. i shouldn't be able to swipe into the
 * missing ones while swiping through the collection ones and vice
 * versa", and "i also want to be able to check off the missing ones
 * just like the collection ones".
 *
 * Through `AppShell`, on a phone and on both tablet shapes. The web's
 * `FetchListMissingCarouselTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class FetchListMissingCarouselTest {

    @get:Rule
    val rule = createComposeRule()

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus\n1 Mox Pearl"

    private fun shell(size: DpSize?): MutableState<AppState> {
        val held = mutableStateOf(
            AppState(
                admin = Admin().signIn(Account(key = "e7de0cb1"), "t"),
                route = Route(View.FETCH, FetchListState.PAGE),
                fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
            ),
        )
        val content: @Composable () -> Unit = {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
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
        rule.setContent { if (size == null) content() else Tablet.Sized(size) { content() } }
        rule.waitForIdle()
        return held
    }

    private fun on(size: DpSize?) = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"

    private fun shows(text: String, under: String): Boolean =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun place(text: String): Boolean =
        rule.onAllNodes(hasText(text) and hasTestTag("carousel-place"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun ticked(tag: String): Boolean =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    private fun pictures(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(CardArt), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[CardArt] }

    private fun tappingAMissingCardOpensItsOwnCarousel(size: DpSize?) {
        val held = shell(size)
        val on = on(size)
        rule.onNodeWithTag("fetch-missing-row:Black Lotus").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "$on tapping Black Lotus opened no carousel")
        assertEquals(PeekOf.FETCH_MISSING, held.value.peek.of, "$on the carousel is not over the missing cards")
        assertFalse(held.value.fetch.isMissingTicked("Black Lotus"), "$on tapping the row checked it off as well")
        assertTrue(place("1 of 2"), "$on the carousel does not count the two missing cards")
        assertTrue(
            pictures().any { "named?exact=Black%20Lotus" in it },
            "$on Black Lotus is drawn with no picture: ${pictures()}",
        )

        // On a swipe, past the end of the missing cards.
        held.value = held.value.peekTo(held.value.peek.at + 5)
        rule.waitForIdle()
        assertTrue(place("2 of 2"), "$on swiping on from the last missing card left the missing carousel")
        assertEquals("Mox Pearl", held.value.peeked?.title)

        val box = rule.onNodeWithTag("carousel-tick").getUnclippedBoundsInRoot()
        assertTrue(box.bottom - box.top >= 16.dp, "$on the carousel's box has no height: $box")
        if (size != null) {
            assertTrue(box.bottom <= size.height && box.right <= size.width, "$on the carousel's box is off screen: $box")
        }
        rule.onNodeWithTag("carousel-tick").performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.isMissingTicked("Mox Pearl"), "$on the carousel's box did not check off Mox Pearl")
        assertTrue(ticked("carousel-tick"), "$on the carousel's box did not show the check")
        assertTrue(shows("1 of 2 checked off", "carousel-progress"), "$on the carousel's count did not move")
        assertTrue(ticked("fetch-missing-tick:Mox Pearl"), "$on the page's box did not follow the carousel")
        assertTrue(shows("0 of 2 found", "fetch-progress"), "$on checking a missing card counted it found")
    }

    private fun theCollectionCarouselNeverReachesAMissingCard(size: DpSize?) {
        val held = shell(size)
        rule.onNodeWithTag("fetch-row:Sol Ring").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(PeekOf.FETCH, held.value.peek.of)
        assertTrue(place("2 of 2"), "${on(size)} the collection carousel counts the missing cards")
        held.value = held.value.peekTo(held.value.peek.at + 5)
        rule.waitForIdle()
        assertEquals("Sol Ring", held.value.peeked?.title, "${on(size)} swiping on from the last collection card reached a missing one")
    }

    private fun theMissingBoxChecksOffWithoutOpeningTheCarousel(size: DpSize?) {
        val held = shell(size)
        rule.onNodeWithTag("fetch-missing-tick:Black Lotus").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.isMissingTicked("Black Lotus"), "${on(size)} the missing card's box does not check it off")
        assertFalse(Overlay.CARD_PEEK in held.value.overlays, "${on(size)} checking the box opened the carousel")
        assertTrue(ticked("fetch-missing-tick:Black Lotus"), "${on(size)} the box does not show the check")
    }

    @Test
    fun aMissingCardOpensItsOwnCarousel() = tappingAMissingCardOpensItsOwnCarousel(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aMissingCardOpensItsOwnCarouselOnAFoldingPhoneOpenedOut() = tappingAMissingCardOpensItsOwnCarousel(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aMissingCardOpensItsOwnCarouselOnATabletHeldSideways() = tappingAMissingCardOpensItsOwnCarousel(Tablet.LANDSCAPE)

    @Test
    fun theCollectionCarouselNeverReachesAMissingCard() = theCollectionCarouselNeverReachesAMissingCard(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theCollectionCarouselNeverReachesAMissingCardOnATablet() = theCollectionCarouselNeverReachesAMissingCard(Tablet.UNFOLDED)

    @Test
    fun theMissingBoxChecksOff() = theMissingBoxChecksOffWithoutOpeningTheCarousel(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theMissingBoxChecksOffOnAFoldingPhoneOpenedOut() = theMissingBoxChecksOffWithoutOpeningTheCarousel(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theMissingBoxChecksOffOnATabletHeldSideways() = theMissingBoxChecksOffWithoutOpeningTheCarousel(Tablet.LANDSCAPE)
}
