package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.Fact
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.ColorTarget
import org.mattshoe.mtg.core.DeckRef2
import org.mattshoe.mtg.core.Facet
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Sort
import org.mattshoe.mtg.core.Tri
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Library and its filters, on a device, against what the website
 * does.
 *
 * Every fact here is one the web suite already states —
 * `LibraryPageTest`, `FilterPanelTest`, `LibrarySweepTest`,
 * `LibraryDriverTest` — asserted again on the phone rather than
 * something adjacent that happens to pass. The gaps it was written to
 * catch, all of them real:
 *
 *  - the filter panel was a flat wall of boxes with no accordion, no
 *    count badges and no groups at all, so a filter applied from a link
 *    was somewhere in a page of fields;
 *  - half the columns the website filters on had no control at all —
 *    produces-mana, set type, layout, frame, border, games;
 *  - the facet lists the web offers as tappable checkboxes were
 *    comma-separated text boxes;
 *  - nothing re-ran the search when a filter changed, which is a filter
 *    that does nothing;
 *  - there was a Search button, a second owner picker and a Filters
 *    button that hid the whole grid, none of which the website has;
 *  - four of the fourteen sorts, as buttons, with no direction control.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The same fixture the web's `FilterPanelTest` hands the panel. */
    private val facets = Facets(
        types = listOf("Artifact", "Creature", "Land"),
        setTypes = listOf("core", "commander"),
        layouts = listOf("normal", "saga"),
        frames = listOf("1993", "2015"),
        borders = listOf("black", "borderless"),
        formats = listOf("commander", "modern"),
        decks = listOf(DeckRef2("alela", "Alela", "matt")),
    )

    private val filters = mutableStateOf(Filters())
    private val library = mutableStateOf(Library())
    private var searches = 0

    private fun card(name: String, qty: Int = 1, price: Double? = 2.5) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = qty, printings = 1, free = 1, price = price, value = price,
    )

    /** The panel on its own, scrollable, the way a phone shows it. */
    private fun panel(start: Filters = Filters()) {
        filters.value = start
        rule.setContent {
            MtgTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FilterSheet(filters.value, facets) { filters.value = it }
                }
            }
        }
        rule.waitForIdle()
    }

    /** The whole screen, wired the way the shell wires it. */
    private fun screen(start: Library) {
        library.value = start
        searches = 0
        rule.setContent {
            MtgTheme {
                LibraryScreen(
                    state = library.value,
                    onState = { library.value = it },
                    onSearch = { searches++ },
                    onOpen = {},
                    facets = facets,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun f() = filters.value

    private fun present(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun said(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun fold(facet: Facet) {
        rule.onNodeWithTag("header-${facet.id}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun press(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun pressText(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /**
     * Scroll the grid until the pager is composed.
     *
     * The web renders the whole page, so its pager is simply there. A
     * `LazyVerticalGrid` composes what is near the viewport and nothing
     * else, and the header — the panel plus ten groups — is taller than
     * a phone, so the pager exists only once it is scrolled to. Not a
     * difference in the screen, a difference in how the two are drawn.
     */
    private fun toThePager() {
        rule.onNodeWithTag("library").performScrollToNode(hasTestTag("pager"))
        rule.waitForIdle()
    }

    private fun type(tag: String, value: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        rule.waitForIdle()
    }

    private fun clear(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextClearance()
        rule.waitForIdle()
    }

    /**
     * How light this node is, on average.
     *
     * The one person who uses this app is colourblind, so "the chosen
     * one is the coloured one" is not a design. Every state here has to
     * be readable with hue thrown away, and this measures it off the
     * screen rather than taking the stylesheet's word for it.
     */
    private fun SemanticsNodeInteraction.lightness(): Double {
        val bitmap = captureToImage().asAndroidBitmap()
        fun straighten(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                sum += 0.2126 * straighten(((p shr 16) and 0xFF) / 255.0) +
                    0.7152 * straighten(((p shr 8) and 0xFF) / 255.0) +
                    0.0722 * straighten((p and 0xFF) / 255.0)
                n++
                x += 2
            }
            y += 2
        }
        return sum / n
    }

    // ------------------------------------------------------- the accordion

    @Test
    fun theFilterPanelIsTenGroupsAndTheyAllStartFoldedAway() {
        panel()
        Parity.check(
            *Facet.entries.map { facet ->
                Fact("the ${facet.title} group is on the screen") { present("facet-${facet.id}") }
            }.toTypedArray(),
            *Facet.entries.map { facet ->
                Fact("the ${facet.title} group starts folded away") { !present("body-${facet.id}") }
            }.toTypedArray(),
            Fact("a folded group does not render its controls") { !said("Mana cost contains") },
            Fact("the reset is below the groups") { said("Reset everything") },
            Fact("there is no Apply button, because it applies as you go") { !said("Apply") },
        )
        rule.onRoot().shoot("library-filters-closed")
    }

    @Test
    fun everyFilterGroupOpensAndClosesOnItsOwn() {
        panel()
        Facet.entries.forEach { facet ->
            fold(facet)
            assertTrue(present("body-${facet.id}"), "${facet.title} did not open")
            fold(facet)
            assertTrue(!present("body-${facet.id}"), "${facet.title} did not close")
        }
        // Two open at once, and neither shuts the other.
        fold(Facet.COLOUR)
        fold(Facet.MANA)
        Parity.check(
            Fact("opening one group") { present("body-mana") },
            Fact("must not close another") { present("body-colour") },
            Fact("the colour controls are on the screen now") { said("Produces mana") },
            Fact("and so are the mana ones") { said("Mana cost contains") },
        )
        rule.onRoot().shoot("library-filters-open")
    }

    @Test
    fun aGroupHoldingAFilterOpensItselfAndSaysHowMany() {
        panel(Filters(cmcMin = "2", cmcMax = "4", manaCost = "{G}"))
        Parity.check(
            Fact("the group holding a filter opened itself") { present("body-mana") },
            Fact("and says how many are set") {
                rule.onNodeWithTag("count-mana", useUnmergedTree = true)
                    .assertTextEquals("3")
                true
            },
            Fact("a group with nothing set stays shut") { !present("body-colour") },
            Fact("and wears no badge") { !present("count-colour") },
        )
        rule.onNodeWithTag("facet-mana").shoot("library-group-expanded")
    }

    @Test
    fun aGroupThatOpenedItselfDoesNotFoldWhenYouClearTheBox() {
        // On a phone this is the keyboard, the field and the whole
        // section going at once, mid-word.
        panel(Filters(artist = "quay"))
        assertTrue(present("body-text"), "the group holding a filter did not open itself")
        clear("box-artist")
        Parity.check(
            Fact("clearing the box must not fold the section away") { present("body-text") },
            Fact("and the filter is actually gone") { f().artist.isEmpty() },
        )
        type("box-artist", "seb")
        Parity.check(
            Fact("it must not fold away between two characters") { present("body-text") },
            Fact("and it takes what was typed") { f().artist == "seb" },
        )
        // Sticky must not mean stuck: the header still folds it.
        fold(Facet.TEXT)
        assertTrue(!present("body-text"), "the header would not close it")
    }

    // ---------------------------------------------------------- the colours

    @Test
    fun theColourPipsAccumulateAndComeBackOffAgain() {
        panel()
        fold(Facet.COLOUR)
        press("pip-colors-W")
        press("pip-colors-U")
        press("pip-colors-B")
        assertEquals(listOf("W", "U", "B"), f().colors, "the colours did not accumulate")
        press("pip-colors-G")
        press("pip-colors-G")
        assertEquals(listOf("W", "U", "B"), f().colors, "a second tap did not take it back off")
        press("pip-colors-C")
        Parity.check(
            Fact("colourless is its own pip, not a sixth colour") { "C" in f().colors },
            Fact("produces mana is a row of its own") { present("pip-produces-G") },
            Fact("and it writes its own column") {
                press("pip-produces-G")
                f().produces == listOf("G") && "G" !in f().colors
            },
        )
        rule.onNodeWithTag("facet-colour").shoot("library-colours-chosen")
    }

    @Test
    fun aChosenColourIsTellableApartWithoutHue() {
        Parity.needsRealRendering()
        panel()
        fold(Facet.COLOUR)
        // Every one of the six, measured off the screen: the repo owner
        // cannot tell green from red, so a pip that only changes hue
        // when it is chosen has no selected state at all.
        (listOf("W", "U", "B", "R", "G") + "C").forEach { letter ->
            val pip = { rule.onNodeWithTag("pip-colors-$letter") }
            pip().performScrollTo()
            pip().assertIsOff()
            val off = pip().lightness()
            pip().performClick()
            rule.waitForIdle()
            pip().assertIsOn()
            val on = pip().lightness()
            assertTrue(
                abs(on - off) > 0.03,
                "$letter chosen and not chosen differ by only ${abs(on - off)} in lightness",
            )
            // And it still says which colour it is, in letters, for
            // anything that cannot see it at all.
            val name = when (letter) {
                "W" -> "White"; "U" -> "Blue"; "B" -> "Black"
                "R" -> "Red"; "G" -> "Green"; else -> "Colourless"
            }
            assertEquals(
                2,
                rule.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size,
                "\$name is not named on both rows of pips",
            )
            pip().performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun theColourShortcutsAndModesAreAllOffered() {
        panel()
        fold(Facet.COLOUR)
        pressText("all five")
        assertEquals(listOf("W", "U", "B", "R", "G"), f().colors)
        pressText("clear")
        assertEquals(emptyList(), f().colors)
        pressText("colourless")
        Parity.check(
            Fact("colourless means exactly C") { f().colors == listOf("C") },
            Fact("and says so in the mode") { f().colorMode == ColorMode.EXACTLY },
        )
        ColorMode.entries.forEach { mode ->
            pressText(mode.label)
            assertEquals(mode, f().colorMode, "${mode.label} did not stick")
        }
        pressText("Printed colour")
        assertEquals(ColorTarget.PRINTED, f().colorTarget)
    }

    // ----------------------------------------------------------- the boxes

    @Test
    fun everyBoxNarrowsAndThenWidensAgain() {
        // The bug this exists for: type a name, delete it, and the
        // results stayed narrowed. The web sweeps every box for it; so
        // does this, through the `Filters` the panel writes.
        panel()
        val sweep = listOf(
            Triple(Facet.TEXT, "box-q", "bolt"),
            Triple(Facet.TEXT, "box-text", "draw card"),
            Triple(Facet.TEXT, "box-flavor", "kweh"),
            Triple(Facet.TEXT, "box-artist", "guay"),
            Triple(Facet.TEXT, "box-watermark", "boros"),
            Triple(Facet.TYPE, "box-typeLine", "creature !land"),
            Triple(Facet.MANA, "box-manaCost", "{G}{G}"),
            Triple(Facet.MANA, "box-pow", "4"),
            Triple(Facet.PRINTING, "box-collnum", "117"),
            Triple(Facet.COLLECTION, "box-freeMin", "2"),
        )
        val reads = mapOf(
            "box-q" to { f().q }, "box-text" to { f().text }, "box-flavor" to { f().flavor },
            "box-artist" to { f().artist }, "box-watermark" to { f().watermark },
            "box-typeLine" to { f().typeLine }, "box-manaCost" to { f().manaCost },
            "box-pow" to { f().pow }, "box-collnum" to { f().collnum },
            "box-freeMin" to { f().freeMin },
        )
        val groups = sweep.map { it.first }.distinct()
        groups.forEach { fold(it) }
        sweep.forEach { (_, tag, term) ->
            type(tag, term)
            assertEquals(term, reads.getValue(tag)(), "$tag did not narrow")
            clear(tag)
            assertEquals("", reads.getValue(tag)(), "$tag did not widen again")
        }
    }

    @Test
    fun everyRangeWritesBothEndsAndClearsAgain() {
        panel()
        fold(Facet.MANA)
        type("min-cmc", "3")
        type("max-cmc", "6")
        Parity.check(
            Fact("the low end of a range") { f().cmcMin == "3" },
            Fact("and the high end") { f().cmcMax == "6" },
        )
        clear("min-cmc")
        clear("max-cmc")
        assertEquals(Filters(), f(), "a cleared range left something behind")

        fold(Facet.COLLECTION)
        type("min-qty", "2")
        type("min-price", "5")
        type("min-edhrec", "100")
        Parity.check(
            Fact("copies owned") { f().qtyMin == "2" },
            Fact("price in USD") { f().priceMin == "5" },
            Fact("EDHREC rank") { f().edhrecMin == "100" },
        )
    }

    // ----------------------------------------------------- the facet lists

    @Test
    fun theFacetListsAreTickableRatherThanTyped() {
        // They were comma-separated text boxes here, and the website
        // offers what the collection actually holds.
        panel()
        fold(Facet.TYPE)
        press("check-types-Creature")
        press("check-types-Artifact")
        assertEquals(listOf("Creature", "Artifact"), f().types)
        press("check-types-Creature")
        assertEquals(listOf("Artifact"), f().types)
        rule.onNodeWithTag("check-types-Artifact").assertIsOn()
        rule.onNodeWithTag("check-types-Land").assertIsOff()
        fold(Facet.TYPE)

        fold(Facet.PRINTING)
        press("check-rarities-mythic")
        press("check-setTypes-commander")
        Parity.check(
            Fact("rarity is a list to tick") { f().rarities == listOf("mythic") },
            Fact("and so is set type") { f().setTypes == listOf("commander") },
        )
        fold(Facet.PRINTING)

        fold(Facet.PHYSICAL)
        press("check-layouts-saga")
        press("check-frames-2015")
        press("check-borders-borderless")
        press("check-games-arena")
        Parity.check(
            Fact("layout") { f().layouts == listOf("saga") },
            Fact("frame") { f().frames == listOf("2015") },
            Fact("border") { f().borders == listOf("borderless") },
            Fact("what it is available in") { f().games == listOf("arena") },
        )
    }

    @Test
    fun tokensAddOnEnterAndComeOffOnATap() {
        panel()
        fold(Facet.TAGS)
        type("token-keywords", "Flying")
        rule.onNodeWithTag("token-keywords").performImeAction()
        rule.waitForIdle()
        Parity.check(
            Fact("a keyword typed and entered is kept") { f().keywords == listOf("Flying") },
            Fact("and shown as something removable") { said("Flying ×") },
            Fact("the box clears itself ready for the next one") { said("Flying, Ward…") },
        )
        pressText("Flying ×")
        assertEquals(emptyList(), f().keywords, "the chip would not come off")

        type("token-tags", "mana-rock")
        rule.onNodeWithTag("token-tags").performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("mana-rock"), f().tags)
    }

    // ------------------------------------------------- flags and legality

    @Test
    fun theFlagsAreThreeValuedAndLegalityWaitsForAFormat() {
        panel()
        fold(Facet.FLAGS)
        rule.onNode(hasText("yes") and hasAnyAncestor(hasTestTag("tri-reserved")))
            .performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Tri.YES, f().flags[Flag.RESERVED])
        rule.onNode(hasText("no") and hasAnyAncestor(hasTestTag("tri-reserved")))
            .performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Tri.NO, f().flags[Flag.RESERVED])
        Parity.check(
            *Flag.entries.map { flag ->
                Fact("${flag.label} is on the screen") { said(flag.label) }
            }.toTypedArray(),
        )
        fold(Facet.FLAGS)

        fold(Facet.LEGALITY)
        rule.onNodeWithTag("select-legality").performScrollTo().assertIsNotEnabled()
        press("select-format")
        pressText("modern")
        Parity.check(
            Fact("the format reaches the filters") { f().format == "modern" },
            Fact("and the status is live once there is a format") {
                rule.onNodeWithTag("select-legality").performScrollTo()
                press("select-legality")
                pressText("banned")
                f().legality == "banned"
            },
            Fact("has-rulings is three-valued too") {
                rule.onNode(hasText("yes") and hasAnyAncestor(hasTestTag("tri-hasRulings")))
                    .performScrollTo().performClick()
                rule.waitForIdle()
                f().hasRulings == Tri.YES
            },
        )
    }

    @Test
    fun theCollectionGroupHoldsWhichDeckAndNotWhoseItIs() {
        // A Matt / Kayla / Both switch was the first row of this
        // group. The page is one collection now, whichever the
        // address names, so a filter offering somebody else's cards
        // from inside it cannot mean anything.
        panel()
        fold(Facet.COLLECTION)
        Parity.check(
            Fact("nothing asks whose collection it is") { !said("Whose") },
            Fact("and neither of the two names is offered") { !said("Kayla") },
        )
        pressText("Unassigned")
        assertEquals(Pool.FREE, f().pool)
        press("select-deck")
        Parity.check(
            Fact("the any-deck option is offered") { said("— in any deck —") },
            Fact("the no-deck option is offered") { said("— in no deck —") },
            Fact("and the collection's decks by name") { said("Alela (matt)") },
        )
        pressText("Alela (matt)")
        assertEquals("alela", f().deck)
    }

    @Test
    fun resetPutsEverythingBack() {
        panel(Filters(q = "bolt", colors = listOf("R"), cmcMin = "2"))
        pressText("Reset everything")
        assertEquals(Filters(), f(), "reset left a filter behind")
    }

    // ------------------------------------------------------- the screen

    @Test
    fun theGridSaysWhichHundredOfHowManyAndTheTileCarriesThePrice() {
        screen(Library(filters = Filters(page = 2)).loaded(listOf(card("Sol Ring", 3), card("Opt")), 6607))
        Parity.check(
            Fact("the range and the total, the way a person reads it") { said("101–200 of 6607") },
            Fact("which page of how many") { toThePager(); said("Page 2 of 67") },
            Fact("the card's name") { said("Sol Ring") },
            Fact("how many are owned") { said("×3") },
            Fact("and what one is worth") { said("\$2.50") },
            Fact("no mana cost on the tile, which the website dropped") { !said("{1}") },
            Fact("and no set code") { !said("M3C") },
            Fact("the filter groups are on the same screen as the grid") { present("facet-colour") },
        )
        rule.onRoot().shoot("library-grid")
    }

    @Test
    fun theTopRowHasExactlyTheControlsTheWebsiteHas() {
        screen(Library().loaded(listOf(card("Sol Ring")), 1))
        Parity.check(
            Fact("there is no Search button, because it applies as you go") { !said("Search") },
            Fact("there is no Filters button hiding the panel") { !said("Filters") },
            Fact("nor a Hide filters one") { !said("Hide filters") },
            Fact("the owner picker is not duplicated above the panel") { !said("Kayla") },
            Fact("the export says where it is going") { said("Copy") },
            Fact("and it is not called Export decklist any more") { !said("Export decklist") },
            Fact("the name box is still the first thing on the page") { said("Card name") },
        )
    }

    @Test
    fun theSortIsEverySortWithADirectionBesideIt() {
        screen(Library().loaded(listOf(card("Sol Ring")), 1))
        Parity.check(
            Fact("price descending is the default") { said("Price") },
            Fact("and the arrow says which way") { said("↓") },
            Fact("which it also says in words") {
                rule.onNodeWithContentDescription("Largest first").assertExists()
                true
            },
        )
        press("select-sort")
        Parity.check(
            *Sort.entries.map { sort ->
                Fact("${sort.label} is offered") { said(sort.label) }
            }.toTypedArray(),
        )
        pressText("Mana value")
        Parity.check(
            Fact("picking a sort reaches the state") { library.value.filters.sort == Sort.CMC },
            Fact("picking a column must not silently reverse it") { library.value.filters.descending },
            Fact("and it asked the database exactly once") { searches == 1 },
        )
        pressText("↓")
        Parity.check(
            Fact("the arrow flips the direction") { !library.value.filters.descending },
            Fact("without changing the column") { library.value.filters.sort == Sort.CMC },
            Fact("and turns around") { said("↑") },
        )
    }

    @Test
    fun thePagerIsDeadAtBothEndsAndMoves() {
        screen(Library().loaded(listOf(card("A")), 250))
        toThePager()
        rule.onNodeWithText("← Previous").assertIsNotEnabled()
        rule.onNodeWithText("Next →").performClick()
        rule.waitForIdle()
        Parity.check(
            Fact("next goes to page two") { library.value.page == 2 },
            Fact("and asks for it") { searches == 1 },
        )
        rule.runOnIdle { library.value = Library(filters = Filters(page = 3)).loaded(listOf(card("A")), 250) }
        rule.waitForIdle()
        toThePager()
        rule.onNodeWithText("Next →").assertIsNotEnabled()
        rule.runOnIdle { library.value = Library().loaded(listOf(card("A")), 3) }
        rule.waitForIdle()
        toThePager()
        Parity.check(
            Fact("previous is dead on a single page") {
                rule.onNodeWithText("← Previous").assertIsNotEnabled()
                true
            },
            Fact("and so is next") {
                rule.onNodeWithText("Next →").assertIsNotEnabled()
                true
            },
            Fact("and it still says which page of how many") { said("Page 1 of 1") },
        )
    }

    @Test
    fun searchingEmptyAndFailedEachSayWhatHappened() {
        screen(Library().loaded(emptyList(), 0))
        Parity.check(
            Fact("nothing matched, and it says so") { said("Nothing matches that.") },
            Fact("and shows no pager over an empty grid") { !present("pager") },
        )
        rule.onRoot().shoot("library-empty")

        rule.runOnIdle { library.value = Library().loading() }
        rule.waitForIdle()
        assertTrue(said("Searching…"), "a first search does not say it is searching")

        rule.runOnIdle { library.value = Library().failed("HTTP 500") }
        rule.waitForIdle()
        assertTrue(said("Search failed: HTTP 500"), "a failure does not name itself")

        // Rows already on screen stay there while a narrower search is
        // in flight — the web dims the grid rather than replacing it,
        // because collapsing the page throws away where you were.
        rule.runOnIdle { library.value = Library().loaded(listOf(card("Sol Ring")), 1).loading() }
        rule.waitForIdle()
        // The grid is lazy, so a row below the fold is not composed at
        // all. "Still there" is a claim about the list, not about what
        // fits on this particular screen, so scroll to it and then ask.
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()
        Parity.check(
            Fact("it still says it is searching") { said("Searching…") },
            Fact("and the rows are still there") { said("Sol Ring") },
        )
    }

    @Test
    fun everyControlThatChangesAFilterAsksForAFreshSearch() {
        // The whole bug, on the web and then here: the filter was
        // applied to the state and nobody asked the database again.
        screen(Library().loaded(listOf(card("Sol Ring")), 1))
        fun ran(what: String, act: () -> Unit) {
            val before = searches
            act()
            rule.waitForIdle()
            assertTrue(searches > before, "$what did not re-run the search")
        }
        fold(Facet.COLOUR)
        ran("choosing a colour") { press("pip-colors-G") }
        ran("changing the colour mode") { pressText("At least") }
        fold(Facet.COLOUR)
        fold(Facet.COLLECTION)
        ran("choosing a pool") { pressText("Unassigned") }
        Parity.check(
            Fact("and narrowing goes back to page one") { library.value.page == 1 },
            Fact("the colour reached the filters") { library.value.filters.colors == listOf("G") },
            Fact("and so did the pool") { library.value.filters.pool == Pool.FREE },
        )
    }
}
