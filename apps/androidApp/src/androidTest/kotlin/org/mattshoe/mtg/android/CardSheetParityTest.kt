package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckUse
import org.mattshoe.mtg.core.Legality
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Ruling
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card sheet, proved against the website's card page.
 *
 * Every sentence asserted here is one the web suite already asserts
 * about `CardPage` — `CardLegalitiesTest`, `CardRulingsTest` and
 * `CardPageLayoutTest`. Reading the two files and concluding they look
 * similar is how the phone kept its own rulings loop and ended up
 * disagreeing with the browser about the same card, so these render
 * the real composable and read what is on it.
 *
 * Nothing here touches the network. The sheet is handed a
 * `CardDetail`, which is the whole reason the state lives in `:core`.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CardSheetParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The width the sheet is mounted at. A phone, because that is the device. */
    private val phone = 340

    /**
     * The sheet, mounted once.
     *
     * `setContent` may only be called once per test, so a test that
     * wants a second card is a second test.
     */
    private fun open(card: CardDetail, width: Int = phone) = mount(width) { CardSheet(card) {} }

    private fun mount(width: Int = phone, body: @Composable () -> Unit) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Box(Modifier.width(width.dp)) { body() } }
            }
        }
        rule.waitForIdle()
    }

    /** How many nodes carry this text. Exactly, or anywhere inside. */
    private fun count(text: String, anywhere: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = anywhere)).fetchSemanticsNodes().size

    private fun seen(text: String) = count(text) > 0
    private fun inside(text: String) = count(text, anywhere = true)

    /** Where a line sits down the page, for asserting reading order. */
    private fun topOf(text: String) =
        rule.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    // ------------------------------------------------------------ cards

    private fun card(vararg l: Pair<String, String>) =
        CardDetail(name = "Sol Ring", legalities = l.map { (f, s) -> Legality(f, s) })

    /** A card with one of every legality on it. The web's `mixed()`. */
    private fun mixed() = card(
        "standard" to "not_legal",
        "vintage" to "restricted",
        "commander" to "legal",
        "legacy" to "banned",
    )

    /** Two people, one card, the numbers that differ between them. */
    private fun shared() = CardDetail(
        name = "Sol Ring",
        nameNorm = "sol ring",
        printings = listOf(
            Printing(1, "lcc", "The Lost Caverns of Ixalan Commander", "4", "nonfoil", 10, null, owner = "kayla"),
            Printing(2, "m3c", "Modern Horizons 3", "409", "nonfoil", 24, null, owner = "matt"),
        ),
        usedIn = listOf(
            DeckUse("a", "Alela", "kayla", 5, null, false),
            DeckUse("b", "Bello", "matt", 24, null, false),
        ),
    )

    /** Two printings: one with a listing, one nobody sells. */
    private fun shoppable() = CardDetail(
        name = "Anointed Procession",
        printings = listOf(
            Printing(
                id = 1, setCode = "lcc", setName = "The Lost Caverns of Ixalan Commander",
                collectorNumber = "124", finish = "nonfoil", qty = 3,
                scryfallId = "abcdef12-3456-7890-abcd-ef1234567890",
                price = 5.36, tcgplayer = "https://tcg.example/anointed",
            ),
            Printing(
                id = 2, setCode = "sld", setName = "Artist Series", collectorNumber = "17",
                finish = "foil", qty = 1,
                scryfallId = "bbcdef12-3456-7890-abcd-ef1234567890",
            ),
        ),
    )

    /** One card with everything the page can say about a card on it. */
    private fun everything() = CardDetail(
        name = "Sol Ring",
        nameNorm = "sol ring",
        printings = listOf(
            Printing(
                id = 1, setCode = "m3c", setName = "Modern Horizons 3", collectorNumber = "409",
                finish = "nonfoil", qty = 3,
                scryfallId = "abcdef12-3456-7890-abcd-ef1234567890",
                owner = "matt", price = 1.5, tcgplayer = "https://tcg.example/sol",
            ),
        ),
        usedIn = listOf(DeckUse("alela", "Alela", "matt", 1, "ramp", false)),
        legalities = mixed().legalities,
        rulings = listOf(
            Ruling("2004-10-04", "It is a mana ability."),
            Ruling("2018-12-07", "It checks the battlefield."),
        ),
    )

    private fun deckCard(name: String) = DeckCard(name = name, qty = 1, role = null, owned = 1)

    private val sections = listOf("Who owns it", "Printings", "Legal in", "In decks", "Rulings")

    // ------------------------------------------------- legality chips

    @Test
    fun theLegalityRowDrawsOneChipPerFormatInTheOrderPeopleAskAbout() {
        open(mixed())
        val chips = mixed().legalityChips
        Parity.check(
            Parity.Fact("one chip per format, and four formats make four chips") {
                chips.size == 4
            },
            Parity.Fact("the chips are ordered commander, legacy, vintage, standard") {
                chips.map { it.formatLabel } ==
                    listOf("Commander", "Legacy", "Vintage", "Standard")
            },
            Parity.Fact("every one of those chips is on the screen as written") {
                chips.all { seen(it.chip) }
            },
            Parity.Fact("the chips read down the page in that same order") {
                val tops = chips.map { topOf(it.chip) }
                tops == tops.sorted()
            },
        )
        rule.onNodeWithText("✓ Commander legal").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("✕ Legacy banned").assertExists()
    }

    @Test
    fun aBannedChipIsTellableFromALegalOneWithTheColourThrownAway() {
        // The owner of this collection is colourblind. Strip every chip
        // down to the characters in it: if banned and legal still
        // differ, colour was never the only signal.
        open(mixed())
        val chips = mixed().legalityChips.associateBy { it.formatLabel }
        val legal = chips.getValue("Commander").chip
        val banned = chips.getValue("Legacy").chip
        val restricted = chips.getValue("Vintage").chip
        val absent = chips.getValue("Standard").chip
        Parity.check(
            Parity.Fact("legal and banned do not read the same") { legal != banned },
            Parity.Fact("legal and banned open with different marks") {
                legal.first() != banned.first()
            },
            Parity.Fact("all four statuses open with four different marks") {
                listOf(legal, banned, restricted, absent).map { it.first() }.distinct().size == 4
            },
            Parity.Fact("every chip spells its status out in words") {
                "legal" in legal && "banned" in banned &&
                    "restricted" in restricted && "not legal" in absent
            },
            Parity.Fact("a format the card was never in is not called banned") {
                "banned" !in absent
            },
            Parity.Fact("all four chips reached the screen") {
                listOf(legal, banned, restricted, absent).all { seen(it) }
            },
        )
    }

    @Test
    fun aCardWithNoLegalityDataSaysNothingRecordedAndDrawsNoChips() {
        open(CardDetail(name = "Sol Ring"))
        Parity.check(
            Parity.Fact("the heading is there even with no legality under it") { seen("Legal in") },
            Parity.Fact("it says nothing is recorded") { seen("Nothing recorded.") },
            Parity.Fact("no chip is drawn for a card with no legality data") {
                inside("Commander") == 0
            },
            Parity.Fact("a card with no data is not told it is legal nowhere") {
                !seen("Legal nowhere.")
            },
        )
    }

    @Test
    fun aCardLegalNowhereSaysSoUnderTheHeading() {
        // The heading says "Legal in". When the answer is nowhere, that
        // has to be written down, not counted off the chips.
        val nowhere = card("commander" to "banned", "standard" to "not_legal")
        open(nowhere)
        Parity.check(
            Parity.Fact("the sheet says the card is legal nowhere") { seen("Legal nowhere.") },
            Parity.Fact("the chips did not vanish with the legality") {
                nowhere.legalityChips.size == 2 && nowhere.legalityChips.all { seen(it.chip) }
            },
        )
        rule.onNodeWithText("Legal nowhere.").performScrollTo().assertIsDisplayed()
        rule.onRoot().shoot("card_sheet_legal_nowhere")
    }

    @Test
    fun aCardLegalSomewhereDoesNotClaimItIsLegalNowhere() {
        open(mixed())
        Parity.check(
            Parity.Fact("a Commander-legal card is not told it is legal nowhere") {
                !seen("Legal nowhere.")
            },
        )
    }

    @Test
    fun theSameFormatTwiceOnlyDrawsOneChip() {
        val twice = card("commander" to "legal", "commander" to "banned")
        open(twice)
        Parity.check(
            Parity.Fact("the legality row does not repeat itself") {
                twice.legalityChips.size == 1
            },
            Parity.Fact("the chip kept is the first status recorded") {
                twice.legalityChips.single().chip == "✓ Commander legal"
            },
            Parity.Fact("exactly one Commander chip is on the screen") {
                seen("✓ Commander legal") && !seen("✕ Commander banned")
            },
        )
    }

    @Test
    fun aFormatNobodyHasHeardOfStillGetsAChip() {
        val odd = card("commander" to "legal", "timeless" to "not_legal")
        open(odd)
        Parity.check(
            Parity.Fact("an unknown format is kept, after the ones people ask about") {
                odd.legalityChips.map { it.formatLabel } == listOf("Commander", "Timeless")
            },
            Parity.Fact("the unknown format reaches the screen spelt out") {
                seen("○ Timeless not legal")
            },
        )
    }

    @Test
    fun theChipRowStaysInsideAPhone() {
        val many = card(
            "commander" to "legal", "modern" to "legal", "legacy" to "banned",
            "vintage" to "restricted", "standard" to "not_legal", "pauper" to "not_legal",
            "paupercommander" to "not_legal", "oathbreaker" to "legal",
        )
        open(many)
        // The sheet is mounted at a phone's width, so anything past it
        // is off the side of the screen.
        val limit = phone + 1f
        val over = many.legalityChips.map { it.chip }.filter {
            rule.onNodeWithText(it).getUnclippedBoundsInRoot().right.value > limit
        }
        assertTrue(over.isEmpty(), "chips hanging off a ${phone}dp phone: $over")
        assertEquals(8, many.legalityChips.size, "a format was dropped on the way to the row")
    }

    // ------------------------------------------------------- rulings

    @Test
    fun theSectionHasAHeadingAPersonCanFindEvenWithNoRulingsUnderIt() {
        // Dropping the section leaves "no rulings exist" looking
        // identical to "the rulings did not load", which is worse.
        open(shared())
        Parity.check(
            Parity.Fact("the heading survives a card with no rulings") { seen("Rulings") },
            Parity.Fact("the sheet says there are none") { seen("No rulings.") },
        )
        rule.onNodeWithText("No rulings.").performScrollTo().assertIsDisplayed()
        rule.onRoot().shoot("card_sheet_no_rulings")
    }

    @Test
    fun aRulingThatIsAllWhitespaceCountsAsNoRulingAtAll() {
        open(shared().copy(rulings = listOf(Ruling("2018-12-07", "   "))))
        Parity.check(
            Parity.Fact("a blank ruling is not drawn as a line") { inside("2018-12-07") == 0 },
            Parity.Fact("a card whose only ruling is blank is told it has none") {
                seen("No rulings.")
            },
        )
    }

    @Test
    fun theyReadOldestFirstWithTheUndatedLastWhateverOrderTheServerSentThem() {
        val card = shared().copy(
            rulings = listOf(
                Ruling("", "Undated."),
                Ruling("2019-05-03", "Third."),
                Ruling("2004-10-04", "First."),
                Ruling("2018-12-07", "Second."),
            ),
        )
        open(card)
        val lines = card.rulingsShown
        val written = listOf(
            "2004-10-04  First.", "2018-12-07  Second.",
            "2019-05-03  Third.", "Undated.",
        )
        Parity.check(
            Parity.Fact("four rulings come out as four lines") { lines.size == 4 },
            Parity.Fact("they are ordered oldest first, undated last") {
                lines.map { it.body } == listOf("First.", "Second.", "Third.", "Undated.")
            },
            Parity.Fact("each dated ruling carries its day in front of it") {
                written.all { seen(it) }
            },
            Parity.Fact("an undated ruling is not given a date") { inside("  Undated.") == 0 },
            Parity.Fact("the lines sit down the page in reading order") {
                val tops = written.map { topOf(it) }
                tops == tops.sorted()
            },
        )
    }

    @Test
    fun theDayIsAPlainDateAndNotAWholeTimestamp() {
        open(shared().copy(rulings = listOf(Ruling("2018-12-07T00:00:00.000Z", "Timestamped."))))
        Parity.check(
            Parity.Fact("the timestamp never reaches the screen") { inside("T00:00:00") == 0 },
            Parity.Fact("the day is trimmed to a date") { seen("2018-12-07  Timestamped.") },
        )
    }

    @Test
    fun aRulingWithAnUnreadableDateShowsItsWordsAndNothingElse() {
        open(shared().copy(rulings = listOf(Ruling("not a date", "Still worth reading."))))
        Parity.check(
            Parity.Fact("an unreadable date is not printed anyway") { inside("not a date") == 0 },
            Parity.Fact("the ruling itself survives its bad date") {
                seen("Still worth reading.")
            },
        )
    }

    @Test
    fun theSameRulingTwiceAppearsOnce() {
        val twice = shared().copy(
            rulings = listOf(
                Ruling("2018-12-07", "It checks the battlefield."),
                Ruling("2018-12-07", "It checks the battlefield."),
            ),
        )
        open(twice)
        assertEquals(1, twice.rulingsShown.size, "the same ruling was kept twice")
        assertEquals(
            1,
            inside("It checks the battlefield."),
            "the same ruling reached the screen twice",
        )
    }

    // --------------------------------------------- who owns it, where

    @Test
    fun theSheetSaysWhoOwnsHowManyMostCopiesFirst() {
        // It used to be one person's page, so the other half of the
        // collection was simply not there.
        open(shared())
        Parity.check(
            Parity.Fact("the section names itself") { seen("Who owns it") },
            Parity.Fact("matt has 24 and none of them spare") {
                seen("matt · 24 owned · 0 free")
            },
            Parity.Fact("kayla has 10 and five of them spare") {
                seen("kayla · 10 owned · 5 free")
            },
            Parity.Fact("the owner with the most copies comes first") {
                topOf("matt · 24 owned · 0 free") < topOf("kayla · 10 owned · 5 free")
            },
        )
    }

    @Test
    fun anOwnerWhoHasNoneOfItButWantsItStillGetsALine() {
        // Nought owned against two wanted is the most useful thing the
        // page can tell you.
        open(
            CardDetail(
                name = "Sol Ring",
                usedIn = listOf(DeckUse("a", "Alela", "kayla", 2, null, false)),
            ),
        )
        Parity.check(
            Parity.Fact("an owner with no copies is still listed, and said to be short") {
                seen("kayla · 0 owned · 0 free · 2 short")
            },
        )
    }

    @Test
    fun aPrintingSaysWhoseCopyItIsAndADeckSaysWhoseDeckItIs() {
        open(shared())
        Parity.check(
            Parity.Fact("kayla's printing names her") {
                inside("LCC · 4 · The Lost Caverns of Ixalan Commander · kayla · 10×") == 1
            },
            Parity.Fact("matt's printing names him") {
                inside("M3C · 409 · Modern Horizons 3 · matt · 24×") == 1
            },
            Parity.Fact("both decks that want it are listed") {
                inside("Alela") >= 1 && inside("Bello") >= 1
            },
            // The owner is its own tag beside the row, the way the web
            // page puts it in its own span.
            Parity.Fact("each name appears on an owner line, a printing and a deck row") {
                inside("kayla") >= 3 && inside("matt") >= 3
            },
        )
    }

    @Test
    fun aPrintingYouCanBuyNamesTheShopAndOneNobodySellsDoesNot() {
        open(shoppable(), width = 360)
        Parity.check(
            Parity.Fact("the row that can be bought names the shop") { seen("TCGplayer ↗") },
            Parity.Fact("a printing nobody sells does not offer a shop") {
                inside("TCGplayer") == 1
            },
            Parity.Fact("the price quoted is the one for the finish this copy is in") {
                seen("$5.36")
            },
            // Nothing known is a dash, never a zero — a card is not free.
            Parity.Fact("an unpriced printing reads as a dash") { seen("—") },
            Parity.Fact("the foil printing says it is a foil") { inside("foil · 1×") == 1 },
        )
    }

    @Test
    fun aCardNobodyOwnsSaysSoRatherThanShowingAnEmptyList() {
        open(CardDetail(name = "Sol Ring", nameNorm = "sol ring"))
        Parity.check(
            Parity.Fact("the printings section names itself") { seen("Printings") },
            Parity.Fact("it says nobody owns one") { seen("Nobody owns one.") },
            Parity.Fact("it says the card is in no deck") { seen("Not in a deck.") },
            Parity.Fact("a card nobody owns gets no owner section at all") {
                !seen("Who owns it")
            },
            Parity.Fact("nought owned and nought free are both stated") {
                seen("0 owned") && seen("0 free")
            },
        )
        rule.onRoot().shoot("card_sheet_nobody_owns")
    }

    @Test
    fun moreDecksThanCopiesIsSaidOutLoudInTheWebsWords() {
        open(
            CardDetail(
                name = "Sol Ring",
                printings = listOf(
                    Printing(1, "m3c", "Modern Horizons 3", "409", "nonfoil", 1, null, owner = "matt"),
                ),
                usedIn = listOf(
                    DeckUse("a", "Alela", "matt", 1, null, false),
                    DeckUse("b", "Bello", "matt", 2, null, false),
                ),
            ),
        )
        Parity.check(
            Parity.Fact("the overcommitment is stated as the web states it") {
                seen("3 committed")
            },
            Parity.Fact("the owner line says how short he is") {
                seen("matt · 1 owned · 0 free · 2 short")
            },
        )
    }

    @Test
    fun aProxyDoesNotEatACopyOrCountAsCommitted() {
        open(
            CardDetail(
                name = "Sol Ring",
                printings = listOf(Printing(1, "m3c", null, "409", "nonfoil", 1, null, owner = "matt")),
                usedIn = listOf(DeckUse("p", "Proxy deck", "matt", 1, null, true)),
            ),
        )
        Parity.check(
            Parity.Fact("the copy is still spare") { seen("1 free") },
            Parity.Fact("a proxy is not counted as a committed copy") {
                inside("committed") == 0
            },
            Parity.Fact("the deck row says it is a proxy") { seen("Proxy deck · 1× · proxy") },
        )
    }

    // ------------------------------------------------- the card itself

    @Test
    fun theSheetShowsTheScanAndEverySectionTheWebPageHasInItsOrder() {
        open(everything())
        Parity.check(
            Parity.Fact("the card itself is on its own page") {
                rule.onAllNodes(hasContentDescription("Sol Ring")).fetchSemanticsNodes().isNotEmpty()
            },
            Parity.Fact("every section the web page has is here") { sections.all { seen(it) } },
            Parity.Fact("the sections are in the website's order") {
                val tops = sections.map { topOf(it) }
                tops == tops.sorted()
            },
            Parity.Fact("the deck row keeps its quantity and its role") {
                seen("Alela · 1× · ramp")
            },
            Parity.Fact("the rulings under it are oldest first") {
                topOf("2004-10-04  It is a mana ability.") <
                    topOf("2018-12-07  It checks the battlefield.")
            },
        )
        rule.onNodeWithContentDescription("Sol Ring").assertExists()
        rule.onNodeWithText("Rulings").performScrollTo()
        rule.onRoot().shoot("card_sheet_everything")
    }

    @Test
    fun backLeavesTheCard() {
        var left = 0
        mount { CardSheet(everything()) { left++ } }
        rule.onNodeWithText("← Back").performClick()
        rule.runOnIdle { assertEquals(1, left) }
    }

    // -------------------------------------------- stepping along a deck

    @Test
    fun aCardOpenedFromADeckStepsToTheOneEitherSideOfIt() {
        var stepped: String? = null
        mount {
            CardSheet(
                everything(),
                onClose = {},
                previous = deckCard("Bitterblossom"),
                next = deckCard("Arcane Signet"),
                place = "7 of 84",
                onStep = { stepped = it.name },
            )
        }
        Parity.check(
            Parity.Fact("the sheet says where you are in the deck") { seen("7 of 84") },
            Parity.Fact("the previous card is named on its control") { seen("← Bitterblossom") },
            Parity.Fact("the next card is named on its control") { seen("Arcane Signet →") },
        )
        rule.onNodeWithText("← Bitterblossom").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Arcane Signet →").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("Arcane Signet", stepped) }
        rule.onRoot().shoot("card_sheet_deck_steps")
    }

    @Test
    fun theEndsOfADeckOfferNothingToStepTo() {
        mount {
            CardSheet(everything(), onClose = {}, previous = null, next = null, place = "1 of 1")
        }
        Parity.check(
            Parity.Fact("the place is still stated on a one-card run") { seen("1 of 1") },
            Parity.Fact("a control with nowhere to go names no card") {
                seen("← Previous") && seen("Next →")
            },
        )
        rule.onNodeWithText("← Previous").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Next →").assertIsNotEnabled()
    }

    @Test
    fun aCardOpenedFromNowhereOffersNoStepsAtAll() {
        open(everything())
        Parity.check(
            Parity.Fact("a card with no run behind it offers no previous") {
                !seen("← Previous") && inside("← Bitterblossom") == 0
            },
            Parity.Fact("a card with no run behind it offers no next") { !seen("Next →") },
            Parity.Fact("and no place in a deck it is not part of") { inside(" of ") == 0 },
        )
    }

    @Test
    fun theStepsSurviveTheCardStillLoading() {
        // The web page draws them outside the loading branch: stepping
        // on is what you want most while the next card is on its way.
        mount {
            CardSheet(
                CardDetail(name = "Sol Ring").loading(),
                onClose = {},
                previous = deckCard("Bitterblossom"),
                next = deckCard("Arcane Signet"),
                place = "7 of 84",
            )
        }
        Parity.check(
            Parity.Fact("a loading card says it is loading") { seen("Loading…") },
            Parity.Fact("the steps stay while the card loads") {
                seen("7 of 84") && seen("← Bitterblossom")
            },
            Parity.Fact("nothing else is drawn over a card that has not arrived") {
                !seen("Rulings") && !seen("Printings")
            },
        )
    }
}
