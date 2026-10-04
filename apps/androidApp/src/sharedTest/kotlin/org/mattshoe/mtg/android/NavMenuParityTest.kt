package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertTrue

/**
 * The phone's navigation, against `AppNav` (section 5).
 *
 * Android had a row of pills that scrolled sideways and never
 * collapsed, no title at all, no grouping of the admin screens, and a
 * Find button the website deliberately does not have. The web's
 * comment says why there is one hamburger at every width: "the row of
 * tabs only ever fitted on a desk, and two behaviours to keep straight
 * is how the phone ended up with no navigation at all".
 *
 * Through the real `AppShell`, pressed. The menu is drawn over the
 * page rather than inside the bar, so mounting a nav composable on its
 * own would not see the backdrop, which is the half that goes wrong.
 */
@RunWith(AndroidJUnit4::class)
class NavMenuParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The real shell over mutable state, the way `MainActivity` runs it. */
    private fun shell(start: AppState = AppState()): () -> AppState {
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = remember { mutableStateOf(start) }
                    state = held.value
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onRunSql = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    private fun burger() = rule.onNodeWithContentDescription("Menu")

    private fun openMenu() {
        burger().performClick()
        rule.waitForIdle()
    }

    /** The one row in the menu with this label. The title and any page heading are not pressable. */
    private fun row(label: String): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and hasClickAction())

    private fun topOf(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().top.value

    private fun menuIsOpen() =
        rule.onAllNodes(hasTestTag("app-menu")).fetchSemanticsNodes().isNotEmpty()

    // ------------------------------------------------------------ open and shut

    @Test
    fun thereIsAHamburgerAndTheMenuIsShutBehindIt() {
        shell()
        burger().assertExists()
        assertTrue(!menuIsOpen(), "the menu is open before anybody asked for it")
        // Nothing loose beside the burger: the places you can go live
        // in the menu, not in the bar.
        assertTrue(
            rule.onAllNodes(hasText("Decks") and hasClickAction()).fetchSemanticsNodes().isEmpty(),
            "a nav tab is still sitting in the bar",
        )
    }

    @Test
    fun theHamburgerOpensIt() {
        shell()
        openMenu()
        assertTrue(menuIsOpen(), "the menu did not open")
        row("Decks").assertExists()
        // And it says so, the way the web's `aria-expanded` does.
        rule.onNodeWithContentDescription("Close menu").assertExists()
    }

    @Test
    fun theHamburgerShutsItAgain() {
        shell()
        openMenu()
        rule.onNodeWithContentDescription("Close menu").performClick()
        rule.waitForIdle()
        assertTrue(!menuIsOpen(), "the menu did not close again")
    }

    @Test
    fun aPressAnywhereElseClosesIt() {
        shell()
        openMenu()
        val backdrop = rule.onNodeWithTag("nav-backdrop")
        backdrop.assertExists()
        // Well clear of the menu, which hangs off the top left.
        backdrop.performTouchInput { click(Offset(width - 8f, height / 2f)) }
        rule.waitForIdle()
        assertTrue(!menuIsOpen(), "the menu is stuck open")
    }

    /**
     * The backdrop catches the press without blinding the page.
     *
     * `Modifier.clickable` on something this size sets
     * `shouldMergeDescendantSemantics`, which folds every tag beneath
     * it into one node — dozens of tests stop being able to see
     * anything at all while the menu is up.
     */
    @Test
    fun theBackdropDoesNotSwallowTheTreeUnderIt() {
        shell()
        openMenu()
        rule.onNodeWithTag("topbar-title").assertExists()
        rule.onNodeWithTag("app-menu").assertExists()
        assertTrue(
            rule.onNodeWithTag("nav-backdrop").fetchSemanticsNode()
                .config.contains(androidx.compose.ui.semantics.SemanticsProperties.Role).not(),
            "the backdrop announces itself as a full-screen button",
        )
    }

    @Test
    fun pickingTheViewYouAreAlreadyOnStillClosesIt() {
        // The old row closed nothing, because it never opened. A menu
        // that only closes on a route change sits over the page when
        // you tap the view you are already looking at.
        val state = shell()
        openMenu()
        row(View.LIBRARY.label).performClick()
        rule.waitForIdle()
        assertTrue(!menuIsOpen(), "the menu sat open over the page")
        assertTrue(state().view == View.LIBRARY)
    }

    @Test
    fun andPickingAnotherOneGoesThere() {
        val state = shell()
        openMenu()
        row("Decks").performClick()
        rule.waitForIdle()
        assertTrue(!menuIsOpen(), "the menu stayed open")
        assertTrue(state().view == View.DECKS, "it did not navigate: ${state().view}")
    }

    // ------------------------------------------------------------ what is in it

    @Test
    fun theAdminHalfIsBehindARuleAndAHeading() {
        shell(AppState(admin = Admin(token = "t")))
        openMenu()

        val sep = topOf(rule.onNodeWithTag("app-menu-sep"))
        val heading = rule.onNodeWithTag("app-menu-group")
        heading.assertTextEquals("ADMIN")

        val ungated = View.entries.filter { it.inNav && !it.gated }
        val gated = View.entries.filter { it.inNav && it.gated }
        assertTrue(ungated.isNotEmpty() && gated.isNotEmpty())

        ungated.forEach { view ->
            assertTrue(
                topOf(row(view.label)) < sep,
                "${view.label} is below the separator, with the admin screens",
            )
        }
        assertTrue(topOf(heading) > sep, "the Admin heading is above its own rule")
        gated.forEach { view ->
            assertTrue(
                topOf(row(view.label)) > topOf(heading),
                "${view.label} is above the Admin heading",
            )
        }
    }

    @Test
    fun andLockIsLast() {
        shell(AppState(admin = Admin(token = "t")))
        openMenu()
        val lock = topOf(row("Lock"))
        (View.entries.filter { it.inNav }).forEach { view ->
            assertTrue(topOf(row(view.label)) < lock, "${view.label} is below Lock")
        }
    }

    @Test
    fun aGatedViewIsAbsentWhileLockedAndSoIsItsHalfOfTheMenu() {
        shell()
        openMenu()
        listOf("Mass Entry", "Server Logs").forEach { label ->
            assertTrue(
                rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isEmpty(),
                "$label is in the menu while locked",
            )
        }
        row("Unlock").assertExists()
    }

    @Test
    fun thereIsNoFindButton() {
        // The palette is still on ⌘K and `/`. A phone has neither, and
        // a button for it in the bar was one more thing nobody asked
        // for — the website's own suite asserts its absence.
        shell()
        assertTrue(
            rule.onAllNodes(hasText("Find")).fetchSemanticsNodes().isEmpty(),
            "the Find button is back in the bar",
        )
        openMenu()
        assertTrue(
            rule.onAllNodes(hasText("Find")).fetchSemanticsNodes().isEmpty(),
            "the Find button moved into the menu",
        )
    }

    // ------------------------------------------------------------ the title

    @Test
    fun theBarSaysWhatYouAreLookingAt() {
        shell()
        rule.onNodeWithTag("topbar-title").assertTextEquals("Library")
        openMenu()
        row("Stats").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("topbar-title").assertTextEquals("Stats")
    }

    @Test
    fun andAnOpenDeckPutsItsOwnNameThere() {
        val decks = DecksState()
            .loaded(listOf(Deck("a", "Feather Storm", "matt", null, "RW", 3, null)))
            .opened("a", emptyList())
        shell(AppState(decks = decks).navigate(Route(View.DECKS, "a")))
        rule.onNodeWithTag("topbar-title").assertTextEquals("Feather Storm")
    }

    // ------------------------------------------------------------ the picture

    /**
     * What it actually looks like open, which no assertion here can
     * say. Device only — Robolectric renders no pixels, so on the JVM
     * this skips and proves nothing.
     */
    @Test
    fun theOpenMenuLooksLikeTheWebs() {
        Parity.needsRealRendering()
        shell(AppState(admin = Admin(token = "t")))
        openMenu()
        rule.onNodeWithTag("app-menu").assertExists()
        rule.onRoot().shoot("nav-menu-open")
    }
}
