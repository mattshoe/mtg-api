package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
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
 * Matt: "i want the 'not in collection' section in the fetch list to
 * show the count in the header, and every section of it". The website
 * has always put the count beside each head; the phone put none.
 *
 * Through `AppShell`, on a phone and on both tablet shapes. The web's
 * `FetchListCountsTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class FetchListCountsTest {

    @get:Rule
    val rule = createComposeRule()

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private val list = "2 Counterspell\n1 Sol Ring\n3 Black Lotus\n1 Mox Pearl"

    private fun shell(size: DpSize?) {
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
    }

    private fun on(size: DpSize?) = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"

    /** The count, in the head, on the same line as the head's words. */
    private fun headCounts(size: DpSize?, section: String, words: String, count: String) {
        val on = on(size)
        val head = "fetch-head:$section"
        rule.onNodeWithTag(head).performScrollTo()
        rule.waitForIdle()
        val counts = rule.onAllNodes(hasText(count) and hasAnyAncestor(hasTestTag(head)), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, counts.size, "$on the $section head shows no count of $count")
        val label = rule.onAllNodes(hasText(words) and hasAnyAncestor(hasTestTag(head)), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        val tag = counts.single().boundsInRoot
        assertTrue(tag.top < label.bottom && label.top < tag.bottom, "$on the $section count is not on the head's line: $tag against $label")
        assertTrue(tag.width > 0f && tag.height > 0f, "$on the $section count has no size: $tag")
        val panel = rule.onNodeWithTag("fetch-section:$section").fetchSemanticsNode().boundsInRoot
        assertTrue(tag.right <= panel.right + 0.5f, "$on the $section count runs off its panel: $tag against $panel")
    }

    private fun everyHeadCounts(size: DpSize?) {
        shell(size)
        headCounts(size, "Instants · U · Common", "Instants", "2")
        headCounts(size, "Artifacts · C · Uncommon", "Artifacts", "1")
        headCounts(size, "Missing", "Not in your collection", "4")
    }

    @Test
    fun everyHeadCountsItsCards() = everyHeadCounts(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun everyHeadCountsItsCardsOnAFoldingPhoneOpenedOut() = everyHeadCounts(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun everyHeadCountsItsCardsOnATabletHeldSideways() = everyHeadCounts(Tablet.LANDSCAPE)
}
