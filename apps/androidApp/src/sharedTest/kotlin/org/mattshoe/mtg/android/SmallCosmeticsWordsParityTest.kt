package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Ruling
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Four of section 6's smaller cosmetics, the ones that are words and
 * the colour of words.
 *
 * None of them is a layout bug, which is exactly why they outlived
 * four rounds of fixes: a tag in the wrong colour, a caption that
 * raised an alarm the website does not raise, a gap after a date that
 * is twice what the page draws, and a count that says "1 lines".
 *
 * Every one is read back as a resolved fact — the string the
 * semantics tree carries, and the `TextStyle` the node reports
 * through its own `GetTextLayoutResult` action — through a real
 * `AppShell` driven to the screen a person would be on. Not the
 * composable alone: four separate bugs in this project survived a
 * green suite because a component was hosted by itself.
 */
@RunWith(AndroidJUnit4::class)
class SmallCosmeticsWordsParityTest {

    @get:Rule
    val rule = createComposeRule()

    // --------------------------------------------------------- fixtures

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(
        name: String,
        typeLine: String = "Creature — Faerie",
        manaCost: String? = null,
        cmc: Double? = 2.0,
        produces: String? = null,
    ) = DeckCard(
        name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = typeLine,
        manaCost = manaCost,
        cmc = cmc,
        producedMana = produces,
        scryfallId = "abcdef12-3456",
    )

    /** A deck, open, the way the app opens one. */
    private fun opened(cards: List<DeckCard> = listOf(card("Sol Ring"))) = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin(token = "t"),
        decks = DecksState().loaded(listOf(deck())).opened("alela", cards),
    )

    /**
     * A real shell over mutable state, wired the way `MainActivity`
     * wires it.
     */
    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
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

    // ---------------------------------------------------------- reading

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    private fun SemanticsNodeInteraction.colour(): Color? = textLayout()?.layoutInput?.style?.color

    /** The one node whose text contains this, unmerged so a row is not the answer. */
    private fun holding(fragment: String) =
        rule.onNode(hasText(fragment, substring = true), useUnmergedTree = true)

    private fun wordsOf(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun nothingSays(fragment: String) = rule
        .onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isEmpty()

    // ====================================== the "going out" tag's colour

    @Test
    fun theGoingOutTagIsNotPaintedAsAWarning() {
        shell(
            opened(listOf(card("Alela, Artful Provocateur"), card("Sol Ring")))
                .copy(
                    deckTweak = DeckTweak.on(
                        deck(), "Alela, Artful Provocateur", card("Sol Ring"), Tweak.SWAP,
                    ),
                )
                .opening(Overlay.DECK_TWEAK),
        )
        val tag = rule.onNodeWithText("going out", useUnmergedTree = true)
        tag.assertExists()
        // `.tag.mini` with no tone class on the web, which resolves to
        // `var(--text-2)`. `.tag.warn` is a different class and this
        // is not it: the card leaving a swap is the ordinary half of
        // a swap, not a problem with it.
        assertEquals(
            Ink2,
            tag.colour(),
            "the card going out is marked in the colour the rest of this sheet keeps for trouble",
        )
    }

    // ============================ the "No source for X" caption's amber

    @Test
    fun theSplashWithNoSourcesIsTheLastSentenceOfTheCaptionAndNotAnAmberLine() {
        shell(
            opened(
                listOf(
                    card("Plains", "Basic Land — Plains", cmc = 0.0, produces = "W"),
                    card("Counterspell", "Instant", manaCost = "{U}{U}"),
                ),
            ),
        )
        // The website writes it into the same `.sub` div as the rest
        // of the caption, so it is one sentence more and not a second
        // thing on the page to notice.
        val caption = holding("No source for Blue.")
        caption.assertExists()
        val said = wordsOf(caption)
        assertNotNull(said, "nothing on the deck page says a colour has no source")
        assertTrue(
            said.startsWith("Pips the deck asks for"),
            "the splash warning is its own line rather than the end of the caption: \"$said\"",
        )
        assertEquals(
            Ink3,
            caption.colour(),
            "the caption's last sentence is a different colour from the caption",
        )
    }

    // ====================================== one space after a ruling date

    @Test
    fun aRulingHasOneSpaceAfterItsDateAndNotTwo() {
        shell(
            AppState(
                route = Route(View.CARD, "Sol Ring"),
                card = CardDetail(
                    name = "Sol Ring",
                    rulings = listOf(Ruling("2004-10-04", "It taps for two.")),
                ),
            ),
        )
        // The web writes `r.day + " "` and then the body, as one line.
        rule.onNodeWithText("2004-10-04 It taps for two.", useUnmergedTree = true)
            .assertExists()
        assertTrue(
            nothingSays("2004-10-04  It"),
            "the date is two spaces clear of its ruling, where the page sets one",
        )
    }

    // ============================= the edit dialog's line count, in English

    @Test
    fun oneLineIsCalledOneLine() {
        shell(
            opened()
                .copy(
                    deckEdit = DeckEditState(
                        slug = "alela", deckName = "Alela", list = "1 Sol Ring",
                    ),
                )
                .opening(Overlay.DECK_EDIT),
        )
        rule.onNodeWithText("The 99 — 1 line", useUnmergedTree = true).assertExists()
        assertTrue(nothingSays("1 lines"), "a one-card list is described as \"1 lines\"")
    }

    @Test
    fun twoLinesAreStillCalledLines() {
        shell(
            opened()
                .copy(
                    deckEdit = DeckEditState(
                        slug = "alela", deckName = "Alela", list = "1 Sol Ring\n1 Arcane Signet",
                    ),
                )
                .opening(Overlay.DECK_EDIT),
        )
        rule.onNodeWithText("The 99 — 2 lines", useUnmergedTree = true).assertExists()
    }
}
