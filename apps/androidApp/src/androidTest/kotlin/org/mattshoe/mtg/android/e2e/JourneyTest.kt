package org.mattshoe.mtg.android.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app, end to end, on a phone.
 *
 * Matt: "Make sure you use proper E2E support on android."
 *
 * Everything here goes through the real `MainActivity`: the real
 * `MtgViewModel`, the real `MtgApi` over a real socket, carrying the
 * SQL `:core` writes, against a real SQLite holding the
 * repository's own fixture. Nothing is handed a prepared `AppState`
 * and nothing is asserted about a composable in isolation.
 *
 * That is the gap this closes. The 433 tests in `sharedTest` mount
 * `AppShell` and ask whether the chrome is right, and four of them
 * have been green while the screen they described was broken,
 * because the state they fed it was state the app could not
 * actually produce. A journey cannot make that mistake: if the
 * query is wrong, or the loader never runs, or the row never
 * arrives, nothing is on screen to click.
 */
@RunWith(AndroidJUnit4::class)
internal class JourneyTest : E2eTest() {

    /** A deck that is in the fixture. `Deck.title` cuts at the dash. */
    private val aDeck = "Chaos Incarnate"

    @Test
    fun openingTheAppShowsCardsTheDatabaseActuallyHas() {
        settled()
        compose.onNodeWithTag("library").assertIsDisplayed()
        // Scrolled to, not assumed on screen. The Library's first
        // grid item is the whole search panel and every filter
        // accordion, so the first card tile starts well below the
        // fold — and a row a lazy grid has not composed is exactly
        // the thing this repository once wrote a green test about.
        //
        // The name comes from what the app actually got rather than
        // from the fixture by hand, so this cannot drift into
        // asserting on a card the query no longer returns.
        showCard(firstCardOnScreen()).assertIsDisplayed()
        assertTrue(
            fake.statements.any { it.contains("FROM cards", ignoreCase = true) },
            "the app never asked the database for any cards: ${fake.statements}",
        )
    }

    @Test
    fun tappingACardOpensItAndBackComesHome() {
        settled()
        showCard(firstCardOnScreen()).performClick()
        // Through the carousel, which is what a tile opens now. The
        // card's own page is "Full details" on its sheet.
        until("tapping a card did not open the carousel") {
            compose.onAllNodesWithTag("card-carousel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Full details").performClick()
        until("Full details did not open the card") { state().view == View.CARD }
        until("the card page never got its text") {
            state().card?.face?.oracleText?.isNotBlank() == true
        }
        // Exists, not displayed. Whether the oracle box has scrolled
        // into view depends on how tall the phone is — it was on
        // screen on a 2424px emulator and below the fold on CI's —
        // and how a page lays out at a given height is what the
        // measured tests in `sharedTest` are for. What a journey is
        // entitled to claim is that tapping a card composed the
        // card's page, with its text in it.
        //
        // `onAllNodes`, because the first card in the fixture is
        // double-faced and a two-faced card draws an oracle box per
        // face. Asking for "the" oracle box found two and threw.
        compose.onAllNodesWithTag("oracle").onFirst().assertExists()
        // The header is at the top whatever the height, and on a
        // card it carries the card's name — the one place the
        // bottom bar cannot say where you are.
        compose.onNodeWithTag("topbar-title").assertIsDisplayed()
        assertEquals(
            state().card?.name,
            headerSays(),
            "the card opened but the header is not naming it",
        )

        compose.activity.onBackPressedDispatcher.let { dispatcher ->
            compose.runOnUiThread { dispatcher.onBackPressed() }
        }
        until("back did not return to the Library") { state().view == View.LIBRARY }
        assertTrue(
            state().library.rows.isNotEmpty(),
            "the Library came back empty, so going to a card threw its results away",
        )
    }

    @Test
    fun theDecksTabListsWhatTheDatabaseHas() {
        settled()
        compose.onNodeWithContentDescription("Decks").performClick()
        until("the deck list never filled") { state().decks.decks.isNotEmpty() }
        compose.onNodeWithTag("deck-list").assertIsDisplayed()
        compose.onAllNodesWithText(aDeck, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun openingADeckShowsItsCards() {
        settled()
        compose.onNodeWithContentDescription("Decks").performClick()
        until("the deck list never filled") { state().decks.decks.isNotEmpty() }
        compose.onAllNodesWithText(aDeck, substring = true).onFirst().performClick()
        until("the deck never opened") { state().route.rest.isNotEmpty() }
        until("the deck opened with no cards in it") { state().decks.cards.isNotEmpty() }
        compose.onNodeWithTag("deck-detail").assertIsDisplayed()
    }

    @Test
    fun loggingInThroughTheProfileBringsTheEntryTab() {
        settled()
        // The real `/admin` round trip: the password goes to the
        // server, a token comes back, and the bar grows a tab. Three
        // separate things that have each been broken on their own.
        assertTrue(
            compose.onAllNodesWithText("Entry").fetchSemanticsNodes().isEmpty(),
            "Entry is in the bar before anybody logged in",
        )
        openTheProfile(andFind = "Log in")
        compose.onNodeWithText("Log in").performClick()
        compose.onNode(hasText("Password")).performTextInput(fake.password)
        compose.onNodeWithText("Unlock").performClick()
        until("the unlock never came back") { state().admin.unlocked }
        compose.onNodeWithContentDescription("Entry").assertIsDisplayed()
    }

    @Test
    fun aWrongPasswordIsRefusedAndSaysSo() {
        settled()
        openTheProfile(andFind = "Log in")
        compose.onNodeWithText("Log in").performClick()
        compose.onNode(hasText("Password")).performTextInput("nope")
        compose.onNodeWithText("Unlock").performClick()
        until("the app never heard back about the wrong password") {
            !state().admin.trying && state().toast != null
        }
        assertEquals(false, state().admin.unlocked, "a wrong password unlocked the app")
        assertTrue(
            state().toastFailed,
            "a refused password was reported as if it had worked: ${state().toast}",
        )
    }

    @Test
    fun turningThePhoneOverKeepsWhatWasLoaded() {
        // Both loads finished first, on purpose. Snapshotting the
        // traffic while the facet load is still in flight measures
        // the race and not the rotation — it reported one extra
        // statement that way, which was the opening load finishing,
        // not the new activity asking again.
        settled()
        val before = state().library.rows.size
        val asked = fake.statements.toList()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        assertEquals(
            before,
            state().library.rows.size,
            "a configuration change emptied the Library",
        )
        val again = fake.statements.toList().drop(asked.size)
        assertTrue(
            again.isEmpty(),
            "the recreated activity asked the server ${again.size} more things, so the " +
                "rotation re-ran work the ViewModel was already holding: $again",
        )
    }

    /** What the header actually says, read back off the node. */
    private fun headerSays(): String =
        compose.onNodeWithTag("topbar-title", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)
            .orEmpty().joinToString("") { it.text }

    /**
     * The profile, open, with the row this test is about on screen.
     *
     * Waiting for the menu's own container was not enough: it is
     * there a frame before its rows are, and the click then missed.
     * So the wait is for the row itself, which is also the thing
     * being clicked.
     */
    private fun openTheProfile(andFind: String) {
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.waitForIdle()
        until("the profile menu never offered \"$andFind\"") {
            compose.onAllNodesWithText(andFind).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Scroll the Library down to a card tile and hand back its node.
     *
     * By tag and not by the card's name: the tile is `clickable`, so
     * Compose merges the picture, the name and the count into one
     * node, and scrolling to "the node whose text is this name"
     * found nothing. The name is still checked — on the node this
     * returns — but finding it is the tag's job.
     */
    private fun showCard(name: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library").performScrollToNode(
            hasTestTag("card-tile") and hasContentDescription(name),
        )
        compose.waitForIdle()
        return compose.onNode(hasTestTag("card-tile") and hasContentDescription(name))
    }
}
