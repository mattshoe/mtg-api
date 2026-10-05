package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.Fact
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Tally
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The deck tweak sheet, on a device, held against the website's.
 *
 * The phone did not have this screen at all: adding one card to a deck
 * meant retyping the whole list into the bulk editor. So there was no
 * drift to find here — there was nothing. These tests are what says
 * the new screen is the same screen, and every sentence they assert is
 * one the web suite already asserts about `DeckTweakSheet.kt`:
 * `TweakFinderTest` and `TweakCounterTest`, plus the core's own
 * `DeckTweakTest`.
 *
 * Four screens in one sheet, so four blocks below: what to do, which
 * card, how many, and what that would mean. Nothing here touches the
 * network — the sheet is handed a `DeckTweak` and hands one back,
 * which is the whole reason the state lives in `:core`.
 *
 * Every one of them goes through a real `AppShell`, over an open deck,
 * on a screen with edges. It used to mount the sheet alone in a `Box`
 * of its own width, and that is why seventy green tests here said
 * nothing at all about the sheet not being a dialog: a box has no
 * screen to run off, nothing behind it to dim, and no outside to tap.
 * `TweakSheetIsADialogTest` is the half of this that asserts those
 * three things; this half is everything the sheet says and does, now
 * asked of it where it actually lives.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TweakSheetParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The whole app's state, held the way `MainActivity` holds it. */
    private val live = mutableStateOf(AppState())

    /** What the sheet asked the server to look up, in order. */
    private val asked = mutableListOf<String>()
    private var previewAsked = 0
    private var applyAsked = 0

    /**
     * The sheet, reached the way a person reaches it.
     *
     * A real `AppShell`, over the deck the tweak is about, with
     * `Overlay.DECK_TWEAK` up — so the sheet is drawn by the code that
     * actually draws it, on a screen of the device's own size, with the
     * deck list behind it. The old mount handed `DeckTweakSheet` its
     * own callbacks inside a `Box(Modifier.width(400.dp))`, which is
     * the seam the missing dialog lived in.
     */
    private fun sheet(start: DeckTweak) {
        live.value = AppState(
            route = Route(View.DECKS, "alela"),
            admin = Admin(token = "t"),
            decks = DecksState().loaded(listOf(deck)).opened(
                "alela",
                listOf(card("Sol Ring", 1)),
            ),
        ).copy(deckTweak = start).opening(Overlay.DECK_TWEAK)

        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = live.value,
                        onState = { live.value = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onTweakFind = { asked += it },
                        onTweakPreview = { previewAsked++ },
                        onTweakApply = { applyAsked++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        // `setContent` returns before the host activity has necessarily
        // finished launching on a cold emulator, and a finder that runs
        // first fails with "No compose hierarchies found" — which reads
        // exactly like a real failure and is not one. The sheet is its
        // own window now, so this waits for that window and not merely
        // for the shell underneath it.
        rule.waitUntil(timeoutMillis = 10_000) { present("tweak-sheet") }
    }

    /** What the sheet holds now, after whatever was just pressed. */
    private fun state() = live.value.deckTweak ?: DeckTweak()

    /**
     * Whether the sheet is still up.
     *
     * Closing it is the shell's business — `onClose` is
     * `state.closing(Overlay.DECK_TWEAK)` — so "it closed" is a
     * question about the app's state, not a counter in the test.
     */
    private val up: Boolean get() = Overlay.DECK_TWEAK in live.value.overlays

    /** Push a server answer in, the way the shell's coroutine does. */
    private fun push(next: DeckTweak) {
        rule.runOnIdle { live.value = live.value.copy(deckTweak = next) }
        rule.waitForIdle()
    }

    // ---------------------------------------------------------- reading

    /**
     * Every node matching, inside the sheet and nothing else.
     *
     * Scoped on purpose. The sheet is hosted in a real shell now, so
     * the nav row and the open deck are on screen behind it, and a
     * dialog's `boundsInRoot` lands in the same coordinate space as the
     * shell's — so an unscoped search let the deck underneath answer
     * questions asked about the sheet, in both directions: "the card is
     * named" passing because the list behind says so, and "nothing runs
     * off the edge" failing because something behind it did.
     */
    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> {
        // The sheet is a dialog, so it is its own window and its own
        // semantics root. Same root means inside the sheet; anything
        // else is the shell behind it.
        val sheet = rule.onAllNodesWithTag("tweak-sheet").fetchSemanticsNodes().firstOrNull()
            ?: return emptyList()
        return rule.onAllNodes(matcher).fetchSemanticsNodes()
            .filter { it.root === sheet.root }
    }

    /**
     * The sheet's right edge, in the units a node's bounds are in.
     *
     * `getUnclippedBoundsInRoot()` is in dp and `SemanticsNode.boundsInRoot`
     * is in pixels, and comparing one against the other said everything
     * on a 400dp sheet ran off a 400dp sheet — by exactly the density.
     * The overflow checks were measuring the screen's density, not the
     * layout.
     */
    private fun rightEdgePx(): Float {
        val d = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        return rule.onNodeWithTag("tweak-sheet").getUnclippedBoundsInRoot().right.value * d + 1f
    }

    private fun says(text: String, substring: Boolean = false) =
        nodes(hasText(text, substring = substring)).isNotEmpty()

    private fun howMany(text: String, substring: Boolean = false) =
        nodes(hasText(text, substring = substring)).size

    private fun present(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * Pressable, or on screen and refused.
     *
     * `Primary` and the counter's steps both mark a dead control
     * `disabled()` rather than removing it, the same as the web's
     * `disabled` attribute, so "absent" and "refused" stay different
     * answers.
     */
    private fun pressableTag(tag: String): Boolean {
        val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return false
        return !node.config.contains(SemanticsProperties.Disabled)
    }

    private fun pressableText(text: String): Boolean {
        val node = nodes(hasText(text)).firstOrNull() ?: return false
        return !node.config.contains(SemanticsProperties.Disabled)
    }

    /** The name a screen reader would read off that control. */
    private fun named(tag: String): String? =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            ?.firstOrNull()

    /** The number the counter is actually showing, which is all a person sees. */
    private fun shown(): String {
        val node = rule.onAllNodesWithTag("tweak-qty").fetchSemanticsNodes().first()
        if (node.config.contains(SemanticsProperties.EditableText)) {
            return node.config[SemanticsProperties.EditableText].text
        }
        return node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
    }

    /** The one-line summary the sheet puts under everything. */
    private fun summary(): String =
        rule.onAllNodesWithTag("tweak-says").fetchSemanticsNodes().first()
            .config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("") { it.text }

    /** Every hit on screen, top to bottom, as the words on the row. */
    private fun hitRows(): List<String> =
        (0 until 12).takeWhile { present("tweak-hit-$it") }.map { wordsUnder("tweak-hit-$it") }

    /** Everything written inside that region, joined, in reading order. */
    private fun wordsUnder(tag: String): String {
        val box = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return ""
        val b = box.boundsInRoot
        return nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .filter {
                it.boundsInRoot.top >= b.top - 1 && it.boundsInRoot.bottom <= b.bottom + 1 &&
                    it.boundsInRoot.left >= b.left - 1 && it.boundsInRoot.right <= b.right + 1
            }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
            .joinToString(" ")
    }

    private fun height(tag: String) =
        rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    private fun width(tag: String) =
        rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.right.value - it.left.value }

    /**
     * How light that control is on average, 0 to 1.
     *
     * The person who owns this collection cannot separate two hues, so
     * "off" has to read as a difference in lightness. Measuring the
     * pixels is the only way to assert that — a colour constant in the
     * source proves nothing about what reached the screen.
     */
    private fun lightness(tag: String): Double {
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        var total = 0.0
        var n = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                val r = ((p shr 16) and 0xFF) / 255.0
                val g = ((p shr 8) and 0xFF) / 255.0
                val b = (p and 0xFF) / 255.0
                total += 0.2126 * r + 0.7152 * g + 0.0722 * b
                n++
            }
        }
        return if (n == 0) 0.0 else total / n
    }

    private fun fact(what: String, holds: () -> Boolean) = Fact(what, holds)

    private fun type(text: String) = rule.onNodeWithTag("tweak-term").performTextReplacement(text)

    private fun typeCount(text: String) = rule.onNodeWithTag("tweak-qty").performTextReplacement(text)

    // --------------------------------------------------------- fixtures
    //
    // The same deck, card and hits the web suites use, so a
    // disagreement is about the screen and not about what it was handed.

    private val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UW", 3, null)

    private fun found(
        name: String,
        qty: Int = 0,
        owner: String = "",
        type: String? = "Instant",
        id: Long = 1,
    ) = Found(id, name, "sf-$id", type, qty, owner)

    private val bolt = found("Lightning Bolt", qty = 4, owner = "matt", id = 1)

    private fun card(name: String, qty: Int, type: String? = "Artifact") =
        DeckCard(name, qty, null, qty, nameNorm = name.lowercase(), typeLine = type)

    /** Opened on a card with the choice still to make. The sheet's first screen. */
    private fun choosing(have: Int = 3) =
        DeckTweak.on(deck, "Alela, Artful Provocateur", card("Sol Ring", have))

    private fun adding() = DeckTweak.add(deck, "Alela, Artful Provocateur")

    private fun swapping() =
        DeckTweak.on(deck, "Alela, Artful Provocateur", card("Sol Ring", 1), Tweak.SWAP)

    private fun counting(have: Int = 3) =
        DeckTweak.on(deck, "Alela, Artful Provocateur", card("Swamp", have, "Basic Land — Swamp"), Tweak.QUANTITY)

    private fun removing() =
        DeckTweak.on(deck, "Alela, Artful Provocateur", card("Sol Ring", 2), Tweak.REMOVE)

    private fun plan() = DeckPlan(
        cardCount = 99,
        added = listOf(Tally("Lightning Bolt", 1)),
        removed = listOf(Tally("Sol Ring", 1)),
        acquired = listOf(Tally("Lightning Bolt", 1)),
        returned = listOf(Tally("Sol Ring", 1)),
        dryRun = true,
    )

    // ================================================ 1. what to do

    @Test
    fun theSheetOpensAskingWhatToDoAndHasDecidedNothing() {
        sheet(choosing())
        rule.onNodeWithTag("tweak-scrim").shoot("tweak-1-choose")

        Parity.check(
            fact("the sheet is headed 'Change this card' while nothing is chosen") {
                says("Change this card")
            },
            fact("no kind of change has been decided") { state().kind == null },
            fact("the deck it belongs to is named") { says("Alela") },
            fact("there is a way out of the sheet") { named("tweak-close") == "Close" },
        )
    }

    @Test
    fun theThreeChoicesAreTheWebsitesInItsOrder() {
        sheet(choosing())
        Parity.check(
            fact("the first choice is the website's swap") {
                wordsUnder("tweak-opt-0").startsWith("Swap it for another card")
            },
            fact("the second is the website's count") {
                wordsUnder("tweak-opt-1").startsWith("Change how many")
            },
            fact("the third is the website's removal") {
                wordsUnder("tweak-opt-2").startsWith("Take it out of the deck")
            },
            fact("there is no fourth") { !present("tweak-opt-3") },
        )
    }

    @Test
    fun everyChoiceCarriesTheWebsitesLineOfHelp() {
        sheet(choosing())
        Parity.check(
            fact("the swap says what it does to the number") {
                says("Takes the same number out as it puts in.")
            },
            fact("the removal says where the copies go") { says("The copies go back to bulk.") },
        )
    }

    @Test
    fun theCountChoiceSaysHowManyAreInTheDeck() {
        sheet(choosing(have = 3))
        assertTrue(says("There are 3 in the deck."), "it did not say how many are in the deck")
    }

    @Test
    fun andSaysItInTheSingularForOne() {
        sheet(choosing(have = 1))
        assertTrue(says("There is 1 in the deck."), "one card was described in the plural")
        assertFalse(says("There are 1 in the deck."), "one card was described in the plural")
    }

    @Test
    fun theCardBeingActedOnIsAboveTheChoices() {
        sheet(choosing())
        val subject = rule.onNodeWithTag("tweak-subject").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag("tweak-opt-0").getUnclippedBoundsInRoot()
        assertTrue(says("Sol Ring"), "the sheet does not name the card it is about")
        assertTrue(
            subject.bottom.value <= first.top.value + 1,
            "the card sits under the choices rather than over them",
        )
    }

    @Test
    fun everyChoiceIsAThumbSizedTarget() {
        sheet(choosing())
        listOf(0, 1, 2).forEach {
            assertTrue(
                height("tweak-opt-$it") >= 44f,
                "choice $it is ${height("tweak-opt-$it")}dp tall, under the 44 a thumb needs",
            )
        }
    }

    @Test
    fun nothingIsAskedForBeforeTheChoiceIsMade() {
        sheet(choosing())
        Parity.check(
            fact("no card finder while the sheet is still asking") { !present("tweak-term") },
            fact("no number while the sheet is still asking") { !present("tweak-counter") },
            fact("nothing is offered to preview yet") { !says("Preview →") },
        )
    }

    @Test
    fun choosingTheSwapRetitlesTheSheetAndAsksForACard() {
        sheet(choosing())
        rule.onNodeWithTag("tweak-opt-0").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the choice was recorded") { state().kind == Tweak.SWAP },
            fact("the sheet is now headed with the website's swap title") {
                says(Tweak.SWAP.title)
            },
            fact("a card finder appeared") { present("tweak-term") },
            fact("the choices are gone") { !present("tweak-opt-0") },
        )
    }

    @Test
    fun choosingTheCountStartsAtTheNumberAlreadyInTheDeck() {
        sheet(choosing(have = 3))
        rule.onNodeWithTag("tweak-opt-1").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the choice was recorded") { state().kind == Tweak.QUANTITY },
            fact("the number starts where the deck is") { shown() == "3" },
            fact("a count needs no card finder") { !present("tweak-term") },
            fact("nothing is ready to preview until the number moves") { !state().ready },
        )
    }

    @Test
    fun choosingTheRemovalOffersNeitherACardNorANumber() {
        sheet(choosing(have = 2))
        rule.onNodeWithTag("tweak-opt-2").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the choice was recorded") { state().kind == Tweak.REMOVE },
            fact("a removal takes the whole row, so there is no number") { !present("tweak-counter") },
            fact("and no card is coming in") { !present("tweak-term") },
            fact("it is ready to preview on its own") { state().ready },
        )
    }

    @Test
    fun theWayOutOfTheSheetWorks() {
        sheet(choosing())
        rule.onNodeWithTag("tweak-close").performClick()
        rule.waitForIdle()
        assertFalse(up, "the close button did not close the sheet")
    }

    // =================================================== 2. the finder

    @Test
    fun aTermShorterThanTheMinimumAsksTheServerNothing() {
        // One letter matches the whole collection. Asking for it is a
        // round trip whose answer is useless.
        sheet(adding())
        type("l")
        rule.waitForIdle()
        Parity.check(
            fact("one letter did not go to the server") { asked.isEmpty() },
            fact("the box kept what was typed") { state().term == "l" },
            fact("a one-letter term listed nothing") { !present("tweak-hit-0") },
        )
    }

    @Test
    fun exactlyTheMinimumIsEnoughToAsk() {
        sheet(adding())
        type("li")
        rule.waitForIdle()
        assertEquals(listOf("li"), asked, "the minimum term was not asked for")
    }

    @Test
    fun whitespaceAloneIsNotATerm() {
        sheet(adding())
        type("   ")
        rule.waitForIdle()
        Parity.check(
            fact("three spaces were not sent as a search") { asked.isEmpty() },
            fact("whitespace listed nothing") { !present("tweak-hit-0") },
            fact("and it did not call anything missing") { !present("tweak-none") },
        )
    }

    @Test
    fun theFinderOnlyAppearsWhenACardIsComingIn() {
        sheet(removing())
        assertFalse(present("tweak-term"), "a removal offered a card finder")
    }

    @Test
    fun andItIsThereForAnAdd() {
        sheet(adding())
        assertTrue(present("tweak-term"), "an add has no card finder")
    }

    @Test
    fun whatComesBackIsListedOnePerHit() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix", "Lightning Strike")))
        Parity.check(
            fact("the hits are one row each") { hitRows().size == 3 },
            fact("the first hit is the card found") { hitRows()[0].contains("Lightning Bolt") },
            fact("there is no fourth row") { !present("tweak-hit-3") },
        )
        rule.onNodeWithTag("tweak-scrim").shoot("tweak-2-finder")
    }

    @Test
    fun whatIsOwnedIsListedAheadOfWhatIsNot() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix")))
        val rows = hitRows()
        assertTrue(rows[0].startsWith("Lightning Bolt"), "the owned card is not first: $rows")
        assertTrue(rows[1].startsWith("Lightning Helix"), "the unowned card is not second: $rows")
    }

    @Test
    fun whetherACardIsOwnedIsSaidInWordsNotOnlyInColour() {
        // Red-on-grey is not a difference to the person looking at this.
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix")))
        val rows = hitRows()
        Parity.check(
            fact("the owned hit says how many") { rows[0].contains("4×") },
            fact("the owned hit says whose it is") { rows[0].contains("matt") },
            fact("the unowned hit says so in words") { rows[1].contains("not owned") },
        )
    }

    @Test
    fun aHitSaysEnoughToTellTwoOfTheSameNameApart() {
        // The collection groups by owner, so the same card can come
        // back twice. If the two rows read identically, picking is a
        // coin toss.
        val mine = found("Sol Ring", qty = 2, owner = "matt", type = "Artifact", id = 7)
        val hers = found("Sol Ring", qty = 1, owner = "kayla", type = "Artifact", id = 8)
        sheet(adding())
        type("sol")
        push(state().searched(listOf(mine, hers)))
        val rows = hitRows()
        Parity.check(
            fact("two owners of one card did not collapse into one hit") { rows.size == 2 },
            fact("the two hits do not read exactly the same") { rows[0] != rows[1] },
            fact("both hits say what the card is") { rows.all { it.contains("Artifact") } },
        )
    }

    @Test
    fun theSameCardIsNotOfferedTwice() {
        // Scryfall knows every card the collection holds, and two rows
        // of one printing are one card to pick.
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt, bolt.copy(id = 9)), listOf("lightning bolt", "Lightning Helix")))
        val rows = hitRows()
        assertEquals(2, rows.size, "one card was offered more than once: $rows")
        assertEquals(1, rows.count { it.startsWith("Lightning Bolt") }, "Lightning Bolt is listed twice")
    }

    @Test
    fun nothingFoundSaysSoRatherThanShowingAnEmptyVoid() {
        sheet(adding())
        type("qqqq")
        push(state().searched(emptyList(), emptyList()))
        Parity.check(
            fact("nothing was listed for a term that found nothing") { !present("tweak-hit-0") },
            fact("the sheet says the card does not exist, by name") {
                says("No card called “qqqq”.")
            },
        )
    }

    @Test
    fun andSaysNothingBeforeTheAnswerIsBack() {
        // "No such card" the instant the second letter lands is a lie
        // that then corrects itself.
        sheet(adding())
        type("qq")
        rule.waitForIdle()
        Parity.check(
            fact("it did not call the card missing before looking") { !present("tweak-none") },
            fact("it knows it is still looking") { state().searching },
        )
    }

    @Test
    fun aHitIsARealButton() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt)))
        val node = rule.onNodeWithTag("tweak-hit-0").fetchSemanticsNode()
        Parity.check(
            fact("a hit can be pressed") { node.config.contains(SemanticsActions.OnClick) },
            fact("a hit announces itself as a button") {
                node.config.contains(SemanticsProperties.Role) &&
                    node.config[SemanticsProperties.Role] == androidx.compose.ui.semantics.Role.Button
            },
            fact("a hit says which card it is") {
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .firstOrNull() == "Lightning Bolt"
            },
        )
    }

    @Test
    fun pickingAHitShowsItAndClosesTheList() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix")))
        rule.onNodeWithTag("tweak-hit-0").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the tap picked the card") { state().pick?.name == "Lightning Bolt" },
            fact("the list closed over the choice") { !present("tweak-hit-0") },
            fact("the sheet shows the card chosen") {
                wordsUnder("tweak-pick").contains("Lightning Bolt")
            },
            fact("the box holds the card that was picked") { state().term == "Lightning Bolt" },
            fact("the pick says how many are owned and whose") {
                wordsUnder("tweak-pick").contains("4 owned · matt")
            },
        )
    }

    @Test
    fun aCardNobodyOwnsIsPickableAndSaysItWouldBeBought() {
        sheet(adding())
        type("lig")
        push(state().searched(emptyList(), listOf("Lightning Helix")))
        rule.onNodeWithTag("tweak-hit-0").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("an unowned card could be picked") { state().pick?.name == "Lightning Helix" },
            fact("the sheet warns that the card has to be bought") {
                says("not owned — would be bought")
            },
            fact("an unowned card is good enough to preview") { state().ready },
            fact("and the Preview button is live") { pressableText("Preview →") },
        )
    }

    @Test
    fun pickingADifferentCardAfterwardsReplacesTheFirst() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt)))
        rule.onNodeWithTag("tweak-hit-0").performClick()
        rule.waitForIdle()

        type("sol")
        push(state().searched(listOf(found("Sol Ring", 1, "matt", "Artifact", 5))))
        assertTrue(present("tweak-hit-0"), "the second search had nowhere to show itself")
        rule.onNodeWithTag("tweak-hit-0").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the second pick took") { state().pick?.name == "Sol Ring" },
            fact("only one pick is on screen") { howMany("Lightning Bolt") == 0 },
            fact("and it is the second one") { wordsUnder("tweak-pick").contains("Sol Ring") },
        )
    }

    @Test
    fun clearingTheBoxAfterAPickClearsThePick() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt)))
        rule.onNodeWithTag("tweak-hit-0").performClick()
        rule.waitForIdle()
        assertNotNull(state().pick)

        type("")
        rule.waitForIdle()
        Parity.check(
            fact("the pick did not survive the box being emptied") { state().pick == null },
            fact("the chosen card is off the screen") { !present("tweak-pick") },
            fact("an empty box lists no hits") { !present("tweak-hit-0") },
            fact("an empty finder cannot be previewed") { !pressableText("Preview →") },
        )
    }

    @Test
    fun backingOffBelowTheMinimumClearsTheStaleHits() {
        // Hits for "lig" are not answers to "l". Leaving them up means
        // tapping a card the box no longer names.
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix")))
        assertEquals(2, hitRows().size)

        type("l")
        rule.waitForIdle()
        assertFalse(present("tweak-hit-0"), "hits from a longer term stayed up")
    }

    @Test
    fun everyHitIsAThumbSizedTarget() {
        sheet(adding())
        type("lig")
        push(state().searched(listOf(bolt), listOf("Lightning Helix", "Lightning Strike")))
        listOf(0, 1, 2).forEach {
            assertTrue(
                height("tweak-hit-$it") >= 44f,
                "hit $it is ${height("tweak-hit-$it")}dp tall, under the 44 a thumb needs",
            )
        }
    }

    @Test
    fun aHitDoesNotRunOffAPhone() {
        val long = found(
            "Hanweir, the Writhing Township",
            qty = 1,
            owner = "matt",
            type = "Legendary Creature — Eldrazi Horror Mutant",
            id = 11,
        )
        sheet(adding())
        type("han")
        push(state().searched(listOf(long), listOf("Hanweir Battlements")))
        val edgeDp = rule.onNodeWithTag("tweak-sheet").getUnclippedBoundsInRoot().right.value + 1f
        listOf(0, 1).forEach { i ->
            val row = rule.onNodeWithTag("tweak-hit-$i").getUnclippedBoundsInRoot()
            assertTrue(row.right.value <= edgeDp, "hit $i runs to ${row.right.value}, past $edgeDp")
        }
        // And nothing written on the row does either.
        val edge = rightEdgePx()
        nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).forEach {
            assertTrue(
                it.boundsInRoot.right <= edge,
                "something on the row runs to ${it.boundsInRoot.right}, past $edge",
            )
        }
    }

    @Test
    fun theBoxSaysWhatItIsForWhenAddingACard() {
        sheet(adding())
        assertTrue(says("Card name"), "the add box does not say what to type")
    }

    @Test
    fun andSaysSomethingElseWhenSwappingOneIn() {
        sheet(swapping())
        Parity.check(
            fact("the swap box says what to type") { says("Swap in…") },
            fact("a swap with no card named is not ready") { !state().ready },
            fact("and it says what is missing") { says("Find the card first.") },
            fact("the card going out is marked as such, in words") { says("going out") },
        )
    }

    // ================================================== 3. the counter

    @Test
    fun theNumberShownIsTheNumberInState() {
        sheet(counting(7))
        Parity.check(
            fact("there is a counter on the sheet") { present("tweak-counter") },
            fact("it shows the number the state holds") { shown() == "7" },
            fact("and the state holds it") { state().qty == 7 },
        )
        rule.onNodeWithTag("tweak-scrim").shoot("tweak-3-counter")
    }

    @Test
    fun plusAddsOneAndTheBoxFollows() {
        sheet(counting(3))
        rule.onNodeWithTag("tweak-plus").performClick()
        rule.waitForIdle()
        assertEquals(4, state().qty, "the plus did not count")
        assertEquals("4", shown(), "the state moved and the box did not")
    }

    @Test
    fun minusTakesOneAndTheBoxFollows() {
        sheet(counting(3))
        rule.onNodeWithTag("tweak-minus").performClick()
        rule.waitForIdle()
        assertEquals(2, state().qty, "the minus did not count")
        assertEquals("2", shown())
    }

    @Test
    fun pressingOneOfThemFiveTimesIsFiveCards() {
        sheet(counting(3))
        repeat(5) {
            rule.onNodeWithTag("tweak-plus").performClick()
            rule.waitForIdle()
        }
        assertEquals(8, state().qty)
        assertEquals("8", shown())
    }

    @Test
    fun threeTapsInsideOneFrameAreThreeCards() {
        // Compose recomposes on a frame, so every handler installed at
        // the last one holds the same state. Stepping off that state
        // loses every tap but the first — which is exactly what a fast
        // thumb produces. The clock is held still to make the frame
        // last long enough to tap into three times.
        sheet(counting(3))
        rule.mainClock.autoAdvance = false
        repeat(3) { rule.onNodeWithTag("tweak-plus").performClick() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(6, state().qty, "taps inside one frame collapsed into one")
        assertEquals("6", shown())
    }

    @Test
    fun aCountCanGoToNoughtBecauseThatTakesTheCardOut() {
        sheet(counting(1))
        assertTrue(pressableTag("tweak-minus"), "nought is a real answer for a count and the minus was shut")
        rule.onNodeWithTag("tweak-minus").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the number went to nought") { state().qty == 0 },
            fact("the box shows nought") { shown() == "0" },
            fact("the sheet says what nought means") { summary().endsWith("→ 0") },
        )
    }

    @Test
    fun theMinusIsShutAtTheFloorRatherThanLiveAndDoingNothing() {
        sheet(counting(0))
        assertFalse(pressableTag("tweak-minus"), "the minus was live at nought")
        rule.onNodeWithTag("tweak-minus").performClick()
        rule.waitForIdle()
        assertEquals(0, state().qty, "it went below nought")
        assertEquals("0", shown())
    }

    @Test
    fun addingNoughtCopiesIsNotOffered() {
        // An add of nought is not a change, so the sheet must not park
        // the number there under a Preview button that will not press.
        sheet(adding().picked(bolt))
        Parity.check(
            fact("an add starts at one") { shown() == "1" },
            fact("the floor for an add is one, so the minus is shut") { !pressableTag("tweak-minus") },
            fact("the state agrees about the floor") { state().floor == 1 },
        )
        rule.onNodeWithTag("tweak-minus").performClick()
        rule.waitForIdle()
        assertEquals(1, state().qty, "an add was talked down to nought copies")
    }

    @Test
    fun thePlusIsShutAtTheCeiling() {
        sheet(counting(3).count(DeckTweak.MAX_QTY))
        Parity.check(
            fact("the box is at the ceiling") { shown() == "${DeckTweak.MAX_QTY}" },
            fact("the plus is shut there") { !pressableTag("tweak-plus") },
            fact("the other end did not go off with it") { pressableTag("tweak-minus") },
        )
        rule.onNodeWithTag("tweak-plus").performClick()
        rule.waitForIdle()
        assertEquals(DeckTweak.MAX_QTY, state().qty, "it went past the ceiling")
    }

    @Test
    fun aTypedNumberPastTheCeilingComesBackClamped() {
        // The box takes typing, and a slipped thumb that plans a
        // 9999-card purchase is worse than one that cannot type it.
        sheet(counting(3))
        typeCount("9999")
        rule.waitForIdle()
        assertEquals(DeckTweak.MAX_QTY, state().qty)
        assertEquals("${DeckTweak.MAX_QTY}", shown(), "the box kept a number the state refused")
    }

    @Test
    fun aTypedMinusNumberDoesNotGetThrough() {
        sheet(counting(3))
        typeCount("-4")
        rule.waitForIdle()
        assertEquals(0, state().qty)
        assertEquals("0", shown())
    }

    @Test
    fun backspacingTheBoxEmptyDoesNotEmptyTheDeck() {
        // An empty box used to read as nought, and nought on a count
        // takes the card out of the deck. Deleting a card is not what
        // "I am about to type a different number" means.
        sheet(counting(4))
        typeCount("")
        rule.waitForIdle()
        assertEquals(4, state().qty, "clearing the box took the card out of the deck")
        assertEquals("4", shown())
    }

    @Test
    fun typingSomethingThatIsNotANumberLeavesTheNumberAlone() {
        sheet(counting(4))
        typeCount("lots")
        rule.waitForIdle()
        assertEquals(4, state().qty)
        assertEquals("4", shown(), "the box was left holding something that is not the number")
    }

    @Test
    fun changingTheNumberThrowsAwayThePlan() {
        // The plan priced one number. Keeping it on screen next to a
        // different number means the Apply button writes a change
        // nobody was shown.
        sheet(counting(10).count(7).planned(plan()))
        assertTrue(present("tweak-plan"), "the plan was never on screen, so this proves nothing")
        rule.onNodeWithTag("tweak-plus").performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the plan for 7 is not still showing at 8") { !present("tweak-plan") },
            fact("the state dropped it too") { state().plan == null },
            fact("it offers to preview again rather than to apply") { says("Preview →") },
            fact("and no longer offers to apply") { !says("Set it") },
        )
    }

    @Test
    fun aNumberThatDidNotMoveKeepsThePlan() {
        // Typing 999 at the ceiling leaves the number exactly where the
        // plan found it, so the plan still describes the change.
        sheet(counting(10).count(DeckTweak.MAX_QTY).planned(plan()))
        assertTrue(present("tweak-plan"))
        typeCount("999")
        rule.waitForIdle()
        Parity.check(
            fact("a number that did not change kept the plan") { present("tweak-plan") },
            fact("the number is still the ceiling") { state().qty == DeckTweak.MAX_QTY },
            fact("and the box shows it") { shown() == "${DeckTweak.MAX_QTY}" },
        )
    }

    @Test
    fun bothCounterButtonsSayWhatTheyDo() {
        sheet(counting(3))
        Parity.check(
            fact("the minus is more than a glyph") { !named("tweak-minus").isNullOrBlank() },
            fact("the plus is more than a glyph") { !named("tweak-plus").isNullOrBlank() },
            fact("they do not read the same out loud") { named("tweak-minus") != named("tweak-plus") },
            fact("the minus is named the way the website names it") { named("tweak-minus") == "One fewer" },
            fact("the plus is named the way the website names it") { named("tweak-plus") == "One more" },
        )
    }

    @Test
    fun theCounterIsMinusThenTheNumberThenPlus() {
        // The website's order, left to right. Words matching while the
        // layout does not is still a gap: a plus on the left of the
        // number is a different control.
        sheet(counting(3))
        val minus = rule.onNodeWithTag("tweak-minus").getUnclippedBoundsInRoot()
        val box = rule.onNodeWithTag("tweak-qty").getUnclippedBoundsInRoot()
        val plus = rule.onNodeWithTag("tweak-plus").getUnclippedBoundsInRoot()
        Parity.check(
            fact("the minus is left of the number") { minus.right.value <= box.left.value + 1 },
            fact("the plus is right of the number") { box.right.value <= plus.left.value + 1 },
            fact("all three sit on one line") {
                kotlin.math.abs(minus.top.value - plus.top.value) < 2
            },
            fact("the number can be typed into") {
                rule.onNodeWithTag("tweak-qty").fetchSemanticsNode()
                    .config.contains(SemanticsActions.SetText)
            },
        )
    }

    @Test
    fun thereIsNoCounterWhenTheCardIsLeaving() {
        // Removing takes the whole row, so a number on screen would be
        // one the change ignores.
        sheet(removing())
        assertFalse(present("tweak-counter"), "a removal showed a number it does not use")
    }

    @Test
    fun bothCounterButtonsAreThumbSized() {
        sheet(counting(3))
        listOf("tweak-minus", "tweak-plus").forEach {
            assertTrue(height(it) >= 44f, "$it is only ${height(it)}dp tall")
            assertTrue(width(it) >= 44f, "$it is only ${width(it)}dp wide")
        }
    }

    @Test
    fun aShutButtonIsNotOnlyADifferentColour() {
        Parity.needsRealRendering()
        // Whoever owns this cannot tell two hues apart, so "off" has to
        // be legible as something other than a hue. Measured off the
        // pixels, because a colour constant in the source says nothing
        // about what reached the screen.
        sheet(counting(0))
        val off = lightness("tweak-minus")
        val on = lightness("tweak-plus")
        assertTrue(
            on - off >= 0.01,
            "the shut button is the same weight as the live one: $off against $on",
        )
        assertFalse(pressableTag("tweak-minus"), "and it is not even marked shut")
        assertTrue(pressableTag("tweak-plus"), "the live one is marked shut too")
    }

    // ============================================ 4. preview and apply

    @Test
    fun theSummaryIsOneLineSayingWhatWouldHappenToACount() {
        sheet(counting(3).count(5))
        assertEquals("Swamp: 3 → 5", summary(), "the count's one-line summary is not the website's")
    }

    @Test
    fun andForASwapItNamesBothCards() {
        sheet(swapping().picked(bolt))
        assertEquals("Sol Ring → Lightning Bolt", summary(), "the swap's summary is not the website's")
    }

    @Test
    fun andForAnAddItSaysHowMany() {
        sheet(adding().picked(bolt).count(2))
        assertEquals("Add 2× Lightning Bolt", summary(), "the add's summary is not the website's")
    }

    @Test
    fun andForARemovalItSaysHowManyAreGoing() {
        sheet(removing())
        assertEquals("Remove 2× Sol Ring", summary(), "the removal's summary is not the website's")
    }

    @Test
    fun beforeAPlanTheOnlyThingOfferedIsAPreview() {
        sheet(counting(3).count(5))
        Parity.check(
            fact("it offers a preview") { says("Preview →") },
            fact("the preview is live") { pressableText("Preview →") },
            fact("it does not offer to apply") { !says("Set it") },
            fact("and there is no way back from a plan that does not exist") { !says("← Change it") },
            fact("no plan figures are on screen") { !present("tweak-plan") },
        )
        rule.onNodeWithText("Preview →").performClick()
        rule.waitForIdle()
        assertEquals(1, previewAsked, "the preview button did not ask for one")
    }

    @Test
    fun aChangeThatIsNoChangeCannotBePreviewed() {
        // The count is where the deck already is, so there is nothing
        // to price.
        sheet(counting(3))
        Parity.check(
            fact("the state says it is not ready") { !state().ready },
            fact("and the button is refused rather than missing") {
                says("Preview →") && !pressableText("Preview →")
            },
        )
    }

    @Test
    fun aPlanShowsTheWebsitesThreeFigures() {
        sheet(swapping().picked(bolt).planned(plan()))
        rule.onNodeWithTag("tweak-scrim").shoot("tweak-4-preview")
        Parity.check(
            fact("the plan is on screen") { present("tweak-plan") },
            fact("it says how many cards the deck would have after") {
                wordsUnder("tweak-plan").contains("99") && says("cards after")
            },
            fact("it says how many come in") { says("in") },
            fact("it says how many go out") { says("out") },
        )
    }

    @Test
    fun aPlanSaysWhatWouldBeBoughtInWords() {
        sheet(swapping().picked(bolt).planned(plan()))
        Parity.check(
            fact("it says how many would be bought, and which") {
                says("1 to buy: Lightning Bolt")
            },
            fact("it says what goes back to bulk") { says("back to bulk: 1× Sol Ring") },
        )
    }

    @Test
    fun withAPlanTheOnlyThingOfferedIsApplyingIt() {
        Parity.needsRealRendering()
        sheet(swapping().picked(bolt).planned(plan()))
        Parity.check(
            fact("the button is named by what it does") { says("${Tweak.SWAP.verb} it") },
            fact("it is live") { pressableText("Swap it") },
            fact("the preview is no longer offered") { !says("Preview →") },
            fact("there is a way back to change it") { says("← Change it") },
        )
        rule.onNodeWithText("Swap it").performClick()
        rule.waitForIdle()
        assertEquals(1, applyAsked, "the apply button did not apply")
    }

    @Test
    fun theWayBackDropsThePlanAndOffersAPreviewAgain() {
        sheet(counting(3).count(5).planned(plan()))
        // Scrolled to first, the way a thumb reaches it. A counter and
        // a plan together are taller than the sheet's cap on a small
        // screen, so the foot is past the fold — and a press aimed at
        // where an unscrolled node *would* be lands on the scrim
        // outside the sheet and dismisses it, which is not what this
        // test is about.
        rule.onNodeWithText("← Change it").performScrollTo().performClick()
        rule.waitForIdle()
        Parity.check(
            fact("the plan is gone from the state") { state().plan == null },
            fact("and off the screen") { !present("tweak-plan") },
            fact("a preview is offered again") { says("Preview →") },
            fact("nothing was applied") { applyAsked == 0 },
        )
    }

    @Test
    fun nothingIsOfferedTwiceWhileItIsBeingWritten() {
        sheet(swapping().picked(bolt).planned(plan()).working())
        Parity.check(
            fact("the button says it is working") { says("Working…") },
            fact("and cannot be pressed again") { !pressableText("Working…") },
            fact("the state will not apply twice") { !state().canApply },
            fact("the finished label is not also on screen") { !says("Swap it") },
        )
        rule.onNodeWithText("Working…").performClick()
        rule.waitForIdle()
        assertEquals(0, applyAsked, "a busy sheet applied the change again")
    }

    @Test
    fun andNoPreviewIsOfferedTwiceEither() {
        sheet(counting(3).count(5).working())
        Parity.check(
            fact("the preview button says it is working") { says("Working…") },
            fact("and cannot be pressed again") { !pressableText("Working…") },
            fact("the state is not ready while it is working") { !state().ready },
        )
        rule.onNodeWithText("Working…").performClick()
        rule.waitForIdle()
        assertEquals(0, previewAsked, "a busy sheet asked for a second plan")
    }

    @Test
    fun aFailureIsSaidOutLoudAndThePlanIsDropped() {
        sheet(counting(3).count(5).planned(plan()).failed("that did not work", listOf("line 4 is not a card")))
        Parity.check(
            fact("the message is on screen") { says("that did not work") },
            fact("so is every line of it") { says("line 4 is not a card") },
            fact("the plan is gone") { !present("tweak-plan") },
            fact("and it offers to try again") { says("Preview →") },
        )
    }

    @Test
    fun theDoneStateSaysWhatHappenedAndOffersTheWayBack() {
        sheet(swapping().picked(bolt).planned(plan()).finished())
        rule.onNodeWithTag("tweak-scrim").shoot("tweak-5-done")
        Parity.check(
            fact("it says the change is done, and which one") {
                summary() == "Done — Sol Ring → Lightning Bolt"
            },
            fact("the way back to the deck is offered") { says("Back to the deck") },
            fact("nothing is offered to apply again") { !says("Swap it") },
            fact("and no counter is left on screen") { !present("tweak-counter") },
        )
        rule.onNodeWithText("Back to the deck").performClick()
        rule.waitForIdle()
        assertFalse(up, "the done state would not let go of the sheet")
    }

    @Test
    fun theDoneStateIsNotTheChoosingScreenEvenWithNoKind() {
        // `saved` wins over `choosing`, the same as the web's `when`.
        // Getting that order wrong puts the three options back up after
        // the change has already been written.
        sheet(choosing().finished())
        Parity.check(
            fact("the choices are not back up") { !present("tweak-opt-0") },
            fact("it says it is done") { says("Back to the deck") },
        )
    }

    @Test
    fun nothingOnTheSheetRunsOffAPhone() {
        sheet(swapping().picked(bolt).planned(plan()))
        val edge = rightEdgePx()
        val over = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .filter { it.boundsInRoot.right > edge }
        assertTrue(
            over.isEmpty(),
            "${over.size} things run off the right edge of a 400dp phone, " +
                "furthest to ${over.maxOfOrNull { it.boundsInRoot.right }}",
        )
    }
}
