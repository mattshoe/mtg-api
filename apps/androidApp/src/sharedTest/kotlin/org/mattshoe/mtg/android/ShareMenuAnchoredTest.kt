package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertTrue

/**
 * The share menu hangs off its own button, rather than shoving the
 * deck down the page.
 *
 * Section 5 of the audit: the website anchors `.app-menu.from-right`
 * to `.menu-anchor` beside the button, and Android put a panel inline
 * in the column instead. The reasoning written on the Android version
 * was sound as far as it went — a menu that is part of the page
 * cannot end up off the edge of the screen, which is a bug the web
 * genuinely had and genuinely had to fix. What it missed is that an
 * inline panel moves everything under it, so pressing share pushed
 * the hero, the tags and the whole card list down the screen.
 *
 * `DropdownMenu` is the way to have both: it is a window of its own,
 * anchored to the button, and it keeps itself inside the screen by
 * construction — so Android gets the web's placement without ever
 * needing the container query the web needed.
 *
 * `DecksParityTest` already has four tests about what the menu
 * offers and what each row reports. Every one of them passes against
 * the inline version too, because they only ever ask what the menu
 * says. These ask where it is.
 */
@RunWith(AndroidJUnit4::class)
class ShareMenuAnchoredTest {

    @get:Rule
    val rule = createComposeRule()

    private fun deck() = Deck(
        "alela", "Faerie Swarm", "matt",
        "Alela, Artful Provocateur (ELD) 324", "UW", 3, null,
    )

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin().signIn(Account(slug = "matt", role = "admin"), "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(card("Alela, Artful Provocateur", "commander"), card("Sol Ring")),
        ),
    )

    /** Through the real shell, over a real open deck. */
    private fun shell() {
        val start = opened()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
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
        rule.waitForIdle()
    }

    private fun openTheMenu() {
        rule.onNodeWithContentDescription("Share this deck").performClick()
        rule.waitForIdle()
    }

    @Test
    fun theMenuIsAWindowOfItsOwnRatherThanAPanelInThePage() {
        shell()
        // Nothing of the sort before it is pressed.
        assertTrue(
            rule.onAllNodesWithText("Deck list").fetchSemanticsNodes().isEmpty(),
            "the menu is showing before anything was pressed",
        )
        openTheMenu()
        // The assertion that tells anchored from inline. An inline
        // panel is a node in the page's own tree and satisfies no
        // popup matcher at all.
        rule.onNode(isPopup()).assertExists()
        assertTrue(
            rule.onAllNodesWithText("Deck list").fetchSemanticsNodes().isNotEmpty(),
            "the menu opened but does not offer the deck list",
        )
    }

    @Test
    fun openingItDoesNotPushTheDeckDownThePage() {
        shell()
        val before = rule.onNodeWithTag("deck-hero-who").getUnclippedBoundsInRoot().top.value
        openTheMenu()
        val after = rule.onNodeWithTag("deck-hero-who").getUnclippedBoundsInRoot().top.value
        // The whole cost of the inline panel: it was a child of the
        // column, so everything below the header moved by its height
        // every time the button was pressed.
        assertTrue(
            kotlin.math.abs(after - before) < 1f,
            "opening the share menu moved the hero from ${before}dp to ${after}dp",
        )
    }

    @Test
    fun theButtonClosesItAgain() {
        shell()
        openTheMenu()
        assertTrue(
            rule.onAllNodesWithText("Deck list").fetchSemanticsNodes().isNotEmpty(),
            "the menu did not open",
        )
        rule.onNodeWithContentDescription("Share this deck").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Deck list").fetchSemanticsNodes().isEmpty(),
            "the menu stayed open when its own button was pressed again",
        )
    }

    @Test
    fun theMenuStaysInsideTheScreen() {
        shell()
        openTheMenu()
        val menu = rule.onNode(isPopup()).getUnclippedBoundsInRoot()
        // The off-screen bug the web had to fix with a container
        // query. Android gets this free from `DropdownMenu`, which
        // will not place itself outside the window — but free is not
        // the same as checked, and the button it hangs off is at the
        // right-hand edge, which is exactly where the web's went off.
        assertTrue(menu.left.value >= -1f, "the menu starts off the left edge at ${menu.left}")
        assertTrue(menu.top.value >= -1f, "the menu starts above the top edge at ${menu.top}")
    }
}
