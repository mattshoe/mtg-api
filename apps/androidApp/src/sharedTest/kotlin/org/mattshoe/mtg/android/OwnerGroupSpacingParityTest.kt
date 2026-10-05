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

        // The two gaps the claim is about, which is the pair the
        // web measures as well: inside a shelf, and between two.
        //
        // It used to measure the first one from the "Decks" page
        // title down to the first owner's name. That title is gone —
        // the bottom bar says Decks and the page was saying it twice
        // — so the inside-a-group gap is measured where it actually
        // lives, between an owner's name and the first deck under
        // it. The claim is unchanged and so is the number it has to
        // beat.
        val gapInsideAShelf = topOf("Bello") - bottomOf("Kayla")
        // Between the end of Kayla's tiles and Matt's name.
        val gapBetweenShelves = topOf("Matt") - bottomOf("Chulane")

        assertTrue(gapInsideAShelf > 0f, "an owner's name does not even sit above their own decks")
        assertTrue(
            gapBetweenShelves > gapInsideAShelf * 1.5f,
            "the gap between two owners' shelves (${gapBetweenShelves}dp) is not meaningfully " +
                "bigger than the gap inside one (${gapInsideAShelf}dp) — two " +
                "owners read as one list",
        )
    }
}
