package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Matt: "please add the background lore for the Wilds of eldraine into
 * the Omenppaths lore section."
 *
 * Wilds of Eldraine is the first story of the Omenpath era, so it comes
 * straight after the article on the paths themselves and before Thunder
 * Junction, where the same Kellan and Eriette turn up again.
 */
class WildsOfEldraineTest {

    private val era get() = MagicStory.sections.first { it.slug == "omenpaths" }

    private val wilds get() = assertNotNull(MagicStory.article("wilds-of-eldraine"), "there is no Wilds of Eldraine article")

    private fun text(a: StoryArticle) = a.tabs.joinToString(" ") { t -> t.story.joinToString(" ") { MagicStory.plain(it) } }

    @Test
    fun itIsAnArticleOfTheOmenpathEraBetweenTheOmenpathsAndThunderJunction() {
        val titles = era.articles.map { it.title }
        val at = titles.indexOf("Wilds of Eldraine")
        assertTrue(at >= 0, "the Omenpath Era has no Wilds of Eldraine: $titles")
        assertEquals("The Omenpaths", titles[at - 1], "Wilds of Eldraine does not follow The Omenpaths: $titles")
        assertEquals("Thunder Junction", titles[at + 1], "Wilds of Eldraine does not come before Thunder Junction: $titles")
        assertEquals(era, MagicStory.sectionOf(wilds))
    }

    @Test
    fun itTellsTheWickedSlumberAndKellansQuestInOrder() {
        assertEquals(
            listOf("The Wicked Slumber", "Talion's Bargain", "Agatha's Cauldron", "The Frozen Heart", "The Ardent Queen", "Broken Oaths"),
            wilds.tabs.map { it.tab },
            "the tabs are not the story in the order it happens",
        )
        val told = text(wilds)
        listOf("Wicked Slumber", "Talion", "Kellan", "Ruby", "Agatha", "Hylda", "Eriette", "Rowan", "Will", "Ashiok", "Oko").forEach {
            assertTrue(it in told, "Wilds of Eldraine never mentions $it")
        }
    }

    @Test
    fun itLinksToTheOmenpathsAndThunderJunctionAndTheyLinkBack() {
        val links = MagicStory.links(wilds).map { it.substringBefore('/') }
        listOf("the-omenpaths", "thunder-junction", "invasion-of-the-multiverse").forEach {
            assertTrue(it in links, "Wilds of Eldraine does not link to $it: $links")
        }
        listOf("the-omenpaths", "thunder-junction").forEach { slug ->
            val back = MagicStory.links(MagicStory.article(slug)!!).map { it.substringBefore('/') }
            assertTrue("wilds-of-eldraine" in back, "$slug does not link back to Wilds of Eldraine: $back")
        }
    }

    @Test
    fun readOnFromTheOmenpathsIsWildsOfEldraine() {
        assertEquals(wilds, MagicStory.nextArticle(MagicStory.article("the-omenpaths")!!), "Read on from The Omenpaths skips Wilds of Eldraine")
        assertEquals(MagicStory.article("thunder-junction"), MagicStory.nextArticle(wilds))
    }
}
