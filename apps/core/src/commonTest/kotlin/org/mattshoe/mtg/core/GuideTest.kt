package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Guide: a tab of tips and references.
 *
 * Matt: "i want a new section in the bottom nav ... a helpful little
 * tips and info and references and whatnot ... when you tap the tab on
 * bottom nav, you land on a new page, which is a collection of very
 * attractive and stylish 'tiles' ... for the first 'item' we add, i
 * want a reference to all the names for all the color combinations
 * like izzet, dimir, etc etc. all of them all the way through wubrg."
 */
class GuideTest {

    private val all: List<Combo> get() = Guide.colours.flatMap { it.combos }

    @Test
    fun theGuideIsATabEverybodyHas() {
        assertEquals("Guide", View.GUIDE.label)
        assertTrue(View.GUIDE in Admin().bar, "a signed-out app's bar has no Guide: ${Admin().bar}")
        val signedIn = Admin().signIn(Account(key = "e7de0cb1"), "t")
        assertTrue(View.GUIDE in signedIn.bar, "a signed-in app's bar has no Guide: ${signedIn.bar}")
    }

    @Test
    fun theTilesGoRulesGameChangersColourCombinationsThenTheWheel() {
        // Matt: "reorder the Guide sections such that they stay in order
        // of: rules, game changers, color combinations, color wheel".
        assertEquals(
            listOf(Guide.RULES, Guide.BRACKETS, Guide.GAME_CHANGERS, Guide.BANNED, Guide.COLOURS, Guide.WHEEL),
            Guide.tiles.take(6).map { it.slug },
            "the Guide's first six tiles are in the wrong order: ${Guide.tiles.map { it.title }}",
        )
        Guide.tiles.forEach { assertTrue(it.title.isNotBlank() && it.blurb.isNotBlank(), "the tile says nothing: $it") }
    }

    @Test
    fun everyCombinationFromOneColourToAllFiveIsThereOnce() {
        val sorted = all.map { c -> "WUBRG".filter { it in c.pips } }
        val every = (1 until 32).map { bits -> "WUBRG".filterIndexed { i, _ -> bits and (1 shl i) != 0 } }.toSet()
        assertEquals(31, every.size)
        assertEquals(sorted.size, sorted.toSet().size, "a combination is listed twice: $sorted")
        assertEquals(every, sorted.toSet(), "missing: ${every - sorted.toSet()}")
    }

    @Test
    fun theNamesAreTheOnesTheDecksPageUses() {
        // A deck's hero says "Izzet" through `Guild`; the reference
        // must not call the same two colours something else.
        all.filter { it.pips.length != 4 }.forEach { c ->
            assertEquals(Guild.of(c.pips), c.name, "${c.pips} is named differently from a deck of those colours")
        }
        assertEquals("Izzet", all.single { it.pips.toSet() == "UR".toSet() }.name)
        assertEquals("Dimir", all.single { it.pips.toSet() == "UB".toSet() }.name)
    }

    @Test
    fun aFourColourSetSaysWhichColourItLeavesOut() {
        val words = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")
        all.filter { it.pips.length == 4 }.forEach { c ->
            val missing = "WUBRG".single { it !in c.pips }
            assertTrue(c.name.isNotBlank(), "${c.pips} has no name")
            assertTrue(
                c.note.contains(words.getValue(missing), ignoreCase = true),
                "${c.name} (${c.pips}) does not say it leaves out ${words.getValue(missing)}: \"${c.note}\"",
            )
        }
    }

    @Test
    fun theGroupsGoFromOneColourUpToFive() {
        val sizes = Guide.colours.map { g -> g.combos.map { it.pips.length }.distinct() }
        sizes.forEach { assertEquals(1, it.size, "a group mixes sizes: $sizes") }
        assertEquals(sizes.map { it.single() }.sorted(), sizes.map { it.single() }, "the groups are out of order")
        Guide.colours.forEach { assertTrue(it.title.isNotBlank() && it.about.isNotBlank(), "a group says nothing: $it") }
    }

    @Test
    fun aTileOpensAtItsOwnAddressAndBackIsTheTiles() {
        val tile = Guide.tile(Guide.COLOURS) ?: error("no colours tile")
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/colours", open.route.toHash())
        assertEquals(tile, open.guideTile)
        assertEquals(tile.title, open.title, "the header does not name the page")

        val back = open.back() ?: error("back from a tile closed the app")
        assertEquals(View.GUIDE, back.view)
        assertNull(back.guideTile, "back did not leave the tile")
    }

    @Test
    fun aBookmarkToATileLandsOnIt() {
        val s = AppState().navigate(Route.parse("#/guide/colours"))
        assertNotNull(s.guideTile, "#/guide/colours did not open the colours tile")
        val nowhere = AppState().navigate(Route.parse("#/guide/nope"))
        assertNull(nowhere.guideTile)
        assertEquals("Guide", nowhere.title)
    }

    @Test
    fun theGuideAsksTheServerForNothing() {
        assertEquals(emptyList(), Load.needs(Route(View.GUIDE)))
        assertEquals(emptyList(), Load.needs(Route(View.GUIDE, Guide.COLOURS)))
        assertTrue(View.GUIDE !in View.COLLECTION)
    }

    @Test
    fun theListIsEveryCombinationInWubrgOrder() {
        // Matt: "just a list of color combos and names, in wubrg order,
        // so they can easily be scanned to find what i need".
        val pips = Guide.list.map { it.pips }
        assertEquals(
            listOf(
                "W", "U", "B", "R", "G",
                "WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG",
                "WUB", "WUR", "WUG", "WBR", "WBG", "WRG", "UBR", "UBG", "URG", "BRG",
                "WUBR", "WUBG", "WURG", "WBRG", "UBRG",
                "WUBRG",
            ),
            pips,
            "the list is not in WUBRG order",
        )
        assertEquals("Selesnya", Guide.list.single { it.pips == "WG" }.name)
        assertEquals("Glint-Eye", Guide.list.single { it.pips == "UBRG" }.name)
        assertEquals("Five-colour", Guide.list.last().name)
    }
}
