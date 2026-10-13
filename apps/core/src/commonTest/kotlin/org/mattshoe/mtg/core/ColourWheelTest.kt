package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The colour wheel, as a Guide page of its own.
 *
 * Matt: "i want a guide section on the color wheel. i know nothing
 * about it and it'll be cool to have ... it gets its own dedicated
 * page"
 */
class ColourWheelTest {

    @Test
    fun theWheelIsATileOfItsOwnAtItsOwnAddress() {
        val tile = Guide.tile(Guide.WHEEL) ?: error("the Guide has no colour wheel tile: ${Guide.tiles}")
        assertTrue(tile.title.contains("wheel", ignoreCase = true), "the tile does not say wheel: ${tile.title}")
        assertTrue(tile.blurb.isNotBlank(), "the tile says nothing under its title")
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/wheel", open.route.toHash())
        assertEquals(tile, AppState().navigate(Route.parse("#/guide/wheel")).guideTile, "a bookmark does not land on it")
    }

    @Test
    fun theFiveColoursGoRoundTheWheelInWubrgOrder() {
        assertEquals(listOf("W", "U", "B", "R", "G"), ColourWheel.colours.map { it.pip })
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green"), ColourWheel.colours.map { it.name })
    }

    @Test
    fun aColoursAlliesAreItsNeighboursAndItsEnemiesAreAcrossTheWheel() {
        assertEquals(setOf("U", "G"), ColourWheel.allies("W").map { it.pip }.toSet(), "white's allies")
        assertEquals(setOf("B", "R"), ColourWheel.enemies("W").map { it.pip }.toSet(), "white's enemies")
        assertEquals(setOf("W", "B"), ColourWheel.allies("U").map { it.pip }.toSet(), "blue's allies")
        assertEquals(setOf("U", "R"), ColourWheel.enemies("G").map { it.pip }.toSet(), "green's enemies")
        ColourWheel.colours.forEach { c ->
            val allies = ColourWheel.allies(c.pip).map { it.pip }
            val enemies = ColourWheel.enemies(c.pip).map { it.pip }
            assertEquals(2, allies.size, "${c.name} has $allies for allies")
            assertEquals(2, enemies.size, "${c.name} has $enemies for enemies")
            assertEquals(
                "WUBRG".map { it.toString() }.toSet() - c.pip, (allies + enemies).toSet(),
                "${c.name} is not friend or foe to every other colour exactly once",
            )
        }
    }

    @Test
    fun everyColourSaysWhatItWantsAndWhatItIsGoodAndBadAt() {
        ColourWheel.colours.forEach { c ->
            assertTrue(c.wants.isNotBlank(), "${c.name} does not say what it wants")
            assertTrue(c.goodAt.isNotBlank(), "${c.name} does not say what it is good at")
            assertTrue(c.badAt.isNotBlank(), "${c.name} does not say what it is bad at")
        }
    }

    @Test
    fun theTenPairsAreEveryPairOnceNamedAsTheDecksPageNamesThem() {
        val sorted = { p: String -> "WUBRG".filter { it in p } }
        val allied = ColourWheel.allied.map { sorted(it.pips) }.toSet()
        val enemy = ColourWheel.enemy.map { sorted(it.pips) }.toSet()
        assertEquals(setOf("WU", "UB", "BR", "RG", "WG"), allied, "the allied pairs")
        assertEquals(setOf("WB", "UR", "BG", "WR", "UG"), enemy, "the enemy pairs")
        (ColourWheel.allied + ColourWheel.enemy).forEach { p ->
            assertEquals(Guild.of(p.pips), p.name, "${p.pips} is named differently from a deck of those colours")
            assertTrue(p.why.isNotBlank(), "${p.name} does not say why they get along, or do not")
        }
    }

    @Test
    fun thePageStartsBySayingWhatTheWheelIs() {
        assertTrue(ColourWheel.intro.isNotEmpty(), "the page has no opening")
        val words = ColourWheel.intro.joinToString(" ")
        assertTrue(words.contains("ally", ignoreCase = true) || words.contains("allies", ignoreCase = true), "the opening never says ally: $words")
        assertTrue(words.contains("enem", ignoreCase = true), "the opening never says enemy: $words")
        assertNotNull(Guide.tile(Guide.COLOURS), "the colour combinations tile is gone")
    }
}
