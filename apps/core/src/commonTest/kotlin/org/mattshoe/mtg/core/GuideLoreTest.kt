package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every story tile, in one Lore tile.
 *
 * Matt: "collapse all the Guide sections on lore (not the color stuff)
 * into a single top-level one called Lore or something. then just move
 * the current tiles into that one but keep them sorted in roughly
 * chronological order as much as possible".
 */
class GuideLoreTest {

    private val lore: GuideTile get() = assertNotNull(Guide.tile(Guide.LORE), "the Guide has no Lore tile: ${Guide.tiles.map { it.slug }}")

    @Test
    fun theFrontPageIsTheReferencesAndOneLoreTile() {
        assertEquals(
            listOf(Guide.COLOURS, Guide.WHEEL, Guide.RULES, Guide.GAME_CHANGERS, Guide.LORE),
            Guide.tiles.map { it.slug },
            "the front page still carries the story tiles one by one",
        )
        assertEquals("Lore", lore.title)
    }

    @Test
    fun loreHoldsEveryStoryTileFromTheVoidToTheOmenpaths() {
        assertEquals(
            listOf(
                "blind-eternities", "phyrexia-origins", Guide.PHYREXIA, "brothers-war", "weatherlight", "planeswalkers",
                "ravnica", "nicol-bolas", "eldrazi", "innistrad", "new-phyrexia", "omenpaths",
            ),
            Guide.lore.map { it.slug },
            "the Lore tiles are not in the order the story happened",
        )
        assertEquals(
            (MagicStory.sections.map { it.slug } + Guide.PHYREXIA).sorted(),
            Guide.lore.map { it.slug }.sorted(),
            "a story tile went missing, or turned up twice, moving into Lore",
        )
    }

    @Test
    fun loreOpensAtItsOwnAddressAndBackIsTheGuide() {
        val open = AppState().navigate(View.GUIDE).openGuide(lore)
        assertEquals("#/guide/lore", open.route.toHash())
        assertEquals(lore, open.guideTile)
        assertEquals("Lore", open.title)
        assertNull(open.storyPage, "Lore is a page of tiles, not a story")
        assertEquals("Guide", open.guideUpTo, "Lore's way back does not say Guide")
        val back = assertNotNull(open.back(), "back from Lore closed the app")
        assertEquals("#/guide", back.route.toHash())
        assertEquals("#/guide", open.guideUp().route.toHash())
    }

    @Test
    fun aStoryOpenedFromLoreGoesBackToLore() {
        val war = assertNotNull(Guide.tile("brothers-war"))
        val open = AppState().navigate(View.GUIDE).openGuide(lore).openGuide(war)
        assertEquals("#/guide/brothers-war", open.route.toHash())
        assertEquals("Lore", open.guideUpTo, "a story's way back does not say Lore")
        assertEquals("#/guide/lore", open.back()?.route?.toHash(), "back from a story skipped Lore")
        assertEquals("#/guide/lore", open.guideUp().route.toHash(), "the back button skipped Lore")

        val era = AppState().navigate(Route.parse("#/guide/phyrexia/thran"))
        assertEquals("#/guide/lore", era.back()?.route?.toHash(), "back from Phyrexia skipped Lore")
    }

    @Test
    fun theReferencesStillGoBackToTheGuide() {
        val wheel = AppState().navigate(View.GUIDE).openGuide(assertNotNull(Guide.tile(Guide.WHEEL)))
        assertEquals("Guide", wheel.guideUpTo)
        assertEquals("#/guide", wheel.back()?.route?.toHash())
    }

    @Test
    fun anOldBookmarkStillOpensItsStory() {
        val koilos = AppState().navigate(Route.parse("#/guide/brothers-war/koilos"))
        assertEquals("Koilos", (koilos.storyPage as? StoryPage.Reading)?.article?.title, "an old address to Koilos lands nowhere")
        val thran = AppState().navigate(Route.parse("#/guide/phyrexia/thran"))
        assertEquals("thran", thran.loreEra?.slug, "an old address to a Phyrexia era lands nowhere")
    }
}
