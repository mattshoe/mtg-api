package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Blind Eternities, and the shape of the Multiverse, as a Guide section.
 *
 * Matt: "i want a guide section about the blind eternities, and
 * generally the "topology" of the mtg multiverse/universe/whatever".
 * It is a section like the other eleven, a tile with its articles as
 * tabs, so everything `MagicStoryTest` holds the story to holds here too.
 */
class BlindEternitiesTest {

    private fun section(): StorySection =
        assertNotNull(MagicStory.section("blind-eternities"), "there is no Blind Eternities section: ${MagicStory.sections.map { it.slug }}")

    @Test
    fun theBlindEternitiesIsAGuideTileOfItsOwn() {
        val s = section()
        assertEquals("The Blind Eternities", s.title)
        val tile = assertNotNull(Guide.tile(s.slug), "the Blind Eternities is not a Guide tile: ${Guide.tiles.map { it.slug }}")
        assertEquals(s.hook, tile.blurb, "the tile does not carry the section's hook")
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/blind-eternities", open.route.toHash())
        assertEquals(StoryPage.Reading(s, s.articles.first()), open.storyPage, "the tile does not open on its first tab")
    }

    @Test
    fun itTellsTheShapeOfTheMultiverseNotOnlyTheVoid() {
        assertEquals(
            listOf("The Multiverse", "The Blind Eternities", "Crossing the Void", "Planes Made and Unmade"),
            section().articles.map { it.title },
            "the section does not cover the planes, the void between them, the ways across it and the planes made and lost",
        )
    }

    @Test
    fun everyArticleTiesBackIntoTheStory() {
        val s = section()
        s.articles.forEach { a ->
            val elsewhere = MagicStory.links(a).mapNotNull { MagicStory.page(s, it) as? StoryPage.Reading }.filter { it.section != s }
            assertTrue(elsewhere.size >= 2, "${a.title} links to ${elsewhere.size} articles in the rest of the story, so it stands apart from it")
        }
    }

    @Test
    fun theStoryLinksToItWhereItCrossesTheVoid() {
        val s = section()
        listOf("the-omenpaths", "edge-of-eternities", "the-binding").forEach { slug ->
            val from = assertNotNull(MagicStory.article(slug), "there is no $slug")
            assertTrue(
                MagicStory.links(from).any { t -> s.articles.any { it.slug == t.substringBefore('/') } },
                "${from.title} names the Blind Eternities and does not link to the section about them",
            )
        }
    }
}
