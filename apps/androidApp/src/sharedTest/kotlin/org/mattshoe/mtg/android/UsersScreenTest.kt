package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Collector
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.UsersState
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Users tab: everybody's collections as tiles, and a tile opening
 * that person's Library.
 *
 * Matt: "i should have a bottom nav option to browse users, then i can
 * select any user, and browse their cards ... the landing page should
 * be a collection of 'cards' or 'tiles' or whatever, with a profile Pic
 * on it". The website's `UsersPageTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class UsersScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private var refreshed = 0

    private val people = listOf(
        Collector("k1", "Kayla", null, 1234, 5),
        Collector("k2", "Matt", null, 3, 1),
        Collector("k3", null, null, 0, 0),
    )

    private fun signedIn() = AppState(admin = Admin().signIn(Account(key = "k2", name = "Matt"), "t"))

    private fun shell(start: AppState, size: DpSize? = null) {
        val body: @Composable () -> Unit = {
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
                        onRefresh = { refreshed++; held.value = held.value.refreshed() },
                    )
                }
            }
        }
        rule.setContent { if (size == null) body() else Tablet.Sized(size, body) }
        rule.waitForIdle()
    }

    private fun onTiles(s: AppState = signedIn()) =
        s.navigate(View.USERS).copy(users = UsersState().loaded(people))

    @Test
    fun theBarHasUsers() {
        shell(signedIn())
        rule.onNodeWithContentDescription("Users").performClick()
        rule.waitForIdle()
        assertEquals(View.USERS, held.value.view, "the Users tab went somewhere else")
    }

    @Test
    fun eachTileSaysWhoAndHowMuch() {
        shell(onTiles())
        rule.onNodeWithTag("user-tile-k1").assertExists("Kayla has no tile")
        assertTrue(
            rule.onAllNodes(hasText("Kayla")).fetchSemanticsNodes().isNotEmpty(),
            "Kayla's tile does not name her",
        )
        assertTrue(
            rule.onAllNodes(hasText("1,234 cards · 5 decks")).fetchSemanticsNodes().isNotEmpty(),
            "Kayla's tile does not say how much she has",
        )
        // No name and no picture: the key, and a letter in the circle.
        rule.onNodeWithTag("user-tile-k3").assertExists("an account with no name has no tile")
        assertTrue(rule.onAllNodes(hasText("k3")).fetchSemanticsNodes().isNotEmpty(), "a nameless tile says nothing")
    }

    @Test
    fun tappingATileOpensTheirLibrary() {
        shell(onTiles())
        rule.onNodeWithTag("user-tile-k1").performClick()
        rule.waitForIdle()
        assertEquals(Route(View.USERS, "k1"), held.value.route, "the tile did not open Kayla's cards")
        assertEquals("k1", held.value.scopedLibrary().filters.owner, "the Library searches somebody else's cards")
        rule.onNodeWithTag("library").assertExists("Kayla's page is not a Library")
        assertTrue(
            rule.onAllNodes(hasText("Kayla's cards")).fetchSemanticsNodes().isNotEmpty(),
            "the page does not say whose cards these are",
        )
        // And back to the tiles.
        rule.onNodeWithTag("users-back").performClick()
        rule.waitForIdle()
        assertEquals(Route(View.USERS), held.value.route, "back did not return to the tiles")
        rule.onNodeWithTag("user-tile-k1").assertExists()
    }

    @Test
    fun aListThatFailedSaysWhyAndCanBeAskedAgain() {
        shell(signedIn().navigate(View.USERS).fetching().fetchFailed("HTTP 500: the database is down"))
        assertTrue(
            rule.onAllNodes(hasTestTag("users-busy")).fetchSemanticsNodes().isEmpty(),
            "the spinner outlived the failure",
        )
        assertTrue(
            rule.onAllNodes(hasText("HTTP 500: the database is down", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the failure does not say what happened",
        )
        rule.onNodeWithTag("users-retry").performClick()
        rule.waitForIdle()
        assertEquals(1, refreshed, "Try again asked for nothing")
        assertTrue(held.value.users.busy, "Try again did not go back to loading")
    }

    @Test
    fun aListOnItsWaySaysSo() {
        shell(signedIn().navigate(View.USERS).fetching())
        rule.onNodeWithTag("users-busy").assertExists("the list is loading with nothing to say so")
    }

    private fun tilesSitInAGrid(size: DpSize) {
        shell(onTiles(), size)
        val on = "on a ${size.width} by ${size.height} screen"
        val a = rule.onNodeWithTag("user-tile-k1").getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("user-tile-k2").getUnclippedBoundsInRoot()
        assertTrue(
            (a.top - b.top).value in -2f..2f && b.left > a.right,
            "$on the tiles are one long column: Kayla's at ${a.left},${a.top}, Matt's at ${b.left},${b.top}",
        )
        listOf(a, b).forEach {
            assertTrue(it.right <= size.width, "$on a tile runs off the side: ${it.right}")
        }
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun tilesSitSideBySideOnAFoldingPhoneOpenedOut() = tilesSitInAGrid(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun tilesSitSideBySideOnATabletHeldSideways() = tilesSitInAGrid(Tablet.LANDSCAPE)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theirLibraryFitsAFoldingPhoneOpenedOut() {
        shell(onTiles().browseUser("k1"), Tablet.UNFOLDED)
        val lib = rule.onNodeWithTag("library").getUnclippedBoundsInRoot()
        assertTrue(lib.right <= Tablet.UNFOLDED.width, "Kayla's Library runs off the side: ${lib.right}")
        assertTrue(lib.right - lib.left > Tablet.UNFOLDED.width / 2, "Kayla's Library is squeezed to ${lib.right - lib.left}")
    }
}
