package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.and
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
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
import org.mattshoe.mtg.core.Tools
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tools tab: Fetch and Entry as tiles, each with the icon it had
 * in the bar.
 *
 * Matt: "let's collapse fetch and entry into a "Tools" tab. the tools
 * page should look very similar in presentation to the users tab, but
 * instead of profile avatars, use the current icons on the bottom nav
 * for them". The website's `ToolsPageTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class ToolsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

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
                    )
                }
            }
        }
        rule.setContent { if (size == null) body() else Tablet.Sized(size, body) }
        rule.waitForIdle()
    }

    private fun tab(label: String) = hasTestTag("nav-item") and hasContentDescription(label)

    @Test
    fun theBarHasToolsAndNeitherFetchNorEntry() {
        shell(signedIn())
        assertTrue(rule.onAllNodes(tab("Tools")).fetchSemanticsNodes().size == 1, "the bar has no Tools tab")
        listOf("Fetch", "Entry").forEach {
            assertTrue(rule.onAllNodes(tab(it)).fetchSemanticsNodes().isEmpty(), "$it is still a tab in the bar")
        }
        rule.onNodeWithContentDescription("Tools").performClick()
        rule.waitForIdle()
        assertEquals(View.TOOLS, held.value.view, "the Tools tab went somewhere else")
        rule.onNodeWithTag("tools").assertExists("the Tools tab has no page")
    }

    @Test
    fun eachToolIsATileWithItsIconNameAndWhatItIsFor() {
        shell(signedIn().navigate(View.TOOLS))
        listOf(View.FETCH, View.ENTRY).forEach { tool ->
            val tile = rule.onNodeWithTag("tool-tile-${tool.slug}").assertExists("${tool.label} has no tile")
                .getUnclippedBoundsInRoot()
            val icon = rule.onNodeWithTag("nav-icon-${tool.label}", useUnmergedTree = true)
                .assertExists("${tool.label}'s tile has no icon").getUnclippedBoundsInRoot()
            assertTrue(
                icon.left >= tile.left && icon.right <= tile.right && icon.top >= tile.top && icon.bottom <= tile.bottom,
                "${tool.label}'s icon is not on its tile: icon $icon, tile $tile",
            )
            assertTrue(
                rule.onAllNodes(hasText(Tools.blurb(tool)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "${tool.label}'s tile does not say what it is for",
            )
        }
    }

    @Test
    fun aStrangerIsOfferedFetchButNotEntry() {
        shell(AppState(admin = Admin().settle()).navigate(View.TOOLS))
        rule.onNodeWithTag("tool-tile-fetch").assertExists("a stranger cannot fetch")
        assertTrue(
            rule.onAllNodes(hasTestTag("tool-tile-entry")).fetchSemanticsNodes().isEmpty(),
            "a stranger is offered Entry, which writes to a collection they do not have",
        )
    }

    private fun aTileOpens(tool: View) {
        shell(signedIn().navigate(View.TOOLS))
        rule.onNodeWithTag("tool-tile-${tool.slug}").performClick()
        rule.waitForIdle()
        assertEquals(tool, held.value.view, "the ${tool.label} tile opened something else")
        val lit = rule.onNodeWithTag("nav-current", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val tools = rule.onNode(tab("Tools")).getUnclippedBoundsInRoot()
        assertTrue(
            lit.left >= tools.left && lit.right <= tools.right,
            "on ${tool.label} the lit tab is not Tools: lit $lit, Tools $tools",
        )
    }

    @Test
    fun theFetchTileOpensFetchAndToolsStaysLit() = aTileOpens(View.FETCH)

    @Test
    fun theEntryTileOpensEntryAndToolsStaysLit() = aTileOpens(View.ENTRY)

    private fun tilesSitSideBySide(size: DpSize) {
        shell(signedIn().navigate(View.TOOLS), size)
        val on = "on a ${size.width} by ${size.height} screen"
        val a = rule.onNodeWithTag("tool-tile-fetch").getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("tool-tile-entry").getUnclippedBoundsInRoot()
        assertTrue(
            (a.top - b.top).value in -2f..2f && b.left > a.right,
            "$on the tiles are one long column: Fetch's at ${a.left},${a.top}, Entry's at ${b.left},${b.top}",
        )
        listOf(a, b).forEach {
            assertTrue(it.right <= size.width, "$on a tile runs off the side: ${it.right}")
            assertTrue(it.bottom <= size.height, "$on a tile runs off the bottom: ${it.bottom}")
        }
    }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun tilesSitSideBySideOnAFoldingPhoneOpenedOut() = tilesSitSideBySide(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun tilesSitSideBySideOnATabletHeldSideways() = tilesSitSideBySide(Tablet.LANDSCAPE)
}
