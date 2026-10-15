package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list carousel, i want to be able to remove cards
 * from the list. if i do so, the carousel should update in place with
 * the card removed, but i should keep my place in the carousel, just
 * moved forward one card to the next (unless i remove the last
 * obviously)".
 *
 * Through `AppShell`, on a phone and on both tablet shapes. The web's
 * `FetchListCarouselRemoveTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class FetchListCarouselRemoveTest {

    @get:Rule
    val rule = createComposeRule()

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    // Page order: Counterspell, Arcane Signet, Sol Ring.
    private val list = "1 Counterspell\n1 Arcane Signet\n1 Sol Ring"

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
        rule.onAllNodes(hasText(text) and (hasTestTag(under) or hasAnyAncestor(hasTestTag(under))), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun removeKeepsYourPlace(size: DpSize?) {
        val held = shell(size)
        val on = on(size)
        rule.onNodeWithTag("fetch-row:Arcane Signet").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(shows("Arcane Signet", "carousel-sheet"), "$on tapping Arcane Signet's row did not open the carousel on it")

        assertTrue(
            rule.onAllNodesWithTag("carousel-remove").fetchSemanticsNodes().isNotEmpty(),
            "$on the carousel's sheet has no Remove",
        )
        val r = rule.onNodeWithTag("carousel-remove").getUnclippedBoundsInRoot()
        assertTrue(r.right - r.left >= 24.dp && r.bottom - r.top >= 24.dp, "$on the carousel's Remove is not drawn: $r")
        if (size != null) {
            assertTrue(r.bottom <= size.height && r.right <= size.width, "$on the carousel's Remove is off screen: $r")
        }
        assertTrue(
            rule.onAllNodes(hasAnyAncestor(hasTestTag("carousel-remove")) and hasText("Remove", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "$on Remove is a word, not an icon",
        )
        assertTrue(
            rule.onAllNodes(hasTestTag("trash-icon") and hasAnyAncestor(hasTestTag("carousel-remove")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "$on Remove has no bin drawn on it",
        )
        assertEquals(
            listOf("Remove Arcane Signet from the list"),
            rule.onNodeWithTag("carousel-remove").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
            "$on Remove says nothing to a screen reader",
        )

        rule.onNodeWithTag("carousel-remove").performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "$on Remove shut the carousel")
        assertTrue(shows("Sol Ring", "carousel-sheet"), "$on the carousel did not move on to the next card")
        assertTrue(shows("2 of 2", "carousel-place"), "$on the carousel's place is wrong")
        assertTrue(
            rule.onAllNodesWithTag("fetch-card:Arcane Signet").fetchSemanticsNodes().isEmpty(),
            "$on Arcane Signet is still on the page",
        )
        assertFalse("Arcane Signet" in held.value.fetch.list, "$on the list text still names Arcane Signet")

        // The last card now: removing it steps back to the one before.
        rule.onNodeWithTag("carousel-remove").performClick()
        rule.waitForIdle()
        assertTrue(shows("Counterspell", "carousel-sheet"), "$on removing the last card did not step back")
        assertTrue(shows("1 of 1", "carousel-place"), "$on the carousel's place is wrong after stepping back")

        // The only card left: the carousel shuts, and the list is empty.
        rule.onNodeWithTag("carousel-remove").performClick()
        rule.waitForIdle()
        assertFalse(Overlay.CARD_PEEK in held.value.overlays, "$on the carousel is still open over an empty list")
        assertTrue(rule.onAllNodesWithTag("carousel-sheet").fetchSemanticsNodes().isEmpty(), "$on the carousel is still drawn")
        assertTrue(
            rule.onAllNodesWithTag("fetch-card:Counterspell").fetchSemanticsNodes().isEmpty(),
            "$on the page still has Counterspell",
        )
    }

    @Test
    fun removeOnTheCarouselKeepsYourPlace() = removeKeepsYourPlace(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun removeOnTheCarouselKeepsYourPlaceOnAFoldingPhoneOpenedOut() = removeKeepsYourPlace(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun removeOnTheCarouselKeepsYourPlaceOnATabletHeldSideways() = removeKeepsYourPlace(Tablet.LANDSCAPE)
}
