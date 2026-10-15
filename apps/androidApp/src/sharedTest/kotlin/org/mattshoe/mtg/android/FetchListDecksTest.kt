package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "in the fetch list page, if there are no entries in the bulk
 * for a card, then show which deck it belongs to if it appears in a
 * deck".
 *
 * Through `AppShell`, on a phone and on both tablet shapes. Sol Ring's
 * only copy is in a deck, so the deck is named under it. Counterspell
 * has a spare in bulk, so nothing is. The web's `FetchListDecksTest` is
 * the same claim.
 */
@RunWith(AndroidJUnit4::class)
class FetchListDecksTest {

    @get:Rule
    val rule = createComposeRule()

    private val owned = listOf(
        OwnedPrinting(
            "sol ring", "Sol Ring", "Sol Ring", "Artifact", null, "uncommon", 1,
            inDecks = 1, decks = listOf("Chaos Incarnate"),
        ),
        OwnedPrinting(
            "counterspell", "Counterspell", "Counterspell", "Instant", "U", "common", 2, identity = "U",
            inDecks = 1, decks = listOf("Elven Council"),
        ),
    )

    private fun shell(size: DpSize?) {
        val list = "1 Sol Ring\n1 Counterspell"
        val start = AppState(
            admin = Admin().signIn(Account(key = "e7de0cb1"), "t"),
            route = Route(View.FETCH, FetchListState.PAGE),
            fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
        )
        val content: @Composable () -> Unit = {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
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
    }

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private fun deckNamedUnderTheCard(size: DpSize?) {
        shell(size)
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        assertTrue(
            rule.onAllNodesWithTag("fetch-decks:Sol Ring", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "$on Sol Ring has no copy in bulk and no deck is named under it",
        )
        rule.onNodeWithTag("fetch-decks:Sol Ring", useUnmergedTree = true).performScrollTo()
        assertEquals("In deck: Chaos Incarnate", text("fetch-decks:Sol Ring"), on)

        val row = rule.onNodeWithTag("fetch-tick:Sol Ring").getUnclippedBoundsInRoot()
        val line = rule.onNodeWithTag("fetch-decks:Sol Ring", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(line.top >= row.bottom - 1.dp, "$on the deck is not under its card: $line against $row")
        assertTrue(line.bottom - line.top > 4.dp, "$on the deck line has no height: $line")
        if (size != null) assertTrue(line.right <= size.width, "$on the deck line runs off the side: ${line.right}")

        assertTrue(
            rule.onAllNodesWithTag("fetch-decks:Counterspell", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "$on Counterspell has a spare in bulk and a deck is still named under it",
        )
    }

    @Test
    fun aCardWithNoCopyInBulkNamesItsDeck() = deckNamedUnderTheCard(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aCardWithNoCopyInBulkNamesItsDeckOnAFoldingPhoneOpenedOut() = deckNamedUnderTheCard(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aCardWithNoCopyInBulkNamesItsDeckOnATabletHeldSideways() = deckNamedUnderTheCard(Tablet.LANDSCAPE)
}
