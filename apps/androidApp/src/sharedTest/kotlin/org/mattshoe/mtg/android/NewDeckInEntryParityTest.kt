package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckStep
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Starting a deck from the Entry screen, in the Entry screen's shape.
 *
 * Matt: "On the entry screen, we need a new option 'new deck' that
 * launches the new deck flow. Then get rid of the one on the decks
 * list page. I like the format of the entry flow, so make sure the
 * new deck flow matches that style exactly."
 *
 * "Exactly" is the part worth testing, and the only way to promise
 * it is to share the pieces rather than draw them twice — `Choice`,
 * `Foot` and the stepper now live in `Theme.kt` and both wizards use
 * them. What these check is that the deck wizard is actually built
 * out of them: a panel with a head, options you pick as rows, and a
 * ruled foot with the same two buttons in the same words. It used to
 * be an `AlertDialog` full of chips.
 */
@RunWith(AndroidJUnit4::class)
class NewDeckInEntryParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun shell(start: AppState) {
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

    private fun onEntry() = unlocked().navigate(View.ENTRY)

    private fun says(text: String) =
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scroll to a control, then press it.
     *
     * Both wizards are one long scrolling column and Robolectric's
     * screen is 470dp tall, so the foot of a panel with twelve
     * options in it is well past the bottom. A bare `performClick`
     * aimed at coordinates nobody can see, which is how the first
     * version of this managed to press Continue and change nothing.
     */
    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    // ------------------------------------------------- the way in

    @Test
    fun theFirstQuestionOffersADeckAsWellAsCardsInAndOut() {
        shell(onEntry())
        assertTrue(says("Add to the collection"), "the direction options went missing")
        assertTrue(says("Remove from the collection"), "the direction options went missing")
        assertTrue(says("New deck"), "the Entry screen does not offer a new deck")
    }

    @Test
    fun theDeckOptionIsAPickableRowLikeTheOtherTwo() {
        shell(onEntry())
        val deck = rule.onNodeWithText("New deck").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithText("Add to the collection").getUnclippedBoundsInRoot()
        assertTrue(
            deck.top.value > add.top.value,
            "the deck option is above the directions rather than a third answer under them",
        )
    }

    @Test
    fun pickingTheDeckAndContinuingOpensTheWizard() {
        shell(onEntry())
        tap("New deck")
        assertTrue(held.value.entry.startingADeck, "the pick did not register")
        tap("Continue →")
        assertTrue(Overlay.NEW_DECK in held.value.overlays, "Continue did not launch the wizard")
    }

    @Test
    fun continueIsDeadUntilSomethingIsPicked() {
        shell(onEntry())
        tap("Continue →")
        assertEquals(
            false,
            Overlay.NEW_DECK in held.value.overlays,
            "Continue launched the wizard with nothing picked",
        )
    }

    // ----------------------------------------- and out of the decks list

    @Test
    fun theDecksListNoLongerOffersOne() {
        shell(
            unlocked().navigate(View.DECKS).let {
                it.copy(decks = DecksState().loaded(listOf(Deck("a", "Alela", "matt", null, "UW", 3, null))))
            },
        )
        assertTrue(
            rule.onAllNodes(hasText("New deck")).fetchSemanticsNodes().isEmpty(),
            "the decks list still has its own New deck button",
        )
    }

    // ------------------------------------------- the shape of the wizard

    private fun inTheWizard() = shell(onEntry().opening(Overlay.NEW_DECK))

    @Test
    fun theWizardIsAScreenAndNotADialog() {
        inTheWizard()
        assertTrue(
            rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty(),
            "the deck wizard is still a dialog; the entry flow is a page",
        )
    }

    @Test
    fun everyStepIsAPanelThatSaysWhatItIsAsking() {
        inTheWizard()
        assertTrue(says("Which format?"), "the first step does not ask anything")
    }

    @Test
    fun theOptionsArePickableRowsAndNotChips() {
        inTheWizard()
        // `Choice` is a full-width row at a thumb's height. The old
        // wizard drew twelve Material chips, which is a different
        // control and a different screen.
        val box = rule.onNodeWithText("Commander").getUnclippedBoundsInRoot()
        assertTrue(
            box.bottom.value - box.top.value >= 47.5f,
            "a format option is only ${box.bottom.value - box.top.value}dp tall",
        )
    }

    @Test
    fun everyStepEndsInTheSameFootAsTheEntryFlow() {
        inTheWizard()
        tap("Commander")
        tap("Continue →")
        assertEquals(DeckStep.NAME, held.value.newDeck.step)
        assertTrue(says("← Back"), "no way back from the second step")
        assertTrue(says("Continue →"), "the wizard's buttons are not the entry wizard's")
    }

    @Test
    fun theFootsBackButtonStepsBackwards() {
        inTheWizard()
        tap("Commander")
        tap("Continue →")
        tap("← Back")
        assertEquals(DeckStep.FORMAT, held.value.newDeck.step)
    }

    @Test
    fun theFirstStepOffersAWayOutRatherThanADeadBackButton() {
        inTheWizard()
        assertTrue(says("Cancel"), "nothing closes the wizard from its first step")
        tap("Cancel")
        assertEquals(
            false,
            Overlay.NEW_DECK in held.value.overlays,
            "Cancel did not close the wizard",
        )
    }
}
