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
 * Matt: "tapping the row (outside checkbox) should show the carousel,
 * and the bottom part should have the checkbox as well so i can swipe
 * through and check them off as i swipe. careful to keep stats in sync
 * between carousel and other page", and "i also want the ability to
 * remove a card from the fetch list. make sure doing so actually edits
 * the deck list text as well".
 *
 * Through `AppShell`, on a phone and on both tablet shapes. The web's
 * `FetchListCarouselTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class FetchListCarouselTest {

    @get:Rule
    val rule = createComposeRule()

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus"

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

    private fun ticked(tag: String): Boolean =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    private fun tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(size: DpSize?) {
        val held = shell(size)
        val on = on(size)
        rule.onNodeWithTag("fetch-row:Sol Ring").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "$on tapping Sol Ring's row opened no carousel")
        assertEquals(PeekOf.FETCH, held.value.peek.of)
        assertFalse(held.value.fetch.isTicked("Sol Ring"), "$on tapping the row ticked the box as well")

        assertTrue(
            rule.onAllNodesWithTag("carousel-tick").fetchSemanticsNodes().isNotEmpty(),
            "$on the carousel's sheet has no Found box",
        )
        val box = rule.onNodeWithTag("carousel-tick").getUnclippedBoundsInRoot()
        assertTrue(box.bottom - box.top >= 16.dp, "$on the carousel's box has no height: $box")
        if (size != null) {
            assertTrue(box.bottom <= size.height && box.right <= size.width, "$on the carousel's box is off screen: $box")
        }
        assertFalse(ticked("carousel-tick"), "$on the carousel's box starts ticked")

        rule.onNodeWithTag("carousel-tick").performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.isTicked("Sol Ring"), "$on the carousel's box did not tick Sol Ring")
        assertTrue(ticked("carousel-tick"), "$on the carousel's box did not show the tick")
        assertTrue(shows("1 of 2 found", "carousel-progress"), "$on the carousel's count did not move")
        assertTrue(shows("1 of 2 found", "fetch-progress"), "$on the page's count did not follow the carousel")
        assertTrue(ticked("fetch-tick:Sol Ring"), "$on the page's box did not follow the carousel")

        // Back a card, as a swipe does, and tick that one too.
        held.value = held.value.peekTo(0)
        rule.waitForIdle()
        rule.onNodeWithTag("carousel-tick").performClick()
        rule.waitForIdle()
        assertEquals(setOf("Counterspell", "Sol Ring"), held.value.fetch.ticked, on)
        assertTrue(shows("2 of 2 found", "fetch-progress"), "$on the page did not count the second tick")
    }

    private fun removeTakesTheCardOutOfTheListText(size: DpSize?) {
        val held = shell(size)
        val on = on(size)
        assertTrue(
            rule.onAllNodesWithTag("fetch-remove:Counterspell").fetchSemanticsNodes().isNotEmpty(),
            "$on Counterspell has no Remove",
        )
        val r = rule.onNodeWithTag("fetch-remove:Counterspell").performScrollTo().getUnclippedBoundsInRoot()
        if (size != null) assertTrue(r.right <= size.width, "$on Remove runs off the side: $r")
        rule.onNodeWithTag("fetch-remove:Counterspell").performClick()
        rule.waitForIdle()
        assertFalse(Overlay.CARD_PEEK in held.value.overlays, "$on Remove opened the carousel")
        assertTrue(
            rule.onAllNodesWithTag("fetch-card:Counterspell").fetchSemanticsNodes().isEmpty(),
            "$on Counterspell is still on the page",
        )
        assertTrue(shows("0 of 1 found", "fetch-progress"), "$on the page still counts Counterspell")

        rule.onNodeWithTag("fetch-remove:Black Lotus").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithTag("fetch-missing:Black Lotus").fetchSemanticsNodes().isEmpty(),
            "$on Black Lotus is still listed as missing",
        )

        rule.onNodeWithTag("fetch-edit").performScrollTo().performClick()
        rule.waitForIdle()
        val text = rule.onNodeWithTag("fetch-edit-list").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("1 Sol Ring", text, "$on the list text Edit opens still has the removed cards")
    }

    private fun theBoxStillTicksWithoutOpeningTheCarousel(size: DpSize?) {
        val held = shell(size)
        rule.onNodeWithTag("fetch-tick:Counterspell").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.isTicked("Counterspell"), "${on(size)} the page's box no longer ticks")
        assertFalse(Overlay.CARD_PEEK in held.value.overlays, "${on(size)} ticking the box opened the carousel")
    }

    @Test
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePage() = tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePageOnAFoldingPhoneOpenedOut() =
        tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePageOnATabletHeldSideways() =
        tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(Tablet.LANDSCAPE)

    @Test
    fun theBoxOnTheRowStillTicksWithoutOpeningTheCarousel() = theBoxStillTicksWithoutOpeningTheCarousel(null)

    @Test
    fun removeTakesTheCardOutOfTheListText() = removeTakesTheCardOutOfTheListText(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun removeTakesTheCardOutOfTheListTextOnAFoldingPhoneOpenedOut() = removeTakesTheCardOutOfTheListText(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun removeTakesTheCardOutOfTheListTextOnATabletHeldSideways() = removeTakesTheCardOutOfTheListText(Tablet.LANDSCAPE)
}
