package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "i want to see Guide section on all the modern sets from that
 * few years. it should be similar to the root Lore section ... each set
 * gets is own page, preferably in extreme detail, with logical tabs
 * where applicable".
 *
 * So Sets is a tile beside Lore, a page of tiles, one per set in the
 * order they came out, and a set opens as its own page whose articles
 * are its tabs, drawn exactly as a Lore section is.
 */
class SetsTest {

    private val sets: GuideTile get() = assertNotNull(Guide.tile(Guide.SETS), "the Guide has no Sets tile: ${Guide.tiles.map { it.slug }}")

    private val asked = listOf(
        "one" to "Phyrexia: All Will Be One",
        "mom" to "March of the Machine",
        "woe" to "Wilds of Eldraine",
        "lci" to "The Lost Caverns of Ixalan",
        "mkm" to "Murders at Karlov Manor",
        "otj" to "Outlaws of Thunder Junction",
        "blb" to "Bloomburrow",
        "dsk" to "Duskmourn: House of Horror",
        "dft" to "Aetherdrift",
        "tdm" to "Tarkir: Dragonstorm",
        "eoe" to "Edge of Eternities",
    )

    private fun words(a: StoryArticle) = a.tabs.sumOf { t -> t.story.sumOf { MagicStory.plain(it).split(Regex("\\s+")).size } }

    private fun text(a: StoryArticle) = a.tabs.joinToString(" ") { t -> t.story.joinToString(" ") { MagicStory.plain(it) } }

    @Test
    fun theGuideHasASetsTileRightAfterLore() {
        val slugs = Guide.tiles.map { it.slug }
        assertEquals(listOf(Guide.LORE, Guide.SETS), slugs.takeLast(2), "Sets is not the tile after Lore: $slugs")
        assertEquals("Sets", sets.title)
        assertTrue(sets.blurb.isNotBlank(), "the Sets tile says nothing")
    }

    @Test
    fun setsHoldsEveryModernSetInTheOrderTheyCameOut() {
        assertEquals(asked.map { it.second }, Guide.sets.map { it.title }, "the Sets tiles are not the modern sets, oldest first")
        assertEquals(asked.map { it.first }, MagicStory.sets.map { it.slug }, "a set is not at its own code")
        MagicStory.sets.forEach { s ->
            assertEquals(s.hook, Guide.tile(s.slug)?.blurb, "${s.title}'s tile does not carry its hook")
            assertTrue(s.hook.trim().last() == '.', "${s.title}'s hook is not a sentence: ${s.hook}")
            assertEquals(1, Regex("[.?](\\s|$)").findAll(s.hook.trim()).count(), "${s.title}'s hook is not one sentence: ${s.hook}")
        }
    }

    @Test
    fun aSetOpensAsItsOwnPageAndBackIsSets() {
        val page = AppState().navigate(View.GUIDE).openGuide(sets)
        assertEquals("#/guide/sets", page.route.toHash())
        assertNull(page.storyPage, "Sets is a page of tiles, not a story")
        assertEquals("Guide", page.guideUpTo)
        MagicStory.sets.forEach { s ->
            val open = page.openGuide(assertNotNull(Guide.tile(s.slug), "${s.title} is not a tile"))
            assertEquals("#/guide/${s.slug}", open.route.toHash())
            assertEquals(StoryPage.Reading(s, s.articles.first()), open.storyPage, "${s.title} does not open on its first tab")
            assertEquals("Sets", open.guideUpTo, "${s.title}'s way back does not say Sets")
            assertEquals("#/guide/sets", open.guideUp().route.toHash(), "back from ${s.title} does not go to Sets")
            val tab = s.articles.last()
            val deep = open.openStory(tab.slug)
            assertEquals("#/guide/${s.slug}/${tab.slug}", deep.route.toHash(), "${s.title}'s tab is not in the address")
            assertEquals(StoryPage.Reading(s, tab), deep.storyPage)
        }
    }

    @Test
    fun everySetIsToldInExtremeDetailWithItsOwnTabs() {
        MagicStory.sets.forEach { s ->
            assertTrue(s.articles.size >= 6, "${s.title} has ${s.articles.size} tabs")
            val total = s.articles.sumOf(::words)
            assertTrue(total >= 5000, "${s.title} is $total words, which is not extreme detail")
            s.articles.forEach { a ->
                assertTrue(a.title.isNotBlank() && a.title.length <= 24, "${s.title}'s tab is ${a.title.length} characters: ${a.title}")
                assertTrue(a.tabs.size >= 2, "${s.title} / ${a.title} has ${a.tabs.size} part")
                a.tabs.forEach { t ->
                    assertTrue(t.tab.isNotBlank() && t.tab.length <= 24, "${a.title}'s part is ${t.tab.length} characters: ${t.tab}")
                    assertTrue(t.story.size >= 2, "${a.title} / ${t.tab} is ${t.story.size} paragraph")
                    val n = t.story.sumOf { MagicStory.plain(it).split(Regex("\\s+")).size }
                    assertTrue(n >= 150, "${a.title} / ${t.tab} is $n words, a note rather than a telling")
                }
            }
        }
    }

    @Test
    fun everyAddressIsUniqueAndUnderItsSetsCode() {
        val all = MagicStory.articles + MagicStory.sets.flatMap { it.articles }
        val slugs = all.map { it.slug } + MagicStory.sections.map { it.slug } + MagicStory.sets.map { it.slug } +
            (Guide.tiles.map { it.slug } - Guide.lore.map { it.slug }.toSet())
        assertEquals(slugs.size, slugs.toSet().size, "two things share an address: ${slugs.groupBy { it }.filter { it.value.size > 1 }.keys}")
        MagicStory.sets.forEach { s ->
            s.articles.forEach { a ->
                assertTrue(a.slug.matches(Regex("${s.slug}-[a-z0-9-]+")), "${a.slug} is not under ${s.slug}")
                val tabs = a.tabs.map { it.slug }
                assertEquals(tabs.size, tabs.toSet().size, "${a.title} has two parts at one address: $tabs")
                tabs.forEach { assertTrue(it.matches(Regex("${s.slug}-[a-z0-9-]+")), "${a.title}'s part $it is not under ${s.slug}") }
            }
        }
    }

    @Test
    fun theNarrationInventsNoDialogueAndQuotesOnlyWithASource() {
        MagicStory.sets.flatMap { it.articles }.forEach { a ->
            a.tabs.forEach { t ->
                t.story.forEach { p ->
                    val plain = MagicStory.plain(p)
                    val bad = plain.filter { it in "\"“”;:!—–[](){}" }
                    assertTrue(bad.isEmpty(), "${a.title} / ${t.tab} has $bad in its narration: ${plain.take(120)}")
                }
                t.quote?.let { q -> assertTrue(q.words.isNotBlank() && q.source.isNotBlank(), "${a.title} / ${t.tab} has a quote with no source") }
            }
        }
    }

    @Test
    fun everyLinkGoesSomewhereAndEveryoneNamedIsLinked() {
        MagicStory.sets.forEach { s ->
            s.articles.forEach { a ->
                MagicStory.links(a).forEach { target ->
                    val page = assertNotNull(MagicStory.page(s, target), "${s.title} / ${a.title} links to $target, which is nothing")
                    if (page is StoryPage.Reading) assertTrue(page.article != a, "${a.title} links to itself")
                }
                MagicStory.people.filter { MagicStory.mentions(a, it) }.forEach { p ->
                    assertTrue(MagicStory.target(p) in MagicStory.links(a), "${s.title} / ${a.title} names ${p.name} and never links to their profile")
                    assertTrue(a in MagicStory.appearances(p), "${p.name}'s profile does not list ${s.title} / ${a.title}")
                }
            }
        }
    }

    @Test
    fun everyTabSaysWhereItIsToldAndHalfNameACard() {
        MagicStory.sets.forEach { s ->
            s.articles.forEach { a ->
                assertTrue(a.sources.all { it.kind in MagicStory.SOURCE_KINDS && it.name.isNotBlank() }, "${a.title} has a bad source: ${a.sources}")
                assertTrue(a.sources.any { it.kind != "Reference" }, "${s.title} / ${a.title} names no set, novel or story that tells it")
                assertTrue(a.sources.any { it.kind == "Reference" }, "${s.title} / ${a.title} says nothing it was checked against")
            }
            val withCards = s.articles.count { MagicStory.cards(it).isNotEmpty() }
            assertTrue(withCards * 2 >= s.articles.size, "only $withCards of ${s.articles.size} tabs of ${s.title} name a card")
        }
    }

    @Test
    fun readOnRunsThroughASetAndOnToTheNextOne() {
        MagicStory.sets.zipWithNext().forEach { (a, b) ->
            a.articles.zipWithNext().forEach { (x, y) -> assertEquals(y, MagicStory.nextArticle(x), "${x.title} does not lead on to ${y.title}") }
            assertEquals(b.articles.first(), MagicStory.nextArticle(a.articles.last()), "the end of ${a.title} does not lead to ${b.title}")
        }
        assertNull(MagicStory.nextArticle(MagicStory.sets.last().articles.last()), "the newest set's last tab leads somewhere")
        assertNull(MagicStory.nextArticle(MagicStory.articles.last()), "the end of Lore runs on into the sets")
    }

    @Test
    fun theGuideSearchFindsASetsTabs() {
        val blb = MagicStory.sets.first { it.slug == "blb" }
        val hits = GuideSearch.search("Bloomburrow").filter { it.part == blb.title }
        assertTrue(hits.isNotEmpty(), "searching Bloomburrow finds nothing on the Bloomburrow page")
        assertTrue(hits.all { it.address.startsWith("blb/blb-") }, "a Bloomburrow hit is not at its tab's address: ${hits.map { it.address }}")
        val open = AppState().navigate(View.GUIDE).searchGuide("Bloomburrow").openGuideHit(hits.first())
        assertEquals(blb, open.storyPage?.section, "a Bloomburrow hit does not open Bloomburrow")
    }

    @Test
    fun theSetsMattKnowsFromLoreLinkToTheirLoreArticle() {
        listOf("woe" to "wilds-of-eldraine", "mkm" to "karlov-manor", "otj" to "thunder-junction", "dsk" to "duskmourn", "dft" to "aetherdrift", "tdm" to "tarkir-dragonstorm").forEach { (code, lore) ->
            val s = MagicStory.sets.first { it.slug == code }
            val links = s.articles.flatMap { MagicStory.links(it) }.map { it.substringBefore('/') }
            assertTrue(lore in links, "${s.title} never links to the Lore article $lore: ${links.toSet()}")
        }
        val text = MagicStory.sets.first { it.slug == "blb" }.articles.joinToString(" ") { text(it) }
        assertTrue("Valley" in text, "Bloomburrow never mentions the Valley")
    }
}
