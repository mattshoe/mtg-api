package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Banned
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's banned cards, through the real shell, on a phone and on
 * a tablet both ways.
 *
 * Matt: "add a guide section below game Changers for banned cards.
 * start with Commander at the top, then standard, then whatever order
 * makes sense". Sibling of `BannedCardsPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class BannedCardsParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val formats = listOf("Commander", "Standard", "Pioneer", "Modern", "Legacy", "Vintage", "Pauper")

    private fun shell(start: AppState, sized: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        rule.setContent {
            sized {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                        AppShell(state = held.value, onState = { held.value = it }, onSearch = {}, onOpenDeck = {}, onPreviewEntry = {}, onApplyEntry = {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun names(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        }

    /** Every format on the page, Commander first, each below the last, each listing its cards. */
    private fun everyListInOrder(on: String) {
        val drawn = formats.filter { rule.onAllNodes(hasTestTag("ban-format-$it")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(formats, drawn, "$on the formats on the page are not Commander, then Standard, then the rest")
        val tops = formats.map { rule.onNodeWithTag("ban-format-$it").getUnclippedBoundsInRoot().top }
        formats.zip(tops).zipWithNext().forEach { (a, b) ->
            assertTrue(b.second > a.second, "$on ${b.first} at ${b.second} is not below ${a.first} at ${a.second}")
        }
        Banned.lists.forEach { list ->
            assertEquals(list.cards.map { it.shown }, names("ban-row-${list.format}"), "$on ${list.format} does not list its ban list")
        }
    }

    @Test
    fun theGuideOpensEveryBanListCommanderFirstAndARowOpensItsOwnCard() {
        shell(AppState())
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasTestTag("guide-tile-${Guide.BANNED}")).fetchSemanticsNodes().isNotEmpty(),
            "the Guide has no Banned cards tile",
        )
        val gc = rule.onNodeWithTag("guide-tile-${Guide.GAME_CHANGERS}").getUnclippedBoundsInRoot()
        val banned = rule.onNodeWithTag("guide-tile-${Guide.BANNED}").getUnclippedBoundsInRoot()
        val colours = rule.onNodeWithTag("guide-tile-${Guide.COLOURS}").getUnclippedBoundsInRoot()
        assertTrue(banned.top > gc.top && banned.top < colours.top, "the Banned cards tile is not between Game Changers and the colours")
        rule.onNodeWithTag("guide-tile-${Guide.BANNED}").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Guide.BANNED, held.value.route.rest, "the tile did not open its own address")
        everyListInOrder("on a phone")

        // Black Lotus is Commander's and Legacy's both; tapped in Legacy,
        // the carousel is on Legacy's.
        rule.onAllNodes(hasTestTag("ban-row-Legacy") and hasText("Black Lotus"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Black Lotus opened no carousel")
        assertEquals(PeekOf.BANNED, held.value.peek.of)
        val row = Banned.lists.takeWhile { it.format != "Legacy" }.sumOf { it.cards.size } +
            Banned.lists.first { it.format == "Legacy" }.cards.indexOfFirst { it.name == "Black Lotus" }
        assertEquals("${row + 1} of ${Banned.pageOrder.size}", held.value.peekPlace, "the carousel opened on another format's Black Lotus")
        assertEquals("Black Lotus", held.value.peeked?.title)
    }

    private fun listReads(size: DpSize) {
        shell(AppState().navigate(Route.parse("#/guide/${Guide.BANNED}"))) { content -> Tablet.Sized(size) { content() } }
        val on = "on a ${size.width} by ${size.height} screen"
        assertTrue(rule.onAllNodes(hasTestTag("ban-list")).fetchSemanticsNodes().isNotEmpty(), "$on there is no banned cards list")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("ban-list").getUnclippedBoundsInRoot()
        val width = list.right - list.left
        assertTrue(width >= 320.dp, "$on the list is squeezed to $width")
        assertTrue(width <= 720.dp, "$on the list runs $width wide")
        val left = list.left - page.left
        val right = page.right - list.right
        assertTrue((left - right).value in -4f..4f, "$on the list is not centred: $left on the left, $right on the right")
        everyListInOrder(on)

        rule.onAllNodes(hasTestTag("ban-row-Commander") and hasText("Mana Crypt"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(PeekOf.BANNED, held.value.peek.of, "$on tapping Mana Crypt opened no carousel over the lists")
        val sheet = rule.onNodeWithTag("carousel-sheet").getUnclippedBoundsInRoot()
        assertTrue(sheet.bottom <= size.height + 0.5.dp, "$on the carousel's sheet runs ${sheet.bottom - size.height} off the bottom")
        val pager = rule.onNodeWithTag("carousel-pager").getUnclippedBoundsInRoot()
        assertTrue(pager.top >= 0.dp, "$on the carousel's card runs ${-pager.top} off the top")
        assertTrue(pager.right <= size.width + 0.5.dp, "$on the carousel's card runs off the side")
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theListReadsOnAFoldingPhoneOpenedOut() = listReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theListReadsOnATabletHeldSideways() = listReads(Tablet.LANDSCAPE)
}
