package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DecksState
import kotlin.test.assertTrue

/**
 * One owner's shelf of decks has to read as a group apart from the
 * next owner's, not as one long list.
 *
 * The web gives 28px between one owner's decks and the next owner's
 * name against 11px between a heading and its own decks —
 * `DecksLayoutTest.thereIsRealAirBetweenOneOwnersDecksAndTheNextOwnersName`
 * and `.anOwnerHeadingIsSeparatedFromTheDecksUnderIt` on the web side.
 * Android used a uniform 8dp everywhere, so two owners read as one
 * shelf. The claim worth proving is the relationship — a bigger gap
 * between groups than inside one — not a pixel count that breaks on
 * the next density.
 */
@RunWith(AndroidJUnit4::class)
class OwnerGroupSpacingParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun twoOwners() = DecksState().loaded(
        listOf(
            deck("a", "kayla", "Bello"), deck("b", "kayla", "Chulane"),
            deck("c", "matt", "Alela"), deck("d", "matt", "Dihada"),
        ),
    )

    private fun bottomOf(text: String) =
        rule.onNodeWithText(text).getUnclippedBoundsInRoot().bottom.value

    private fun topOf(text: String) =
        rule.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    @Test
    fun theGapBetweenTwoOwnersIsBiggerThanTheGapInsideOne() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Surface { DecksScreen(twoOwners(), {}, {}) } }
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }

        // Both of these are "the gap above an owner's own heading" —
        // the same kind of gap, measured twice, so whatever a heading
        // always carries (its own top padding, the column's own gap)
        // is common to both and cancels out. The only thing that can
        // tell them apart is whichever owner is not the first one.
        //
        // Before the very first heading: the page title to Kayla's name.
        val gapBeforeFirstOwner = topOf("Kayla") - bottomOf("Decks")
        // Before the second owner's heading: the end of Kayla's tiles
        // to Matt's name.
        val gapBeforeSecondOwner = topOf("Matt") - bottomOf("Chulane")

        assertTrue(gapBeforeFirstOwner > 0f, "the page title does not even sit above the first shelf")
        assertTrue(
            gapBeforeSecondOwner > gapBeforeFirstOwner * 1.5f,
            "the gap above a second owner's heading (${gapBeforeSecondOwner}dp) is not meaningfully " +
                "bigger than the gap above the very first one (${gapBeforeFirstOwner}dp) — two " +
                "owners read as one list",
        )
    }
}
