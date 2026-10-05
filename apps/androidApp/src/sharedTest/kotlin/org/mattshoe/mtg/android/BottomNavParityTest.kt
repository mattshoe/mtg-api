package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bottom bar, the profile, and no hamburger.
 *
 * Matt: "Let's give the Android app bottom navigation instead of
 * hamburger menu. The options should be very standard bottom nav with
 * an icon and a single short word label"; "in the top right menu have
 * a 'profile' icon or something that shows admin status and allows you
 * to log in or log out as admin"; "The server logs page should be
 * reachable via the profile icon"; "When using admin mode, the mass
 * entry screen should be accessible via the bottom nav."
 *
 * Which views go where is `:core`'s `Admin.bar` and
 * `Admin.behindProfile`, tested in `NavShapeTest`. This is about the
 * chrome: that there is a bar at the bottom, that every item in it is
 * an icon above a word, that the profile carries admin and the log,
 * and that the hamburger is gone rather than hiding behind the new
 * thing.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun shell(start: AppState = AppState()) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun unlocked() = AppState(admin = Admin(token = "t").unlock("t"))

    private fun tabs() =
        rule.onAllNodes(hasTestTag("nav-item"), useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * One tab, by its own description.
     *
     * Not by text: the label, the icon and the item are three nodes,
     * `clickable` merges them, and asking for "the node with this
     * text" picked whichever of the three answered first — which is
     * how the first version of this managed to measure a tab as
     * 0.0dp tall.
     */
    private fun tab(label: String) = rule.onNodeWithContentDescription(label)

    // --------------------------------------------------------- the bar

    @Test
    fun thereIsNoHamburger() {
        shell()
        assertTrue(
            rule.onAllNodes(hasTestTag("nav-burger"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "the hamburger is still there",
        )
    }

    @Test
    fun theBarIsTheThreeYouCanReachWhileLocked() {
        shell()
        assertEquals(3, tabs().size, "a locked app's bar should be Library, Decks, Stats")
        listOf("Library", "Decks", "Stats").forEach { label ->
            assertTrue(
                rule.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty(),
                "$label is missing from the bar",
            )
        }
    }

    @Test
    fun adminAddsEntryAndNothingElse() {
        shell(unlocked())
        assertEquals(4, tabs().size, "unlocking should add exactly one tab")
        assertTrue(
            rule.onAllNodes(hasText("Entry")).fetchSemanticsNodes().isNotEmpty(),
            "Mass Entry is not in the bar while unlocked",
        )
        assertTrue(
            rule.onAllNodes(hasText("Server Logs")).fetchSemanticsNodes().isEmpty(),
            "the server log is in the bottom bar; it belongs behind the profile",
        )
    }

    @Test
    fun thereIsNoQueryTab() {
        shell(unlocked())
        assertTrue(
            rule.onAllNodes(hasText("Query")).fetchSemanticsNodes().isEmpty(),
            "the Query tab is still in the bar",
        )
    }

    @Test
    fun everyTabIsAnIconAboveAWord() {
        shell(unlocked())
        // The icon and the label are separate nodes inside the item,
        // and the icon sits above the label — which is what makes it
        // a standard bottom bar rather than a row of text buttons.
        listOf("Library", "Decks", "Stats", "Entry").forEach { label ->
            val icon = rule.onNodeWithTag("nav-icon-$label", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val word = rule.onAllNodes(hasText(label), useUnmergedTree = true)
                .fetchSemanticsNodes().first().let {
                    rule.onNodeWithTag("nav-label-$label", useUnmergedTree = true)
                        .getUnclippedBoundsInRoot()
                }
            assertTrue(
                icon.bottom.value <= word.top.value + 1f,
                "$label's icon is not above its label",
            )
            val side = icon.right.value - icon.left.value
            assertTrue(side >= 18f, "$label's icon is only ${side}dp")
        }
    }

    @Test
    fun theBarIsAtTheBottom() {
        shell()
        val bar = rule.onNodeWithTag("bottom-nav").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithTag("topbar-title").getUnclippedBoundsInRoot()
        assertTrue(
            bar.top.value > title.bottom.value,
            "the nav is at ${bar.top}, above the title at ${title.bottom} — that is a top bar",
        )
    }

    @Test
    fun everyTabIsThumbSized() {
        shell(unlocked())
        listOf("Library", "Decks", "Stats", "Entry").forEach { label ->
            val box = tab(label).getUnclippedBoundsInRoot()
            val h = box.bottom.value - box.top.value
            assertTrue(h >= 47.5f, "the $label tab is only ${h}dp tall")
        }
    }

    @Test
    fun tappingATabGoesThere() {
        shell()
        tab("Decks").performClick()
        rule.waitForIdle()
        assertEquals(View.DECKS, held.value.view)
    }

    @Test
    fun theTabYouAreOnIsMarkedWithoutRelyingOnHue() {
        shell()
        // Matt is colourblind. A selected tab that differs only in
        // colour is not marked at all for him, so the current one
        // carries something with its own shape — a pill behind the
        // icon — and that is what this looks for.
        assertTrue(
            rule.onAllNodes(hasTestTag("nav-current"), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1,
            "exactly one tab should be marked as current",
        )
        tab("Stats").performClick()
        rule.waitForIdle()
        val marked = rule.onNodeWithTag("nav-current", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val stats = tab("Stats").getUnclippedBoundsInRoot()
        assertTrue(
            marked.left.value >= stats.left.value - 1f &&
                marked.right.value <= stats.right.value + 1f,
            "the mark is not on the tab you are on",
        )
    }

    // ----------------------------------------------------- the profile

    @Test
    fun thereIsAProfileInTheTopRight() {
        shell()
        val profile = rule.onNodeWithContentDescription("Profile").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithTag("topbar-title").getUnclippedBoundsInRoot()
        assertTrue(
            profile.left.value >= title.right.value - 1f,
            "the profile is not to the right of the title",
        )
        val w = profile.right.value - profile.left.value
        assertTrue(w >= 47.5f, "the profile control is only ${w}dp")
    }

    @Test
    fun theProfileSaysWhetherYouAreAdmin() {
        shell()
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Not signed in", substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
            "the profile does not say you are locked out",
        )
    }

    @Test
    fun theProfileSaysSoWhenYouAreAdmin() {
        shell(unlocked())
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Admin", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the profile does not say you are admin",
        )
    }

    @Test
    fun theProfileIsTheWayInAndTheWayOut() {
        shell()
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Unlock")).fetchSemanticsNodes().isNotEmpty(),
            "no way to sign in from the profile",
        )
    }

    @Test
    fun theProfileOffersLockWhileUnlocked() {
        shell(unlocked())
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Lock")).fetchSemanticsNodes().isNotEmpty(),
            "no way to sign out from the profile",
        )
    }

    @Test
    fun theServerLogIsBehindTheProfile() {
        shell(unlocked())
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Server Logs") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(View.LOGS, held.value.view, "the profile does not reach the server log")
    }

    @Test
    fun theProfileOffersNoLogWhileLocked() {
        shell()
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Server Logs")).fetchSemanticsNodes().isEmpty(),
            "a locked app offers the server log",
        )
    }

    // ------------------------------------------------------ the picture

    @Test
    fun theUnlockedBarIsPhotographed() {
        // Evidence, not an assertion. The assertions above already
        // say the bar has four tabs, each an icon over a word, with
        // the current one marked — this is so a person can look at it.
        Parity.needsRealRendering()
        shell(unlocked())
        rule.onNodeWithTag("bottom-nav").shoot("bottom-nav-admin")
        rule.onRoot().shoot("shell-admin")
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        rule.onRoot().shoot("profile-open-admin")
    }
}
