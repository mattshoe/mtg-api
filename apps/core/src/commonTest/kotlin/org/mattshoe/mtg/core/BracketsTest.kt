package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's Commander brackets: one card per bracket, lowest first.
 *
 * Matt: "create a guide section which shows the rules on brackets ...
 * each bracket section should have its own tile or section or card or
 * something, and each should be collapsed by default ... sort them in
 * ascending order".
 */
class BracketsTest {

    private val onThePage = AppState().navigate(Route.parse("#/guide/${Guide.BRACKETS}"))

    private fun bracket(n: Int): Bracket =
        Brackets.all.firstOrNull { it.number == n } ?: error("there is no bracket $n: ${Brackets.all.map { it.number }}")

    private fun says(n: Int, topic: String): BracketRule =
        bracket(n).rules.firstOrNull { it.topic == topic } ?: error("bracket $n says nothing about $topic: ${bracket(n).rules.map { it.topic }}")

    @Test
    fun theBracketsTileComesRightAfterRulesAndBeforeGameChangers() {
        val slugs = Guide.tiles.map { it.slug }
        val at = slugs.indexOf(Guide.BRACKETS)
        assertTrue(at >= 0, "the Guide has no brackets tile: $slugs")
        assertEquals(slugs.indexOf(Guide.RULES) + 1, at, "the brackets tile is not right after Rules: $slugs")
        assertEquals(slugs.indexOf(Guide.GAME_CHANGERS) - 1, at, "the brackets tile is not right before Game Changers: $slugs")
        val tile = Guide.tiles[at]
        assertEquals("Brackets", tile.title)
        assertEquals(tile, onThePage.guideTile, "#/guide/${Guide.BRACKETS} does not land on the tile")
        assertEquals("Brackets", onThePage.title)
    }

    @Test
    fun theBracketsGoOneToFiveExhibitionToCedh() {
        assertEquals(listOf(1, 2, 3, 4, 5), Brackets.all.map { it.number }, "the brackets are not in ascending order")
        assertEquals(listOf("Exhibition", "Core", "Upgraded", "Optimized", "cEDH"), Brackets.all.map { it.name })
        Brackets.all.forEach { b ->
            assertTrue(b.tagline.isNotBlank() && b.about.isNotBlank() && b.games.isNotBlank(), "bracket ${b.number} says nothing about itself: $b")
        }
    }

    @Test
    fun everyBracketAnswersTheSameQuestionsInTheSameOrder() {
        val topics = Brackets.all.map { b -> b.rules.map { it.topic } }.distinct()
        assertEquals(1, topics.size, "the brackets answer different questions: $topics")
        assertEquals(
            listOf("Game Changers", "Two-card combos", "Mass land denial", "Extra turns", "Tutors"),
            topics.single(),
        )
    }

    @Test
    fun gameChangersAreNoneThenUpToThreeThenAny() {
        // The same as the Game Changers page says.
        assertEquals(listOf(Allowed.NO, Allowed.NO, Allowed.SOME, Allowed.YES, Allowed.YES), (1..5).map { says(it, "Game Changers").allowed })
        assertEquals("Up to three", says(3, "Game Changers").short)
        assertTrue("bracket 3 allows up to three" in GameChangers.ABOUT, "the Game Changers page disagrees: ${GameChangers.ABOUT}")
    }

    @Test
    fun theLowerThreeBanMassLandDenialAndFourAndFiveAllowEverything() {
        (1..3).forEach { assertEquals(Allowed.NO, says(it, "Mass land denial").allowed, "bracket $it allows mass land denial") }
        (4..5).forEach { n ->
            val held = bracket(n).rules.filter { it.allowed != Allowed.YES }
            assertTrue(held.isEmpty(), "bracket $n holds something back: $held")
        }
        assertEquals(Allowed.NO, says(2, "Two-card combos").allowed)
        assertEquals(Allowed.SOME, says(3, "Two-card combos").allowed, "bracket 3's combos are not limited to late in the game")
    }
}
