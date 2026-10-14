package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The story of Phyrexia, as a Guide page of tabs.
 *
 * Matt: "i want a new guide section on phyrexian lore. Lore the whole
 * thing. from thran to modern times and everything in between ... i
 * want this particular guide to be tabbed, so the user can tab through
 * the various 'eras' ... it should be written as an enthralling story,
 * not dry textbook."
 */
class PhyrexianLoreTest {

    private val tile: GuideTile
        get() = Guide.tile(Guide.PHYREXIA) ?: error("the Guide has no Phyrexia tile: ${Guide.tiles.map { it.slug }}")

    @Test
    fun phyrexiaIsATileOfItsOwnAtItsOwnAddress() {
        assertTrue(tile.title.contains("Phyrexia", ignoreCase = true), "the tile does not say Phyrexia: ${tile.title}")
        assertTrue(tile.blurb.isNotBlank(), "the tile says nothing under its title")
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/phyrexia", open.route.toHash())
        assertEquals(tile, open.guideTile, "opening the tile does not land on it")
        assertEquals(tile.title, open.title, "the header does not name the page")
    }

    @Test
    fun theStoryRunsFromTheThranToTheEndOfTheInvasion() {
        val eras = PhyrexianLore.eras
        assertTrue(eras.size >= 6, "a story from the Thran to now in ${eras.size} eras skips most of it: ${eras.map { it.tab }}")
        assertTrue(eras.first().tab.contains("Thran"), "the story does not start with the Thran: ${eras.first().tab}")
        val names = eras.joinToString(" ") { it.tab + " " + it.title }
        listOf("Brothers", "Rath", "Mirrodin", "Machine").forEach {
            assertTrue(names.contains(it), "no era for $it: ${eras.map { e -> e.tab }}")
        }
        assertEquals(eras.size, eras.map { it.slug }.toSet().size, "two eras share an address: ${eras.map { it.slug }}")
        eras.forEach { assertTrue(it.slug.matches(Regex("[a-z-]+")), "${it.slug} is not an address") }
    }

    @Test
    fun everyEraReadsAsAStoryNotAHeadingAndALine() {
        PhyrexianLore.eras.forEach { era ->
            assertTrue(era.tab.isNotBlank() && era.tab.length <= 20, "${era.slug}'s tab label is ${era.tab.length} characters: ${era.tab}")
            assertTrue(era.title.isNotBlank(), "${era.slug} has no title")
            assertTrue(era.story.size >= 4, "${era.slug} is ${era.story.size} paragraphs, which is a note, not a chapter")
            val words = era.story.sumOf { it.split(Regex("\\s+")).size }
            assertTrue(words >= 300, "${era.slug} is $words words, too short to be a chapter of a story")
            era.story.forEach { assertTrue(it.isNotBlank(), "${era.slug} has an empty paragraph") }
        }
    }

    @Test
    fun aQuoteAlwaysSaysWhereItIsFrom() {
        val quoted = PhyrexianLore.eras.mapNotNull { it.quote }
        assertTrue(quoted.isNotEmpty(), "no era quotes the cards, and Matt asked for that")
        quoted.forEach { q ->
            assertTrue(q.words.isNotBlank(), "a quote says nothing")
            assertTrue(q.source.isNotBlank(), "\"${q.words}\" does not say where it is from")
        }
    }

    @Test
    fun theTileOpensOnTheFirstEra() {
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals(PhyrexianLore.eras.first(), open.loreEra, "the page opens somewhere other than the start")
    }

    @Test
    fun aTabIsItsOwnAddressSoBackAndABookmarkBothWork() {
        val second = PhyrexianLore.eras[1]
        val open = AppState().navigate(View.GUIDE).openGuide(tile).openEra(second)
        assertEquals("#/guide/phyrexia/${second.slug}", open.route.toHash())
        assertEquals(second, open.loreEra, "the tab did not change the era")
        assertEquals(tile, open.guideTile, "a tab is not a page of the Phyrexia tile")
        assertEquals(tile.title, open.title, "the header stops naming the page on the second tab")

        val bookmarked = AppState().navigate(Route.parse("#/guide/phyrexia/${second.slug}"))
        assertEquals(second, bookmarked.loreEra, "a bookmark does not land on its era")
    }

    @Test
    fun anEraThatDoesNotExistIsTheStartNotABlankPage() {
        val stale = AppState().navigate(Route.parse("#/guide/phyrexia/nowhere"))
        assertEquals(tile, stale.guideTile, "a stale era is not the Phyrexia page at all")
        assertEquals(PhyrexianLore.eras.first(), stale.loreEra)
    }

    @Test
    fun noOtherPageHasAnEra() {
        assertNull(AppState().navigate(Route.parse("#/guide/wheel")).loreEra, "the wheel has an era")
        assertNull(AppState().navigate(View.GUIDE).loreEra, "the tiles have an era")
        assertNull(AppState().loreEra, "the Library has an era")
    }

    @Test
    fun backFromAnyEraIsTheTiles() {
        val deep = AppState().navigate(Route.parse("#/guide/phyrexia/${PhyrexianLore.eras.last().slug}"))
        val back = assertNotNull(deep.back(), "back from an era closed the app")
        assertEquals("#/guide", back.route.toHash())
    }

    @Test
    fun eachEraLeadsOnToTheNextAndTheLastToNothing() {
        val eras = PhyrexianLore.eras
        eras.zipWithNext().forEach { (a, b) -> assertEquals(b, PhyrexianLore.next(a), "${a.slug} does not lead on to ${b.slug}") }
        assertNull(PhyrexianLore.next(eras.last()), "the last era leads somewhere")
    }
}
