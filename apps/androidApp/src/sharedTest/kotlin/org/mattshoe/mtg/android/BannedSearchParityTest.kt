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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
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
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the banned cards by name, and the carousel saying where a
 * card is banned, through the real shell, on a phone and on a tablet
 * both ways.
 *
 * Matt: "the banned cards should have a search field and the carousel
 * should show which formats for which s card is banned". Sibling of
 * `BannedSearchPageTest` on the website.
 */
@RunWith(AndroidJUnit4::class)
class BannedSearchParityTest {

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

    /** The Guide tab, then the Banned cards tile, the way a person gets there. */
    private fun openOnAPhone() {
        shell(AppState())
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-${Guide.BANNED}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun search(words: String) {
        rule.onNodeWithTag("ban-search").assertExists("the banned cards page has no search bar")
        rule.onNodeWithTag("ban-search").performTextClearance()
        if (words.isNotEmpty()) rule.onNodeWithTag("ban-search").performTextInput(words)
        rule.waitForIdle()
    }

    private fun names(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        }

    private fun texts(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    /** Each format left on the page, with the names of its rows. */
    private fun sections(): List<Pair<String, List<String>>> =
        formats.filter { rule.onAllNodes(hasTestTag("ban-format-$it")).fetchSemanticsNodes().isNotEmpty() }
            .map { it to names("ban-row-$it") }

    /** The carousel's ban, on the screen whole. */
    private fun theBanReads(on: String, width: Dp, height: Dp) {
        val ban = rule.onAllNodes(hasText("Banned in Commander and Legacy"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, ban.size, "$on the carousel does not say Banned in Commander and Legacy")
        val at = rule.onAllNodes(hasText("Banned in Commander and Legacy"), useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertTrue(at.right <= width + 0.5.dp && at.left >= 0.dp, "$on the ban runs off the side: ${at.left}..${at.right} on $width")
        assertTrue(at.bottom <= height + 0.5.dp, "$on the ban runs off the bottom: ${at.bottom} on $height")
        assertTrue(at.right - at.left > 60.dp, "$on the ban is squeezed to ${at.right - at.left}")
    }

    @Test
    fun aNameLeavesOnlyTheFormatsThatBanItAndTheCarouselSaysWhereItIsBanned() {
        openOnAPhone()
        search("black lotus")
        assertEquals(
            listOf("Commander" to listOf("Black Lotus"), "Legacy" to listOf("Black Lotus")),
            sections(),
            "searching black lotus did not leave Commander's and Legacy's Black Lotus",
        )
        assertEquals(listOf("2 of ${Banned.pageOrder.size} cards"), texts("ban-search-count"), "nothing says how many were found")
        val bar = rule.onNodeWithTag("ban-search").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag("ban-format-Commander").getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the lists: ${bar.bottom} vs ${first.top}")

        rule.onAllNodes(hasTestTag("ban-row-Legacy") and hasText("Black Lotus"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "tapping Black Lotus opened no carousel")
        assertEquals(listOf("2 of 2"), texts("carousel-place"), "the carousel is over every banned card, not the ones found")
        val sheet = rule.onNodeWithTag("carousel-sheet").getUnclippedBoundsInRoot()
        theBanReads("on a phone", sheet.right, sheet.bottom)
    }

    @Test
    fun aNameNothingBansSaysSoAndClearingItBringsBackEveryList() {
        openOnAPhone()
        search("llanowar elves")
        assertEquals(listOf("No banned card is called \"llanowar elves\"."), texts("ban-search-none"))
        assertTrue(sections().isEmpty(), "lists are left under a search that found nothing: ${sections()}")
        search("")
        assertEquals(formats, sections().map { it.first }, "clearing the search did not bring back every list")
        Banned.lists.forEach { assertEquals(it.cards.map { c -> c.shown }, names("ban-row-${it.format}"), "clearing did not bring back all of ${it.format}") }
        assertTrue(texts("ban-search-count").isEmpty(), "a count is left over lists nobody searched")
    }

    private fun searchReads(size: DpSize) {
        shell(AppState().navigate(Route.parse("#/guide/${Guide.BANNED}"))) { content -> Tablet.Sized(size) { content() } }
        val on = "on a ${size.width} by ${size.height} screen"
        search("mana crypt")
        assertEquals(listOf("Commander", "Legacy"), sections().map { it.first }, "$on searching mana crypt did not leave Commander and Legacy")
        val page = rule.onNodeWithTag("guide").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("ban-list").getUnclippedBoundsInRoot()
        val width = list.right - list.left
        assertTrue(width >= 320.dp && width <= 720.dp, "$on the list is $width wide")
        val left = list.left - page.left
        val right = page.right - list.right
        assertTrue((left - right).value in -4f..4f, "$on the list is not centred: $left on the left, $right on the right")
        val bar = rule.onNodeWithTag("ban-search").getUnclippedBoundsInRoot()
        assertTrue(
            (bar.left - list.left).value in -4f..4f && (bar.right - list.right).value in -4f..4f,
            "$on the search bar is not over the list: ${bar.left}..${bar.right} against ${list.left}..${list.right}",
        )

        rule.onAllNodes(hasTestTag("ban-row-Commander") and hasText("Mana Crypt"))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "$on tapping Mana Crypt opened no carousel")
        theBanReads(on, size.width, size.height)
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun searchReadsOnAFoldingPhoneOpenedOut() = searchReads(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun searchReadsOnATabletHeldSideways() = searchReads(Tablet.LANDSCAPE)
}
