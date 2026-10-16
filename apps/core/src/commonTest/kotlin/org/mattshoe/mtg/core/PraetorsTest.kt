package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Matt: "Please add the details of the 5 phyrexian praetors and their
 * respective colors. in the elesh norn section, add her work with
 * Atraxa"
 *
 * The Praetors article gives each praetor a part of their own, straight
 * after Five Suns, each telling their colour and their faction. Elesh
 * Norn's tells how she made Atraxa, and so does her profile.
 */
class PraetorsTest {

    private val praetors get() = assertNotNull(MagicStory.article("praetors"), "there is no Praetors article")

    private fun tab(name: String) = assertNotNull(praetors.tabs.firstOrNull { it.tab == name }, "the Praetors article has no $name part: ${praetors.tabs.map { it.tab }}")

    private fun told(t: StoryTab) = t.story.joinToString(" ") { MagicStory.plain(it) }

    private fun cards(t: StoryTab) = t.story.flatMap { MagicStory.spans(it) }.filter { it.card }.map { it.text }

    private val colours = listOf(
        Triple("Elesh Norn", "white", "Machine Orthodoxy"),
        Triple("Jin-Gitaxias", "blue", "Progress Engine"),
        Triple("Sheoldred", "black", "Steel Thanes"),
        Triple("Urabrask", "red", "Quiet Furnace"),
        Triple("Vorinclex", "green", "Vicious Swarm"),
    )

    @Test
    fun eachPraetorHasAPartOfTheirOwnAfterFiveSuns() {
        val tabs = praetors.tabs.map { it.tab }
        val at = tabs.indexOf("Five Suns")
        assertEquals(colours.map { it.first }, tabs.drop(at + 1).take(5), "the five praetors do not follow Five Suns, one part each: $tabs")
        assertEquals("Mother of Machines", tabs.getOrNull(at + 6), "Mother of Machines no longer follows the praetors: $tabs")
    }

    @Test
    fun eachPraetorsPartSaysTheirColourAndTheirFaction() {
        colours.forEach { (name, colour, faction) ->
            val text = told(tab(name))
            assertTrue(Regex("\\b$colour\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), "$name's part never says $name is $colour")
            assertTrue(faction in text, "$name's part never names the $faction")
            val first = name.substringBefore(' ')
            assertTrue(cards(tab(name)).any { it.startsWith(first) }, "$name's part shows none of $name's cards: ${cards(tab(name))}")
        }
    }

    @Test
    fun eleshNornsPartTellsHerWorkWithAtraxa() {
        val norn = tab("Elesh Norn")
        val text = told(norn)
        assertTrue("Atraxa" in text, "Elesh Norn's part never mentions Atraxa")
        assertTrue("Atraxa, Praetors' Voice" in cards(norn), "Elesh Norn's part does not show Atraxa, Praetors' Voice: ${cards(norn)}")
        assertTrue("Atraxa, Grand Unifier" in cards(norn), "Elesh Norn's part does not show Atraxa, Grand Unifier: ${cards(norn)}")
        assertTrue("Urabrask" in text && Regex("\\bred\\b").containsMatchIn(text), "Elesh Norn's part does not say why Atraxa has no red in her")
    }

    @Test
    fun eleshNornsProfileTellsHerWorkWithAtraxa() {
        val norn = assertNotNull(MagicStory.person("elesh-norn"), "Elesh Norn has no profile")
        val text = norn.profile.joinToString(" ") { MagicStory.plain(it) }
        assertTrue("Atraxa" in text, "Elesh Norn's profile never mentions Atraxa: $text")
        assertTrue("white" in text, "Elesh Norn's profile never says she is the white praetor: $text")
    }
}
