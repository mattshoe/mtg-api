package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Opening a deck starts at the top of the deck.
 *
 * It did not. The deck list and the deck detail were one `DecksScreen`
 * inside one scrolling `Column`, and the scroll offset was handed in
 * from `AppShell` keyed by slug. Scroll the list of decks down, tap
 * one, and the detail opened partway down itself — the deck's name
 * and its commander above the fold, because the container never went
 * back to zero.
 *
 * Matt, reporting it: "the deck detail shows at the same scroll
 * position as the page it came from... That should be its own
 * destination in the nav graph."
 *
 * He is right about the cause. A list and a thing from the list are
 * two destinations, and this app had them sharing one scroll
 * container and one `when` branch, which is what made the offset
 * leak between them in the first place. Splitting them is the fix;
 * resetting an offset would only have hidden it.
 */
@RunWith(AndroidJUnit4::class)
class DeckDetailIsItsOwnDestinationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun deck(key: String, name: String) = Deck(
        slug, name, "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null,
    )

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    /** Enough decks that the list is taller than any screen. */
    private fun manyDecks() = (0 until 30).map { deck("deck-$it", "Deck Number $it") }

    private fun listState() = AppState(
        route = Route(View.DECKS),
        admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"),
        decks = DecksState().loaded(manyDecks()),
    )

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    /** The real shell, driving its own navigation. */
    private fun shell(start: AppState = listState()) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        // The real activity fetches here; the test
                        // supplies the deck's cards directly, because
                        // what is under test is where the screen
                        // starts, not the network.
                        onOpenDeck = { key ->
                            held.value = held.value
                                .copy(
                                    decks = held.value.decks.opened(
                                        key,
                                        (0 until 40).map { card("Card Number $it") },
                                    ),
                                )
                                .navigate(Route(View.DECKS, key))
                        },
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollers() = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()

    /**
     * The pressable node with this label.
     *
     * `Ghost` and `DeckTile` paint their text inside a wrapper that
     * carries the click — `Ghost` since item 4.8 gave everything a
     * 48dp touch target — so `onNodeWithText` finds the label and not
     * the button. Pressing the label is how two of these tests
     * silently did nothing at all.
     */
    private fun pressable(label: String) = rule.onNode(hasText(label) and hasClickAction())

    @Test
    fun aDeckOpenedFromAScrolledListStartsAtItsOwnTop() {
        shell()

        // Get the list well down — the last deck, which on any screen
        // is far below the fold.
        rule.onNodeWithText("Deck Number 29").performScrollTo()
        rule.waitForIdle()

        pressable("Deck Number 29").performScrollTo().performClick()
        rule.waitForIdle()

        // The detail is showing.
        assertTrue(
            rule.onAllNodesWithText("Card Number 0").fetchSemanticsNodes().isNotEmpty(),
            "the deck did not open",
        )

        // And it starts at its own top. The way back is the first
        // thing in the detail's header, so if the container inherited
        // the list's offset this is off the top of the screen.
        val back = rule.onNodeWithText("← Decks").getUnclippedBoundsInRoot()
        assertTrue(
            back.top.value >= -1f,
            "the deck detail opened scrolled down — the way back is at ${back.top}, " +
                "above the top of the screen, because it inherited the list's offset",
        )
    }

    @Test
    fun theListAndTheDetailAreTwoSeparateScrollContainers() {
        shell()
        val onTheList = scrollers().size

        rule.onNodeWithText("Deck Number 29").performScrollTo()
        pressable("Deck Number 29").performScrollTo().performClick()
        rule.waitForIdle()

        // Not a count of containers — a check that the one the detail
        // is in is not the one the list was in. Two destinations, two
        // containers; the old code had one `Column` serving both,
        // which is why an offset could travel between them at all.
        assertTrue(onTheList >= 1, "the deck list does not scroll")
        assertTrue(
            rule.onAllNodesWithTag("deck-detail").fetchSemanticsNodes().isNotEmpty(),
            "the deck detail is not its own destination — there is no `deck-detail` " +
                "container, so it is still sharing the list's",
        )
        assertTrue(
            rule.onAllNodesWithTag("deck-list").fetchSemanticsNodes().isEmpty(),
            "the deck list is still composed underneath the open deck",
        )
    }

    @Test
    fun goingBackToTheListKeepsWhereTheListWas() {
        shell()
        rule.onNodeWithText("Deck Number 29").performScrollTo()
        rule.waitForIdle()
        val before = rule.onNodeWithText("Deck Number 29").getUnclippedBoundsInRoot().top.value

        pressable("Deck Number 29").performScrollTo().performClick()
        rule.waitForIdle()
        pressable("← Decks").performScrollTo().performClick()
        rule.waitForIdle()

        // The other half of the same rule. Splitting the two
        // destinations must not cost the list its place — that was
        // item 1.2 and it is why the scroll state is hoisted above
        // the `when` at all.
        val after = rule.onNodeWithText("Deck Number 29").getUnclippedBoundsInRoot().top.value
        assertTrue(
            kotlin.math.abs(after - before) < 2f,
            "coming back from a deck lost the list's place: was ${before}dp, now ${after}dp",
        )
    }

    @Test
    fun twoDifferentDecksDoNotShareAnOffset() {
        shell()
        pressable("Deck Number 0").performScrollTo().performClick()
        rule.waitForIdle()
        // Deep into the first deck.
        rule.onNodeWithText("Card Number 39").performScrollTo()
        rule.waitForIdle()

        // Back to the shelf and into a different deck, driven through
        // the route rather than by pressing `← Decks`. Whether that
        // button navigates is the previous test's subject and it
        // passes there; what is under test here is only whether deck
        // B inherits deck A's offset, and reading to the foot of a
        // forty-card deck scrolls the detail's own header off the top
        // of the screen, so pressing it is a gesture about layout
        // rather than about this.
        rule.runOnIdle { held.value = held.value.navigate(Route(View.DECKS)) }
        rule.waitForIdle()
        pressable("Deck Number 1").performScrollTo().performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithText("Card Number 0").fetchSemanticsNodes().isNotEmpty(),
            "the second deck did not open",
        )
        val back = rule.onNodeWithText("← Decks").getUnclippedBoundsInRoot()
        assertTrue(
            back.top.value >= -1f,
            "the second deck opened at the first deck's offset — " +
                "the way back is at ${back.top}, above the top of the screen",
        )
    }
}
