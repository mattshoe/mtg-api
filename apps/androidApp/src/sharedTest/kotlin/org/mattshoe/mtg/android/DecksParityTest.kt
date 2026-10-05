package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.TokenCard
import org.mattshoe.mtg.core.Tweak
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decks screen on Android, against what the website does.
 *
 * Every sentence here is one the web suite already states — the
 * owner heading that counts its shelf, the typed sections in reading
 * order, the "has N" on a card the deck is short of, the four ways to
 * hand a deck over, the "⋯" that opens the sheet and not the card.
 * The fixtures are the web fixtures, deck for deck and card for card,
 * so a disagreement is the screen's and not the data's.
 *
 * Read on its own this file proves nothing: the whole point is that
 * it runs on a device. `DecksLayoutTest`, `DeckRowActionTest` and
 * `DecksStatsPageTest` are the siblings.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DecksParityTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------- the fixtures

    /** The web suite's deck, down to the set annotation on the commander. */
    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun twoOwners() = DecksState().loaded(
        listOf(
            deck("a", "kayla", "Bello"), deck("b", "kayla", "Chulane"),
            deck("c", "matt", "Alela"), deck("d", "matt", "Dihada"),
        ),
    )

    private fun card(name: String, type: String?, role: String? = null, owned: Int = 1) =
        DeckCard(
            name, qty = 1, role = role, owned = owned,
            nameNorm = name.lowercase(), typeLine = type, scryfallId = "abcdef12-3456",
        )

    /** Six real cards, one the commander, one nobody owns, one a basic. */
    private fun opened() = DecksState()
        .loaded(listOf(deck("a", "matt", "Alela")))
        .opened(
            "a",
            listOf(
                card("Alela, Artful Provocateur", "Legendary Creature — Faerie", "commander"),
                card("Sol Ring", "Artifact"),
                card("Zulaport Cutthroat", "Creature — Human Rogue"),
                card("Birds of Paradise", "Creature — Bird"),
                card("Rhystic Study", "Enchantment", owned = 0),
                card("Island", "Basic Land — Island"),
            ),
        )

    private fun withTokens() = opened().withTokens(
        listOf(
            TokenCard(
                "abcdef12-3456", "Bird", "Token Creature — Bird", "1", "1", "W",
                tcgplayer = "https://tcg.example/bird", madeBy = 2,
            ),
            TokenCard("bbcdef12-3456", "Bird", "Token Creature — Bird", "2", "2", "G"),
            TokenCard("ccdef123-4567", "Clue", "Token Artifact — Clue"),
        ),
    )

    // --------------------------------------------------------- the harness

    /** Mount, and wait until the tree is really there. */
    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { body() } } }
        rule.waitForIdle()
        // Anything at all on the screen, not a clickable.
        //
        // It waited for a click target, which worked only because
        // every state of this screen used to carry a New deck
        // button. The button moved to the entry wizard's first
        // question, so an empty, loading or failed shelf now has
        // nothing you can press and the wait sat there for ten
        // seconds before failing with a timeout rather than a fact.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun says(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun howMany(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun described(d: String) =
        rule.onAllNodesWithContentDescription(d).fetchSemanticsNodes().size

    private fun textOf(node: androidx.compose.ui.semantics.SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

    /**
     * Every heading, top to bottom.
     *
     * The web's finder is `div.panel-head h2`; the honest Android
     * equivalent is the heading semantics, which is also what a
     * screen reader navigates by.
     */
    private fun headings() = rule.onAllNodes(isHeading()).fetchSemanticsNodes()
        .sortedBy { it.positionInRoot.y }
        .map { textOf(it) }

    private fun yOf(text: String) =
        rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** The count tag sitting on the same row as a heading. */
    private fun tagBeside(title: String): String {
        val head = rule.onNode(isHeading() and hasTextExactly(title)).fetchSemanticsNode()
        val mid = head.positionInRoot.y + head.size.height / 2f
        return rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .filter { it.id != head.id && it.positionInRoot.x > head.positionInRoot.x }
            .filter { mid >= it.positionInRoot.y && mid <= it.positionInRoot.y + it.size.height }
            .minByOrNull { it.positionInRoot.x }
            ?.let { textOf(it) }
            .orEmpty()
    }

    // ------------------------------------------------------- the deck shelf

    @Test
    fun theShelfIsGroupedByOwnerWithACountOnEveryHeading() {
        content { DecksScreen(twoOwners(), {}, {}) }
        Parity.check(
            Parity.Fact("each owner has a heading of their own") {
                says("Kayla") && says("Matt")
            },
            Parity.Fact("the owner heading says how many decks are under it") {
                howMany("2 decks") == 2
            },
            Parity.Fact("the headings are the owners, in a stable order") {
                headings().take(2) == listOf("Kayla", "Matt")
            },
            Parity.Fact("Kayla's shelf comes before Matt's") {
                yOf("Kayla") < yOf("Matt")
            },
            Parity.Fact("every deck on both shelves is listed") {
                says("Bello") && says("Chulane") && says("Alela") && says("Dihada")
            },
        )
        rule.onRoot().shoot("decks-shelf")
    }

    @Test
    fun oneDeckIsADeckRatherThanOneDecks() {
        content { DecksScreen(DecksState().loaded(listOf(deck("a", "matt", "Alela"))), {}, {}) }
        Parity.check(
            Parity.Fact("a shelf of one says '1 deck'") { says("1 deck") },
            Parity.Fact("and does not say '1 decks'") { !says("1 decks") },
        )
    }

    @Test
    fun aTileWearsItsBracketItsPipsAndItsCommanderWithoutTheSetCode() {
        val deck = Deck(
            "alela", "Alela — Custom Dimir Faerie Tribal", "matt",
            "Alela, Artful Provocateur (ELD) 324", "Five-color (WUBRG)", 3, "abcdef12-3456",
        )
        content { DecksScreen(DecksState().loaded(listOf(deck)), {}, {}) }
        Parity.check(
            Parity.Fact("the tile-width name, not the prose after the dash") {
                says("Alela") && !says("Alela — Custom Dimir Faerie Tribal")
            },
            Parity.Fact("the commander, without its set annotation") {
                says("Alela, Artful Provocateur") && !says("Alela, Artful Provocateur (ELD) 324")
            },
            Parity.Fact("the bracket, as a tag") { says("bracket 3") },
            Parity.Fact("five pips, one per colour of the identity") {
                listOf("B", "G", "R", "U", "W").all { says(it) }
            },
        )
    }

    @Test
    fun aDeckWithNoCommanderSaysSoRatherThanLeavingTheLineBlank() {
        val bare = Deck("a", "Sixty", "matt", null, "UW", null, null)
        content { DecksScreen(DecksState().loaded(listOf(bare)), {}, {}) }
        Parity.check(
            Parity.Fact("an em dash where the commander would be") { says("—") },
            Parity.Fact("no bracket tag on a deck with no bracket") { !says("bracket") },
        )
    }

    @Test
    fun theThreeEmptyStatesEachSayWhichOneTheyAre() {
        content { DecksScreen(DecksState(), {}, {}, admin = true) }
        assertTrue(says("No decks yet."), "an empty shelf says nothing at all")
        // And no New deck button: a deck is started from the entry
        // wizard's first question now. Matt: "get rid of the one on
        // the decks list page."
        rule.onNodeWithText("New deck").assertDoesNotExist()
    }

    @Test
    fun aFailureNamesWhatWentWrong() {
        content { DecksScreen(DecksState().failed("boom"), {}, {}, admin = true) }
        assertTrue(says("Could not load decks: boom"), "the error is swallowed")
    }

    @Test
    fun loadingSaysItIsLoading() {
        content { DecksScreen(DecksState().loading(), {}, {}, admin = true) }
        assertTrue(says("Loading…"), "a loading shelf is blank")
    }

    @Test
    fun theTileIsTheTargetRatherThanAnOpenButton() {
        var opened: Deck? = null
        content { DecksScreen(twoOwners(), { opened = it }, {}) }
        rule.onNodeWithText("Bello").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("a", opened?.slug) }
    }

    // ------------------------------------------------------ one deck, opened

    @Test
    fun theOpenDeckLeadsWithTheBannerAndTheWayBack() {
        content { DecksScreen(opened(), {}, {}) }
        Parity.check(
            Parity.Fact("the way back is in the header") { says("← Decks") },
            // The web's hierarchy, which Android now follows: the
            // commander is the band's big line and the bracket, the
            // colours and the count are the small one under it. These
            // two facts used to read "Alela, Artful Provocateur ·
            // Bracket 3" as one demoted line under the deck's own
            // title, which is the arrangement section 5 of the audit
            // called out.
            Parity.Fact("the banner leads with the commander") {
                says("Alela, Artful Provocateur")
            },
            Parity.Fact("the bracket is in the small line under it") { says("Bracket 3") },
            // The deck's own name is deliberately absent. The web's
            // open-deck `page-head` carries the back button, the share
            // menu and the admin actions and no title at all — the
            // name was on the tile that was tapped to get here.
            Parity.Fact("the deck's own name is not repeated in the band") { !says("Alela") },
            Parity.Fact("and how many cards it holds") { says("6 cards") },
            Parity.Fact("a share is offered on an open deck") {
                described("Share this deck") == 1
            },
        )
        rule.onRoot().shoot("deck-open")
    }

    @Test
    fun theListIsGroupedByTypeInReadingOrderWithACountOnEachSection() {
        content { DecksScreen(opened(), {}, {}) }
        assertEquals(
            // `.panel-head h2 { text-transform: uppercase }` on the
            // web — the deck's own heading and every type section.
            listOf(
                "THE DECK AT A GLANCE",
                "COMMANDER", "CREATURES", "ARTIFACTS", "ENCHANTMENTS", "LANDS",
            ),
            headings(),
            "the sections are not the web's, in the web's order",
        )
        Parity.check(
            Parity.Fact("the two creatures are counted on their heading") {
                tagBeside("CREATURES") == "2"
            },
            Parity.Fact("one commander, counted") { tagBeside("COMMANDER") == "1" },
            Parity.Fact("a section nothing falls into is not drawn at all") {
                !says("Planeswalkers") && !says("Battles") && !says("Not in the collection")
            },
            Parity.Fact("each section is alphabetical inside") {
                yOf("Birds of Paradise") < yOf("Zulaport Cutthroat")
            },
        )
    }

    @Test
    fun everyCardRowCarriesItsNameItsTypeAndItsQuantity() {
        content { DecksScreen(opened(), {}, {}) }
        Parity.check(
            Parity.Fact("every card in the list is on the screen") {
                listOf(
                    "Alela, Artful Provocateur", "Sol Ring", "Zulaport Cutthroat",
                    "Birds of Paradise", "Rhystic Study", "Island",
                ).all { says(it) }
            },
            Parity.Fact("six rows, six quantities") { howMany("1×") == 6 },
            Parity.Fact("the type line is under the name") {
                says("Creature — Human Rogue") && says("Basic Land — Island")
            },
            Parity.Fact("a card the deck is short of says how many are owned") {
                says("has 0")
            },
            Parity.Fact("and a card nobody is short of says nothing") {
                howMany("has 0") == 1
            },
        )
    }

    @Test
    fun aBasicWithNoPrintingStillGetsItsTypeLineAndIsNeverShort() {
        // Nobody inventories basics, so the joins come back empty and
        // the card arrives with no type and no owner count. The web
        // names it from the card's own name and counts it as owned.
        val s = DecksState().loaded(listOf(deck("a", "matt", "Feather"))).opened(
            "a",
            listOf(
                DeckCard("Plains", 20, null, 0, nameNorm = "plains"),
                DeckCard("Rhystic Study", 1, null, 0, nameNorm = "rhystic study", typeLine = "Enchantment"),
            ),
        )
        content { DecksScreen(s, {}, {}) }
        Parity.check(
            Parity.Fact("a basic nobody owns is still named a Basic Land") {
                says("Basic Land — Plains")
            },
            Parity.Fact("a basic is never short, however many the deck wants") {
                // One "has 0", and it is the Enchantment's. Twenty
                // Plains nobody counted are not twenty to go and buy.
                howMany("has 0") == 1 &&
                    yOf("has 0") == yOf("Rhystic Study")
            },
            Parity.Fact("the twenty Plains are a land, not an unknown card") {
                headings().contains("LANDS")
            },
        )
    }

    @Test
    fun tappingACardAsksForItsDetailByNameNormAndOwner() {
        var asked: Pair<String, String>? = null
        content {
            DecksScreen(opened(), {}, {}, onOpenCard = { c, owner -> asked = c.nameNorm to owner })
        }
        rule.onNodeWithText("Sol Ring").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("sol ring" to "matt", asked) }
    }

    // ------------------------------------------------- the admin actions

    @Test
    fun theAdminActionsAreNotThereAtAllWhileLocked() {
        content { DecksScreen(opened(), {}, {}, admin = false) }
        Parity.check(
            Parity.Fact("no edit") { !says("Edit list") },
            Parity.Fact("no rename") { !says("Rename") },
            Parity.Fact("no disassemble") { !says("Disassemble") },
            Parity.Fact("no add") { !says("+ Add a card") },
            Parity.Fact("not one ⋯ on any row") {
                rule.onAllNodesWithContentDescription("Change Sol Ring")
                    .fetchSemanticsNodes().isEmpty() && !says("⋯")
            },
            Parity.Fact("and the list is otherwise untouched") {
                says("Sol Ring") && howMany("1×") == 6
            },
        )
    }

    @Test
    fun renameSitsBesideEditListOnceUnlocked() {
        var renamed: Deck? = null
        var edited: Deck? = null
        content {
            DecksScreen(
                opened(), {}, {}, admin = true,
                onEdit = { edited = it }, onRename = { renamed = it },
            )
        }
        rule.onNodeWithText("Edit list").assertExists()
        rule.onNodeWithText("Rename").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("a", renamed?.slug) }
        assertNull(edited, "Rename pressed the edit button")
    }

    @Test
    fun theAddButtonIsOfferedOnTheDeckItself() {
        var asked = false
        content { DecksScreen(opened(), {}, {}, admin = true, onAddCard = { asked = true }) }
        rule.onNodeWithText("+ Add a card").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(asked, "the add button does nothing") }
    }

    @Test
    fun everyCardRowGetsExactlyOneActionWhenUnlocked() {
        content { DecksScreen(opened(), {}, {}, admin = true) }
        Parity.check(
            Parity.Fact("one ⋯ per card, six cards") { howMany("⋯") == 6 },
            Parity.Fact("the commander's row has one too") {
                described("Change Alela, Artful Provocateur") == 1
            },
            Parity.Fact("so does a card nobody owns") {
                described("Change Rhystic Study") == 1
            },
            Parity.Fact("each says which card it acts on") {
                described("Change Sol Ring") == 1 && described("Change Island") == 1
            },
        )
    }

    @Test
    fun theActionOpensTheSheetOnThatCardAndDoesNotAlsoOpenTheCard() {
        var asked: Pair<String, Tweak?>? = null
        var openedCard: String? = null
        content {
            DecksScreen(
                opened(), {}, {}, admin = true,
                onOpenCard = { c, _ -> openedCard = c.nameNorm },
                onTweak = { c, t -> asked = c.nameNorm to t },
            )
        }
        rule.onNodeWithContentDescription("Change Sol Ring").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("sol ring", asked?.first, "the sheet opened on the wrong card")
            assertNull(asked?.second, "the row picked the change instead of asking")
            assertNull(openedCard, "the press fell through and opened the card page as well")
        }
        rule.onNodeWithContentDescription("Change Island").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("island", asked?.first, "the second press reopened the first card") }
    }

    @Test
    fun theRowStillOpensTheCardWithTheActionSittingOnIt() {
        var openedCard: String? = null
        var tweaked: String? = null
        content {
            DecksScreen(
                opened(), {}, {}, admin = true,
                onOpenCard = { c, owner -> openedCard = "${c.nameNorm}/$owner" },
                onTweak = { c, _ -> tweaked = c.nameNorm },
            )
        }
        rule.onNodeWithText("Zulaport Cutthroat").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("zulaport cutthroat/matt", openedCard, "the ⋯ broke the row it sits on")
            assertNull(tweaked, "opening the card also opened the sheet")
        }
    }

    @Test
    fun aTokenRowHasNoActionBecauseTheDeckDoesNotHaveThatCard() {
        content { DecksScreen(withTokens(), {}, {}, admin = true) }
        // Six, so the count did not quietly spill onto the tokens.
        assertEquals(6, howMany("⋯"), "a token the deck makes is offered a change to the deck list")
    }

    // ---------------------------------------------------------- the share

    @Test
    fun theShareOffersALinkOrTheListEitherWay() {
        val picked = mutableListOf<Pair<ShareWhat, ExportTo>>()
        content { DecksScreen(opened(), {}, {}, onShare = { w, e -> picked += w to e }) }
        Parity.check(
            Parity.Fact("the button is the icon, not the word") { !says("Share this deck") },
            Parity.Fact("nothing is open until it is pressed") { !says("Deck list") },
        )
        rule.onNodeWithContentDescription("Share this deck").performScrollTo().performClick()
        rule.waitForIdle()
        Parity.check(
            Parity.Fact("the menu says which half is which") {
                says("Link") && says("Deck list")
            },
            Parity.Fact("two ways to take the link, two to take the list") {
                howMany("Copy") == 2 && howMany("Download") == 2
            },
            Parity.Fact("'Link' is above its own two options") {
                yOf("Link") < rule.onAllNodesWithText("Copy")[0]
                    .fetchSemanticsNode().positionInRoot.y
            },
        )
        // The menu itself, not `onRoot()`. It is a `DropdownMenu` now,
        // so with it open there are two roots — the page and the
        // popup's own window — and `onRoot()` throws on the ambiguity.
        // Only ever on a device: `shoot` is `needsRealRendering`, so
        // the JVM skips it and this read as green until the emulator
        // ran it.
        rule.onNode(isPopup()).shoot("deck-share-menu")

        rule.onAllNodesWithText("Download")[1].performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf(ShareWhat.DECKLIST to ExportTo.FILE), picked)
        }
        assertTrue(howMany("Copy") == 0, "the menu stayed open over the page")
    }

    @Test
    fun eachOfTheFourReportsItself() {
        val picked = mutableListOf<Pair<ShareWhat, ExportTo>>()
        content { DecksScreen(opened(), {}, {}, onShare = { w, e -> picked += w to e }) }
        repeat(4) { n ->
            rule.onNodeWithContentDescription("Share this deck").performScrollTo().performClick()
            rule.waitForIdle()
            val rows = rule.onAllNodesWithText("Copy").fetchSemanticsNodes().size +
                rule.onAllNodesWithText("Download").fetchSemanticsNodes().size
            assertEquals(4, rows, "the menu does not offer four ways")
            when (n) {
                0 -> rule.onAllNodesWithText("Copy")[0].performScrollTo().performClick()
                1 -> rule.onAllNodesWithText("Download")[0].performScrollTo().performClick()
                2 -> rule.onAllNodesWithText("Copy")[1].performScrollTo().performClick()
                else -> rule.onAllNodesWithText("Download")[1].performScrollTo().performClick()
            }
            rule.waitForIdle()
        }
        assertEquals(
            listOf(
                ShareWhat.LINK to ExportTo.CLIPBOARD,
                ShareWhat.LINK to ExportTo.FILE,
                ShareWhat.DECKLIST to ExportTo.CLIPBOARD,
                ShareWhat.DECKLIST to ExportTo.FILE,
            ),
            picked,
        )
    }

    // ---------------------------------------------------------- the tokens

    @Test
    fun theTokensAreCardsBelowTheListWithTheirOwnCount() {
        content { DecksScreen(withTokens(), {}, {}) }
        assertEquals("TOKENS", headings().last(), "the tokens are not the last thing on the screen")
        Parity.check(
            Parity.Fact("the heading counts them") { tagBeside("TOKENS") == "3" },
            Parity.Fact("two Birds and a Clue") {
                howMany("Bird") == 2 && says("Clue")
            },
            Parity.Fact("each says what kind of token it is, without the 'Token '") {
                // Three: the two tokens and the card in the list that
                // happens to be a Bird as well.
                howMany("Creature — Bird") == 3 && says("Artifact — Clue") &&
                    !says("Token Creature — Bird")
            },
            Parity.Fact("two Birds that differ only by colour do not read identically") {
                says("1/1") && says("2/2") && says("W") && says("G")
            },
            Parity.Fact("a colourless token says so rather than showing nothing") {
                says("C")
            },
            Parity.Fact("how many the deck makes") { says("2×") },
            Parity.Fact("the one you can buy says it leaves the app") { howMany("↗") == 1 },
            Parity.Fact("and the panel says where they come from") {
                says(
                    "Made by the cards in this deck. You will want these to hand — " +
                        "each one goes to TCGplayer.",
                )
            },
        )
        rule.onNodeWithText("Clue").performScrollTo()
        rule.onRoot().shoot("deck-tokens")
    }

    @Test
    fun aDeckWithNoTokensHasNoTokenPanel() {
        content { DecksScreen(opened(), {}, {}) }
        assertTrue("Tokens" !in headings(), "an empty token panel is worse than none")
    }
}
