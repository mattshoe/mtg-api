package org.mattshoe.mtg.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckStats
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck's five charts, on a device, measured.
 *
 * Every one of these has a sibling in `DeckStatsLayoutTest` under
 * Karma or in `DeckStatsTest` in the core, because a chart that
 * renders is not a chart that is right: a bar of the wrong height, a
 * count floating over the wrong column, a colour told only by its hue
 * — each is exactly as wrong as a bad number, and none of them shows
 * up in a test of the arithmetic.
 *
 * The geometry assertions read the laid-out bounds rather than the
 * state that produced them, which is the only way to catch what the
 * web caught here: fifteen and seventeen drawing identical because the
 * bar measured itself against a box it did not fit in.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DeckStatsParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------------- decks

    private fun card(
        name: String,
        type: String?,
        cost: String?,
        cmc: Double?,
        qty: Int = 1,
        owned: Int = qty,
        produces: String? = null,
        rarity: String = "rare",
        price: Double? = 1.5,
    ) = DeckCard(
        name, qty, null, owned,
        nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc,
        producedMana = produces, colorIdentity = "W", rarity = rarity, price = price,
    )

    /**
     * The web suite's own fixture, card for card — with the three
     * mystery cards owned by nobody, so the "not owned" figure has
     * something to say.
     */
    private fun deck() = listOf(
        card("Plains", "Basic Land — Plains", null, 0.0, qty = 20, produces = "W"),
        card("Island", "Basic Land — Island", null, 0.0, qty = 4, produces = "U"),
        card("Soldier", "Creature — Human Soldier", "{W}", 1.0, qty = 6),
        card("Knight", "Creature — Human Knight", "{1}{W}", 2.0, qty = 8),
        card("Angel", "Creature — Angel", "{4}{W}{W}", 6.0, qty = 2),
        card("Wrath", "Sorcery", "{2}{W}{W}", 4.0, rarity = "mythic"),
        card("Mystery", null, null, null, qty = 3, owned = 0),
    )

    /** Fifteen, fifteen and seventeen: the heights the web drew alike. */
    private fun tallDeck() = listOf(
        card("Plains", "Basic Land — Plains", null, 0.0, qty = 37, produces = "W"),
        card("One", "Creature — Human", "{W}", 1.0, qty = 5),
        card("Two", "Creature — Human", "{1}{W}", 2.0, qty = 15),
        card("Three", "Creature — Human", "{2}{W}", 3.0, qty = 15),
        card("Four", "Creature — Human", "{3}{W}", 4.0, qty = 17),
    )

    /** Ten white pips wanted, five sources to pay them with. */
    private fun lopsidedDeck() = listOf(
        card("Plains", "Basic Land — Plains", null, 0.0, qty = 5, produces = "W"),
        card("Convoke", "Creature — Human", "{W}{W}{W}{W}{W}", 5.0, qty = 2),
    )

    private fun stats(cards: List<DeckCard>): DeckStats = DeckAnalysis.of(cards)

    // --------------------------------------------------------- mounting

    /**
     * Mount the panel at a phone's width and wait for it to settle.
     *
     * It scrolls, because the five charts are taller than any phone
     * and a screenshot has to be able to reach the last of them.
     */
    private fun show(cards: List<DeckCard>) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    Column(
                        Modifier.width(390.dp).verticalScroll(rememberScrollState())
                            .testTag("stats"),
                    ) {
                        DeckStatsPanel(stats(cards))
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasTestTag("stats")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tag(t: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String): Boolean =
        rule.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun height(t: String): Float =
        tag(t).getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    private fun railWidth(t: String): Float =
        tag(t).getUnclippedBoundsInRoot().let { it.right.value - it.left.value }

    private fun textSomewhere(fragment: String): Boolean =
        rule.onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    // ============================================ the six headline figures

    @Test
    fun allSixHeadlineFiguresAreDrawn() {
        // `.figure .k { text-transform: uppercase }` on the web — the
        // visible label is shouted; the accessible name (asserted
        // elsewhere by content description) stays sentence case.
        show(deck())
        rule.onNodeWithText("CARDS").assertExists()
        rule.onNodeWithText("LANDS · 55%").assertExists()
        rule.onNodeWithText("AVG MANA").assertExists()
        rule.onNodeWithText("SPELLS").assertExists()
        rule.onNodeWithText("VALUE").assertExists()
        rule.onNodeWithText("NOT OWNED").assertExists()
    }

    @Test
    fun theTotalIsEveryCopyInTheList() {
        show(deck())
        assertEquals(44, stats(deck()).totalCards)
        rule.onNodeWithContentDescription("44 cards", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theLandShareIsAShareOfTheWholeDeck() {
        show(deck())
        val s = stats(deck())
        assertEquals(24, s.lands)
        assertEquals(55, s.landShare)
        rule.onNodeWithContentDescription("24 lands · 55%", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theAverageManaValueIsOverSpellsOnly() {
        // 6 at one, 8 at two, 1 at four, 2 at six: 38 over 17 spells
        // with a printing. Lands and the unknowns are out of it.
        show(deck())
        assertEquals(2.24, stats(deck()).averageManaValue)
        rule.onNodeWithContentDescription("2.24 avg mana", useUnmergedTree = true).assertExists()
    }

    @Test
    fun spellsAreEverythingThatIsNotALand() {
        show(deck())
        assertEquals(20, stats(deck()).spells)
        rule.onNodeWithContentDescription("20 spells", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theValueFigureIsTheDecksMoney() {
        show(deck())
        rule.onNodeWithContentDescription("\$66 value", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theNotOwnedFigureCountsWhatIsShort() {
        show(deck())
        assertEquals(3, stats(deck()).missing)
        rule.onNodeWithContentDescription("3 not owned", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theValueFigureSaysWhenSomeCardsHaveNoPrice() {
        // The web puts it in the figure's `title`. A phone has nothing
        // to hover, so it is the figure's description — which is also
        // the only way a screen reader hears it.
        val priceless = deck() + card("Ghost", "Creature", "{2}", 2.0, price = null)
        show(priceless)
        rule.onNodeWithContentDescription(
            "1 cards have no price",
            substring = true,
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun nothingIsSaidAboutPricesWhenEveryCardHasOne() {
        show(deck())
        assertTrue(
            rule.onAllNodes(hasContentDescription("have no price", substring = true), true)
                .fetchSemanticsNodes().isEmpty(),
            "a fully priced deck still claims a card has no price",
        )
    }

    @Test
    fun theNotOwnedFigureIsAbsentWhenNothingIsMissing() {
        show(listOf(card("Soldier", "Creature", "{W}", 1.0, qty = 4)))
        assertTrue(!textSomewhere("not owned"), "nothing is missing and it still says so")
    }

    @Test
    fun theValueFigureIsAbsentWhenNothingIsPriced() {
        show(listOf(card("Soldier", "Creature", "{W}", 1.0, qty = 4, price = null)))
        assertTrue(!textSomewhere("value"), "an unpriced deck still shows a value")
    }

    // ======================================================== the curve

    @Test
    fun theCurveLeavesTheLandsOut() {
        // Twenty-four lands. As nought-drops they would be the chart.
        show(deck())
        assertEquals(0, stats(deck()).curve.first { it.label == "0" }.value)
        assertEquals("", tag("curve-count-0").textOrEmpty())
    }

    @Test
    fun theColumnsRunNoughtThroughSevenPlus() {
        show(deck())
        listOf("0", "1", "2", "3", "4", "5", "6", "7+").forEach { label ->
            assertTrue(exists("curve-label-$label"), "no column labelled $label")
        }
    }

    @Test
    fun everythingAboveSevenIsOneColumn() {
        show(
            listOf(
                card("Big", "Creature", "{8}", 9.0),
                card("Bigger", "Creature", "{11}", 12.0),
                card("Seven", "Creature", "{7}", 7.0),
            ),
        )
        assertEquals("3", tag("curve-count-7+").textOrEmpty())
        assertTrue(!exists("curve-label-9"), "a nine-drop got its own column")
        assertTrue(!exists("curve-label-12"), "a twelve-drop got its own column")
    }

    @Test
    fun theCountRidesOnTopOfItsOwnBar() {
        // It must not be a row of numbers along the top of the chart,
        // nowhere near the column each one counts.
        show(deck())
        stats(deck()).curve.filter { it.value > 0 }.forEach { bar ->
            val b = tag("curve-bar-${bar.label}").getUnclippedBoundsInRoot()
            val n = tag("curve-count-${bar.label}").getUnclippedBoundsInRoot()
            assertTrue(
                n.bottom.value <= b.top.value + 1f,
                "the count for ${bar.label} is not above its bar: ${n.bottom} vs ${b.top}",
            )
            assertTrue(
                b.top.value - n.bottom.value < 10f,
                "the count for ${bar.label} floated ${b.top.value - n.bottom.value}dp off its bar",
            )
            val off = abs((n.left.value + n.right.value) / 2 - (b.left.value + b.right.value) / 2)
            assertTrue(off < 2f, "the count for ${bar.label} sits over a neighbour: ${off}dp off")
        }
    }

    @Test
    fun everyColumnWithCardsInItPrintsItsCount() {
        show(deck())
        stats(deck()).curve.forEach { bar ->
            assertEquals(
                if (bar.value > 0) "${bar.value}" else "",
                tag("curve-count-${bar.label}").textOrEmpty(),
                "the count on column ${bar.label}",
            )
        }
    }

    @Test
    fun theTallestColumnFillsTheChartAndTheRestAreToScale() {
        Parity.needsRealRendering()
        show(deck())
        val one = height("curve-bar-1")
        val two = height("curve-bar-2")
        assertTrue(two > 40f, "the tallest column is only ${two}dp")
        // Eight at two mana against six at one: visibly taller, not
        // merely a different number printed over the same box.
        assertTrue(two > one, "6 at one mana drew taller than 8 at two: $one vs $two")
        assertTrue(abs(two / 8f - one / 6f) < (two / 8f) * 0.12f, "not to scale: $one, $two")
    }

    @Test
    fun twoColumnsOnlyDrawAlikeWhenTheyAreAlike() {
        // Fifteen, fifteen and seventeen all drew the same height on
        // the web: the bar was a fraction of the whole column, number
        // included, so anything near the top overflowed.
        show(tallDeck())
        val five = height("curve-bar-1")
        val fifteen = height("curve-bar-2")
        val alsoFifteen = height("curve-bar-3")
        val seventeen = height("curve-bar-4")
        assertTrue(
            abs(fifteen - alsoFifteen) < 0.01f,
            "two columns of fifteen drew differently: $fifteen vs $alsoFifteen",
        )
        assertTrue(seventeen > fifteen + 3f, "seventeen drew the same as fifteen: $seventeen vs $fifteen")
        assertTrue(fifteen > five + 20f, "fifteen drew barely taller than five: $fifteen vs $five")
        val scale = seventeen / 17f
        assertTrue(abs(fifteen / 15f - scale) < scale * 0.12f, "not to scale: $fifteen at 15")
        assertTrue(abs(five / 5f - scale) < scale * 0.12f, "not to scale: $five at 5")
    }

    @Test
    fun theTallestBarsCountIsStillOnTheChart() {
        show(tallDeck())
        val chart = tag("curve").getUnclippedBoundsInRoot()
        val n = tag("curve-count-4").getUnclippedBoundsInRoot()
        assertTrue(
            n.top.value >= chart.top.value - 1f,
            "the tallest bar's count is clipped off the top of the chart",
        )
    }

    @Test
    fun noColumnOverflowsTheChartItIsIn() {
        show(tallDeck())
        val chart = tag("curve").getUnclippedBoundsInRoot()
        stats(tallDeck()).curve.forEach { bar ->
            val b = tag("curve-bar-${bar.label}").getUnclippedBoundsInRoot()
            assertTrue(b.top.value >= chart.top.value - 1f, "column ${bar.label} grew out of the top")
            assertTrue(
                b.bottom.value <= chart.bottom.value + 1f,
                "column ${bar.label} grew out of the bottom",
            )
        }
    }

    @Test
    fun anEmptyColumnIsAStubRatherThanABar() {
        // A floor to read the rest against, and nothing more.
        show(deck())
        val zero = height("curve-bar-0")
        assertTrue(zero > 0f && zero < 4f, "the nought column is ${zero}dp tall")
        assertTrue(zero < height("curve-bar-1") / 4f, "the empty column is as tall as a real one")
    }

    @Test
    fun theMedianIsCaptionedUnderTheCurve() {
        show(deck())
        // "2", not "2.0" — `Double.toString()` disagreed with itself
        // between Kotlin/JS and Kotlin/JVM on exactly this value, and
        // this assertion used to pin the JVM's own spelling of it.
        // See `manaValueText` in `:core`.
        rule.onNodeWithText("Median 2. Lands excluded.").assertExists()
    }

    @Test
    fun thereIsNoCurvePanelWithoutACurve() {
        show(listOf(card("Plains", "Basic Land — Plains", null, 0.0, qty = 40, produces = "W")))
        assertTrue(!exists("curve"), "an all-land deck drew a curve")
        assertTrue(!textSomewhere("Mana curve"), "an all-land deck titled a curve")
    }

    // ============================================ colour: needs and makes

    @Test
    fun eachColourShowsWhatItNeedsAgainstWhatItMakes() {
        show(deck())
        // White and blue, and nothing for the three the deck does not touch.
        assertTrue(exists("mana-row-W"), "no white row")
        assertTrue(exists("mana-row-U"), "no blue row")
        listOf("B", "R", "G").forEach {
            assertTrue(!exists("mana-row-$it"), "a row for $it, which this deck does not touch")
        }
        assertTrue(exists("track-W-needs") && exists("track-W-makes"), "needs and makes are two bars")
    }

    @Test
    fun bothBarsOfAColourAreWrittenOutInWords() {
        show(deck())
        val s = stats(deck())
        rule.onNodeWithText("${s.pips.first { it.label == "White" }.value} needs").assertExists()
        rule.onNodeWithText("${s.sources.first { it.label == "White" }.value} makes").assertExists()
    }

    @Test
    fun theNeedsAndMakesBarsShareOneScale() {
        // Ten pips wanted, five sources: the makes bar has to be half
        // the needs bar, or the pair is not a comparison.
        show(lopsidedDeck())
        val s = stats(lopsidedDeck())
        assertEquals(10, s.pips.first { it.label == "White" }.value)
        assertEquals(5, s.sources.first { it.label == "White" }.value)
        val needs = railWidth("track-W-needs")
        val makes = railWidth("track-W-makes")
        assertTrue(needs > 40f, "the needs bar is only ${needs}dp wide")
        // As a proportion, not as a count of pixels. The claim is that
        // the makes bar is half the needs bar; `fillMaxWidth(0.5f)`
        // rounds to whole pixels, so the answer lands a dp or two
        // either side depending on the screen's density — 104dp of 204
        // on CI's emulator against 102 of 204 here. A 2dp window on a
        // 100dp bar is tighter than that rounding and was failing on
        // the density rather than on the drawing. Three percent still
        // catches a bar at 40% or at full width, which is what going
        // wrong would actually look like.
        val share = makes / needs
        assertTrue(
            abs(share - 0.5f) < 0.03f,
            "five sources against ten pips drew ${makes}dp of ${needs}dp, a share of $share",
        )
    }

    @Test
    fun aColourWithNoSourceIsSaidOutLoud() {
        show(
            listOf(
                card("Plains", "Basic Land — Plains", null, 0.0, produces = "W"),
                card("Counterspell", "Instant", "{U}{U}", 2.0),
            ),
        )
        assertTrue(textSomewhere("No source for Blue"), "a splash with no sources went unsaid")
    }

    @Test
    fun nothingIsSaidWhenEveryColourHasASource() {
        show(deck())
        assertTrue(!textSomewhere("No source for"), "every colour has a source and it still complains")
    }

    @Test
    fun everyColourRowIsNamedByItsLetter() {
        // The owner is colourblind. A row told apart only by its hue
        // says nothing, so the pip carries its letter.
        show(deck())
        assertTrue(
            rule.onAllNodes(hasText("W"), true).fetchSemanticsNodes().isNotEmpty(),
            "the white row carries no letter",
        )
        assertTrue(
            rule.onAllNodes(hasText("U"), true).fetchSemanticsNodes().isNotEmpty(),
            "the blue row carries no letter",
        )
    }

    @Test
    fun theColourChartSaysWhatItIsComparing() {
        show(deck())
        assertTrue(
            textSomewhere("Pips the deck asks for, against cards that can produce them."),
            "the colour chart has no caption",
        )
        assertTrue(textSomewhere("Hybrid pips count for both halves."), "the hybrid rule went unsaid")
    }

    @Test
    fun thereIsNoColourPanelWhenThereIsNoColour() {
        show(listOf(card("Sol Ring", "Artifact", "{1}", 1.0, price = null)))
        assertTrue(!textSomewhere("needs"), "a colourless deck drew a needs bar")
    }

    // ================================================ colour: the two rings

    @Test
    fun eachColourSplitIsAlsoDrawnAsARing() {
        // A bar says how many white pips; a ring says what share of
        // the deck's colour is white, which is the question you ask
        // about a splash.
        show(deck())
        assertTrue(exists("ring-Needs"), "no ring for what the deck needs")
        assertTrue(exists("ring-Makes"), "no ring for what the deck makes")
    }

    @Test
    fun eachRingIsCaptionedWithItsTotal() {
        show(deck())
        val s = stats(deck())
        rule.onNodeWithText("Needs · ${s.pips.sumOf { it.value }}").assertExists()
        rule.onNodeWithText("Makes · ${s.sources.sumOf { it.value }}").assertExists()
    }

    @Test
    fun theRingLegendGivesEveryColourItsPercentage() {
        show(deck())
        val s = stats(deck())
        val total = s.sources.sumOf { it.value }
        assertTrue(s.sources.size >= 2, "this deck should make two colours")
        s.sources.forEach { bar ->
            val pct = (bar.value * 100) / total
            assertTrue(textSomewhere("$pct%"), "the makes ring has no slice at $pct%")
        }
    }

    @Test
    fun theRingNamesEverySliceRatherThanOnlyTintingIt() {
        // Colourblind: a share told by hue alone is no share at all.
        show(deck())
        val s = stats(deck())
        val total = s.sources.sumOf { it.value }
        val told = s.sources.joinToString(", ") { "${it.label} ${(it.value * 100) / total}%" }
        rule.onNodeWithContentDescription(told, substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theRingLegendPairsEveryPercentageWithItsLetter() {
        show(deck())
        // Two slices in the makes ring, so two letters beside it. "W"
        // and "U" are also the row pips, hence two of each.
        assertTrue(
            rule.onAllNodes(hasText("W"), true).fetchSemanticsNodes().size >= 2,
            "the ring legend has no letter for white",
        )
        assertTrue(
            rule.onAllNodes(hasText("U"), true).fetchSemanticsNodes().size >= 2,
            "the ring legend has no letter for blue",
        )
    }

    @Test
    fun everyPipLetterFitsInsideItsOwnDisc() {
        Parity.needsRealRendering()
        // The screenshot found this one: a `Text` keeps the theme's
        // line height whatever its font size, so a 9sp letter sat in
        // a 24sp line and centring the line left the glyph hanging
        // off the bottom of the disc as a sliver. Every pip is a
        // colour named by its letter, and a letter nobody can read
        // leaves a colourblind reader with nothing.
        // Measuring that the letter's box is inside the disc proves
        // nothing: the disc constrains the box, so the box always
        // fits while the glyph inside it hangs out of frame. What
        // gives it away is a line box as tall as the whole disc —
        // that is the theme's line height, not the letter's.
        show(deck())
        listOf("pip-row-W", "pip-row-U", "pip-Needs-W", "pip-Makes-W", "pip-Makes-U")
            .forEach { pip ->
                val disc = tag(pip).getUnclippedBoundsInRoot()
                val glyph = tag("$pip-letter").getUnclippedBoundsInRoot()
                val tall = glyph.bottom.value - glyph.top.value
                val room = disc.bottom.value - disc.top.value
                assertTrue(
                    tall <= room - 3f,
                    "$pip: the letter's line is ${tall}dp in a ${room}dp disc, so the glyph " +
                        "is riding outside it",
                )
                assertTrue(
                    abs((glyph.top.value + glyph.bottom.value) / 2 -
                        (disc.top.value + disc.bottom.value) / 2) < 1f,
                    "$pip: the letter is not centred in its disc",
                )
                assertTrue(
                    glyph.left.value >= disc.left.value - 0.5f &&
                        glyph.right.value <= disc.right.value + 0.5f,
                    "$pip: the letter runs out of the sides of its disc",
                )
            }
    }

    @Test
    fun theRingIsActuallyDrawnAndNotAnEmptyBox() {
        show(deck())
        assertTrue(height("ring-Needs") > 76f, "the needs ring is ${height("ring-Needs")}dp tall")
        assertTrue(height("ring-Makes") > 76f, "the makes ring is ${height("ring-Makes")}dp tall")
    }

    @Test
    fun aRingWithNothingInItIsNotDrawn() {
        // Coloured pips but nothing that makes mana: one ring, not two.
        show(
            listOf(
                card("Counterspell", "Instant", "{U}{U}", 2.0),
                card("Ruins", "Land", null, 0.0),
            ),
        )
        assertTrue(exists("ring-Needs"), "the needs ring went missing")
        assertTrue(!exists("ring-Makes"), "an empty makes ring was drawn anyway")
    }

    @Test
    fun aColourlessDeckDrawsNoRingAtAll() {
        show(listOf(card("Sol Ring", "Artifact", "{1}", 1.0, price = null)))
        assertTrue(!exists("ring-Needs") && !exists("ring-Makes"), "a colourless deck drew a ring")
    }

    // ============================================ types, rarity, footnote

    @Test
    fun theTypeBarsAreTheDecksGroupsWithTheirCounts() {
        show(deck())
        // `.stats-card > h3 { text-transform: uppercase }` on the web.
        rule.onNodeWithText("CARD TYPES").assertExists()
        assertEquals(
            listOf("Creatures", "Sorceries", "Lands", "Not in the collection"),
            stats(deck()).types.map { it.label },
        )
        stats(deck()).types.forEach { bar ->
            assertTrue(exists("hbar-${bar.label}"), "no bar for ${bar.label}")
            assertTrue(textSomewhere(bar.label), "${bar.label} is unlabelled")
        }
    }

    @Test
    fun theRarityBarsRunCommonToMythic() {
        show(deck())
        rule.onNodeWithText("RARITY").assertExists()
        assertEquals(listOf("Rare", "Mythic"), stats(deck()).rarities.map { it.label })
        assertTrue(exists("hbar-Rare") && exists("hbar-Mythic"))
    }

    @Test
    fun theTypeBarsAreToScaleAgainstTheBiggestOfThem() {
        show(deck())
        val bars = stats(deck()).types
        val most = bars.maxOf { it.value }
        val rail = railWidth("hbar-${bars.first { it.value == most }.label}")
        bars.filter { it.value != most }.forEach { bar ->
            val want = rail * (bar.value.toFloat() / most)
            val drew = railWidth("hbar-${bar.label}")
            assertTrue(abs(drew - want) < 2f, "${bar.label} drew ${drew}dp, wanted ${want}dp")
        }
    }

    @Test
    fun twoTypeBarsOnlyDrawAlikeWhenTheyAreAlike() {
        show(deck())
        val lands = railWidth("hbar-Lands")
        val creatures = railWidth("hbar-Creatures")
        assertTrue(lands > creatures + 2f, "24 lands drew the same as 16 creatures: $lands vs $creatures")
    }

    @Test
    fun eachBarChartSaysWhatItIsCountedOutOf() {
        show(deck())
        assertEquals(
            2,
            rule.onAllNodes(hasText("Of 44 cards."), true).fetchSemanticsNodes().size,
            "types and rarity each need their own total",
        )
    }

    @Test
    fun theCardsNobodyOwnsAreNamedRatherThanFoldedIn() {
        show(deck())
        assertTrue(
            textSomewhere("3 cards in this list have no printing"),
            "the three unknown cards went unmentioned",
        )
        assertTrue(textSomewhere("left out of every chart above"), "the footnote stops short")
    }

    @Test
    fun oneSuchCardIsSaidInTheSingular() {
        show(listOf(card("Soldier", "Creature", "{W}", 1.0), card("Mystery", null, null, null)))
        assertTrue(textSomewhere("1 card in this list"), "one unknown card was pluralised")
        assertTrue(!textSomewhere("1 cards in this list"), "one unknown card was pluralised")
    }

    @Test
    fun thereIsNoFootnoteWhenEveryCardHasAPrinting() {
        show(deck().filter { it.name != "Mystery" })
        assertTrue(!textSomewhere("have no printing"), "a complete deck apologised for nothing")
    }

    // ======================================================== the layout

    @Test
    fun nothingRunsOffTheEdgeAtPhoneWidth() {
        show(deck())
        val limit = tag("stats").getUnclippedBoundsInRoot().right.value + 1f
        val watched = buildList {
            add("curve")
            stats(deck()).curve.forEach { add("curve-bar-${it.label}"); add("curve-count-${it.label}") }
            addAll(listOf("mana-row-W", "mana-row-U", "track-W-needs", "track-W-makes"))
            addAll(listOf("ring-Needs", "ring-Makes"))
            stats(deck()).types.forEach { add("hbar-${it.label}") }
            stats(deck()).rarities.forEach { add("hbar-${it.label}") }
        }
        val over = watched.filter { exists(it) }
            .filter { tag(it).getUnclippedBoundsInRoot().right.value > limit }
        assertTrue(over.isEmpty(), "off the right edge at 390dp: $over")
    }

    @Test
    fun everyChartIsDisplayedRatherThanMerelyPresent() {
        show(deck())
        tag("curve").performScrollTo().assertIsDisplayed()
        tag("mana-row-W").performScrollTo().assertIsDisplayed()
        tag("ring-Makes").performScrollTo().assertIsDisplayed()
        tag("hbar-Lands").performScrollTo().assertIsDisplayed()
        tag("hbar-Mythic").performScrollTo().assertIsDisplayed()
    }

    // ===================================================== screenshots

    /**
     * A picture of each chart, pulled off the device and looked at.
     *
     * Numbers that assert true and a chart that draws as a wall of
     * text are the same bug, and only an eye catches the second.
     */
    @Test
    fun everyChartIsPhotographed() {
        Parity.needsRealRendering()
        show(deck())
        shootRoot("01-top")
        shoot("02-curve", "curve")
        shoot("03-colour-white", "mana-row-W")
        shoot("04-ring-needs", "ring-Needs")
        shoot("05-ring-makes", "ring-Makes")
        tag("hbar-Lands").performScrollTo()
        shootRoot("07-types-and-rarity")
        rule.onNode(hasText("left out of every chart above", substring = true), true)
            .performScrollTo()
        shootRoot("09-footnote")
    }

    @Test
    fun theTallCurveIsPhotographed() {
        Parity.needsRealRendering()
        show(tallDeck())
        shoot("08-curve-tall", "curve")
    }

    private fun shots(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "parity",
    ).apply { mkdirs() }

    private fun shoot(name: String, testTag: String) {
        tag(testTag).performScrollTo()
        rule.waitForIdle()
        save(name, tag(testTag).captureToImage().asAndroidBitmap())
    }

    private fun shootRoot(name: String) {
        rule.waitForIdle()
        save(name, rule.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bmp: Bitmap) =
        File(shots(), "$name.png").outputStream()
            .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** The text a node shows, or "" — a blank count is a real answer. */
private fun SemanticsNodeInteraction.textOrEmpty(): String {
    val entry = fetchSemanticsNode().config.firstOrNull { it.key.name == "Text" } ?: return ""
    @Suppress("UNCHECKED_CAST")
    val text = entry.value as? List<androidx.compose.ui.text.AnnotatedString> ?: return ""
    return text.joinToString("") { it.text }
}
