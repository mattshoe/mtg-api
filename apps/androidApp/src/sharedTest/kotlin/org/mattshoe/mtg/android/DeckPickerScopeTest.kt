package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.FacetQueries
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter panel's "In a deck", through the real `AppShell`.
 *
 * Matt: "the collection filter shows ALL decks and NOT the deck the user
 * owns. this is unacceptable. especially when browsing other users
 * cards". The website's `DeckPickerScopeTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class DeckPickerScopeTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun row(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })

    private val signedIn = AppState(
        admin = Admin().signIn(Account("m4tt0001"), "t"),
        facets = Facets(
            types = listOf("Creature"),
            decks = FacetQueries.decodeDecks(
                listOf("key", "name", "owner", "owner_key"),
                listOf(
                    row("d1", "Alela", "Matt", "m4tt0001"),
                    row("d2", "Korvold", "Kayla", "k4yy0003"),
                ),
            ),
        ),
    )

    private fun openPicker(start: AppState, size: DpSize?) {
        rule.setContent {
            val body = @androidx.compose.runtime.Composable {
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
            if (size == null) body() else Tablet.Sized(size) { body() }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("header-collection").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("select-deck").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun offered(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun onlyTheirs(size: DpSize?) {
        openPicker(signedIn.navigate(Route(View.USERS, "k4yy0003")), size)
        assertTrue(offered("— in any deck —"), "the picker did not open, so this proves nothing")
        assertTrue(offered("Korvold"), "Kayla's Library does not offer Kayla's deck by its name")
        assertTrue(!offered("Alela") && !offered("Alela (Matt)"), "Kayla's Library offers Matt's deck")
        rule.onNodeWithText("Korvold").performClick()
        rule.waitForIdle()
        assertEquals("d2", held.value.library.filters.deck, "picking her deck did not filter by it")
    }

    private fun onlyYours(size: DpSize?) {
        openPicker(signedIn.navigate(View.LIBRARY), size)
        assertTrue(offered("— in any deck —"), "the picker did not open, so this proves nothing")
        assertTrue(offered("Alela"), "your Library does not offer your deck by its name")
        assertTrue(!offered("Korvold") && !offered("Korvold (Kayla)"), "your Library offers Kayla's deck")
    }

    @Test
    fun somebodyElsesLibraryOffersOnlyTheirDecks() = onlyTheirs(null)

    @Test
    fun yourLibraryOffersOnlyYourDecks() = onlyYours(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun somebodyElsesLibraryOffersOnlyTheirDecksOnAFoldingPhoneOpenedOut() = onlyTheirs(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun yourLibraryOffersOnlyYourDecksOnAFoldingPhoneOpenedOut() = onlyYours(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun somebodyElsesLibraryOffersOnlyTheirDecksOnATabletHeldSideways() = onlyTheirs(Tablet.LANDSCAPE)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun yourLibraryOffersOnlyYourDecksOnATabletHeldSideways() = onlyYours(Tablet.LANDSCAPE)
}
