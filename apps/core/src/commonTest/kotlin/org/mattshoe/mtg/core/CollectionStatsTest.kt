package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Stats page's breakdowns, worked out from the collection's rows.
 *
 * Matt: "the stats page is so barren and not useful. i want TONS of
 * data and i want to be able to filter on like colors and types".
 */
class CollectionStatsTest {

    private fun card(
        name: String,
        qty: Int = 1,
        type: String = "Creature — Human Warrior",
        identity: String = "",
        cmc: Double = 2.0,
        rarity: String = "common",
        set: String = "Bloomburrow",
        year: String = "2024",
        artist: String = "Somebody",
        price: Double? = 1.0,
        foil: Boolean = false,
    ) = StatCard(name, qty, foil, cmc, type, identity, rarity, set, year, artist, price)

    private val pile = listOf(
        card("Llanowar Elves", qty = 4, type = "Creature — Elf Druid", identity = "G", cmc = 1.0, price = 0.25),
        card("Lightning Bolt", qty = 3, type = "Instant", identity = "R", cmc = 1.0, rarity = "uncommon", price = 2.0),
        card("Sol Ring", qty = 2, type = "Artifact", cmc = 1.0, rarity = "uncommon", set = "Commander", price = 3.0, foil = true),
        card("Forest", qty = 10, type = "Basic Land — Forest", identity = "G", cmc = 0.0, price = null),
        card("Atraxa, Praetors' Voice", type = "Legendary Creature — Phyrexian Angel Horror", identity = "WUBG", cmc = 4.0, rarity = "mythic", set = "Commander", year = "2016", price = 30.0),
        card("Dryad Arbor", type = "Land Creature — Forest Dryad", identity = "G", cmc = 0.0, rarity = "uncommon", price = 1.5),
        card("Kolaghan's Command", qty = 2, type = "Instant", identity = "BR", cmc = 4.0, rarity = "uncommon", price = 0.5),
    )

    @Test
    fun theWholeCollectionIsCountedByCopiesAndValuedByWhatEachCopyIsWorth() {
        val b = CollectionAnalysis.of(pile)
        assertEquals(23, b.copies, "copies")
        assertEquals(7, b.uniques, "names")
        // 4×0.25 + 3×2 + 2×3 + 30 + 1.5 + 2×0.5 = 45.5. Forests have no price.
        assertEquals(45.5, b.value)
        assertEquals(10, b.unpriced, "the ten unpriced Forests")
        assertEquals(2, b.foils)
        assertEquals(2, b.sets, "Bloomburrow and Commander")
    }

    @Test
    fun aCardOfTwoTypesIsCountedInEach() {
        val b = CollectionAnalysis.of(pile)
        val types = b.types.associate { it.label to it.value }
        // Dryad Arbor is a land and a creature.
        assertEquals(6, types["Creature"], "Llanowar 4, Atraxa 1, Dryad Arbor 1")
        assertEquals(11, types["Land"], "ten Forests and the Arbor")
        assertEquals(5, types["Instant"])
        assertEquals(2, types["Artifact"])
    }

    @Test
    fun aTabIsOnlyTheCardsOfItsType() {
        val b = CollectionAnalysis.of(pile, StatTab.CREATURE)
        assertEquals(6, b.copies)
        assertEquals(listOf("Llanowar Elves", "Atraxa, Praetors' Voice", "Dryad Arbor").sorted(), b.names.sorted())
    }

    @Test
    fun theTabsSayHowManyCardsAreInEachAndHideTheEmptyOnes() {
        val tabs = CollectionAnalysis.tabs(pile, StatFilter())
        assertEquals(StatTab.ALL to 23, tabs.first(), "the landing tab is the whole collection")
        assertTrue(tabs.none { it.first == StatTab.SORCERY }, "a tab with nothing in it: $tabs")
        assertEquals(5, tabs.first { it.first == StatTab.INSTANT }.second)
    }

    @Test
    fun aFilterChangesTheTabsCountsButNotWhichTabsThereAre() {
        // Red has no creatures. The tab stays, says nought, and opens
        // on "nothing matches" rather than jumping out from under a
        // finger reaching for it.
        val tabs = CollectionAnalysis.tabs(pile, StatFilter().toggle(Pip.R))
        assertEquals(0, tabs.firstOrNull { it.first == StatTab.CREATURE }?.second, "the Creatures tab under red: $tabs")
        assertEquals(5, tabs.first().second)
    }

    @Test
    fun aColourFilterKeepsCardsWhoseIdentityHasThatColour() {
        val red = CollectionAnalysis.of(pile, filter = StatFilter().toggle(Pip.R))
        assertEquals(listOf("Kolaghan's Command", "Lightning Bolt"), red.names.sorted())
        // Colourless is a colour you can ask for, and it means no colour.
        val none = CollectionAnalysis.of(pile, filter = StatFilter().toggle(Pip.C))
        assertEquals(listOf("Sol Ring"), none.names)
        // Two colours is either of them.
        val either = CollectionAnalysis.of(pile, filter = StatFilter().toggle(Pip.R).toggle(Pip.W))
        assertEquals(3, either.uniques, "the two red cards and Atraxa")
    }

    @Test
    fun aRarityFilterKeepsThatRarityAndStacksWithTheTabAndColours() {
        val b = CollectionAnalysis.of(pile, StatTab.INSTANT, StatFilter().toggleRarity("uncommon").toggle(Pip.B))
        assertEquals(listOf("Kolaghan's Command"), b.names)
    }

    @Test
    fun nothingMatchingIsAnEmptyBreakdownRatherThanAnError() {
        val b = CollectionAnalysis.of(pile, StatTab.BATTLE)
        assertEquals(0, b.copies)
        assertNull(b.value)
        assertTrue(b.isEmpty)
    }

    @Test
    fun colourIsCountedOncePerColourInTheIdentity() {
        val b = CollectionAnalysis.of(pile)
        val colours = b.colours.associate { it.label to it.value }
        assertEquals(16, colours["Green"], "4 elves, 10 forests, Atraxa, Arbor")
        assertEquals(1, colours["White"])
        assertEquals(3, colours["Black"], "Atraxa and two Commands")
        assertEquals(2, colours["Colourless"], "Sol Ring")
    }

    @Test
    fun theExactIdentitiesAreNamedLikeAGuild() {
        val b = CollectionAnalysis.of(pile)
        val named = b.identities.associate { it.label to it.note }
        assertEquals("Rakdos", named["BR"])
        assertEquals("Mono-green", named["G"])
        assertEquals(15, b.identities.first { it.label == "G" }.value)
        assertEquals(b.identities.size, b.identityRing.size, "every identity is a numbered slice")
    }

    @Test
    fun theCurveLeavesLandsOut() {
        val curve = CollectionAnalysis.of(pile).curve.associate { it.label to it.value }
        assertEquals(0, curve["0"], "lands are not nought-drops")
        assertEquals(9, curve["1"])
        assertEquals(3, curve["4"])
    }

    @Test
    fun theTopListsAreMostFirst() {
        val b = CollectionAnalysis.of(pile)
        // Four Elf Druids are four of each; a tie is alphabetical.
        assertEquals(listOf("Druid" to 4, "Elf" to 4), b.tribes.take(2).map { it.label to it.value })
        assertTrue(b.tribes.any { it.label == "Forest" }, "Dryad Arbor is a Forest Dryad creature")
        assertEquals("Bloomburrow", b.topSets.first().label)
        assertEquals("Atraxa, Praetors' Voice", b.priciest.first().name)
        assertEquals(30.0, b.priciest.first().each)
        assertEquals(listOf("2016", "2024"), b.years.map { it.label }, "oldest first")
    }

    @Test
    fun valueIsSplitByRarityAndColourInWholeDollars() {
        val b = CollectionAnalysis.of(pile)
        val rarity = b.valueByRarity.associate { it.label to it.value }
        assertEquals(30, rarity["Mythic"])
        // 2×3 + 3×2 + 1.5 + 2×0.5 = 14.5, rounded.
        assertEquals(15, rarity["Uncommon"])
        val colour = b.valueByColour.associate { it.label to it.value }
        assertEquals(6, colour["Colourless"])
    }

    @Test
    fun copiesAreBandedByWhatEachIsWorth() {
        val bands = CollectionAnalysis.of(pile).priceBands.associate { it.label to it.value }
        assertEquals(6, bands["Under $1"], "four elves and two Commands")
        assertEquals(6, bands["$1 to $5"], "three Bolts, two Sol Rings, the Arbor")
        assertEquals(1, bands["$20 and up"])
        assertEquals(10, bands["No price"])
    }

    @Test
    fun theRowsComeBackFromTheQueryByColumnName() {
        val cols = listOf("name", "qty", "finish", "cmc", "type_line", "color_identity", "rarity", "set_name", "year", "artist", "price")
        val row = JsonArray(
            listOf(
                JsonPrimitive("Sol Ring"), JsonPrimitive(2), JsonPrimitive("foil"), JsonPrimitive(1.0),
                JsonPrimitive("Artifact"), JsonNull, JsonPrimitive("uncommon"), JsonPrimitive("Commander"),
                JsonPrimitive("2020"), JsonPrimitive("Mark Tedin"), JsonNull,
            ),
        )
        val c = StatsQueries.decodeCards(cols, listOf(row)).single()
        assertEquals(StatCard("Sol Ring", 2, true, 1.0, "Artifact", "", "uncommon", "Commander", "2020", "Mark Tedin", null), c)
    }

    @Test
    fun theCardsQueryAsksForEveryRowOfTheCollectionOnScreenAndNoOneElses() {
        val q = StatsQueries.cards(StatsScope("t4pee71g"))
        assertEquals(listOf<Any?>("t4pee71g"), q.params)
        assertTrue(q.sql.trimEnd().endsWith("LIMIT 50000"), "the server's 5,000-row default would cut the collection short: ${q.sql}")
    }

    @Test
    fun theStatePicksATabAndFiltersWithoutLosingTheRows() {
        val s = StatsState().loaded(Totals(), pile).onTab(StatTab.INSTANT).toggleColour(Pip.R)
        assertEquals(5, s.breakdown.copies)
        assertEquals(pile, s.cards)
        val cleared = s.clearFilters()
        assertEquals(StatTab.INSTANT, cleared.tab, "clearing the filters keeps the tab")
        assertEquals(StatFilter(), cleared.filter)
    }
}
