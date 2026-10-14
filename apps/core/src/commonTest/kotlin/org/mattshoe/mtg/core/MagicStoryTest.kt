package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The story of Magic, as a Guide tile of sections, articles and tabs.
 *
 * Matt: "Add Magic the Gathering lore content to the Guide. 10
 * sections, each with the articles listed below. Every article has its
 * own tabs." The section and article names below are his, word for word.
 */
class MagicStoryTest {

    private val tile: GuideTile
        get() = Guide.tile(Guide.STORY) ?: error("the Guide has no lore tile: ${Guide.tiles.map { it.slug }}")

    private val asked = listOf(
        "Brothers' War" to listOf("The Thran", "Koilos", "The War", "Argoth", "Sylex Blast", "Ice Age"),
        "Phyrexia's Origins" to listOf("Halcyon", "Thran Phyrexian War", "Yawgmoth's Spheres", "Rath"),
        "Weatherlight Saga" to listOf("The Legacy", "Rath", "Mercadia", "Invasion", "Apocalypse"),
        "New Phyrexia" to listOf("Argentum", "Memnarch", "Scars", "Praetors", "Invasion of the Multiverse"),
        "Planeswalkers" to listOf("Oldwalkers", "Time Rifts", "The Mending", "Gatewatch", "Sparks Lost"),
        "Nicol Bolas" to listOf("Elder Dragons", "Alara", "Tarkir", "Amonkhet", "War of the Spark"),
        "Ravnica" to listOf("Guildpact", "The Breaking", "Implicit Maze", "War of the Spark", "Karlov Manor"),
        "Eldrazi and Zendikar" to listOf("The Binding", "Rise", "Battle for Zendikar", "Emrakul on Innistrad"),
        "Innistrad" to listOf("Helvault", "Avacyn Restored", "Shadows", "Eldritch Moon", "Eternal Night"),
        "Omenpath Era" to listOf(
            "The Omenpaths", "Thunder Junction", "Bloomburrow", "Duskmourn", "Aetherdrift", "Tarkir Dragonstorm", "Edge of Eternities",
        ),
    )

    private fun titled(section: String, article: String): StoryArticle =
        MagicStory.sections.firstOrNull { it.title == section }?.articles?.firstOrNull { it.title == article }
            ?: error("there is no $article in $section")

    private fun linksTo(from: StoryArticle, to: StoryArticle) =
        MagicStory.links(from).any { it.substringBefore('/') == to.slug }

    private val text: (StoryArticle) -> String = { a -> a.tabs.joinToString(" ") { t -> t.story.joinToString(" ") { MagicStory.plain(it) } } }

    @Test
    fun theStoryIsATileOfItsOwnAtItsOwnAddress() {
        assertTrue(tile.blurb.isNotBlank(), "the tile says nothing under its title")
        val open = AppState().navigate(View.GUIDE).openGuide(tile)
        assertEquals("#/guide/lore", open.route.toHash())
        assertEquals(StoryPage.Index, open.storyPage, "opening the tile does not land on the sections")
        assertEquals(tile.title, open.title, "the header does not name the page")
    }

    @Test
    fun theTenSectionsAndTheirArticlesAreTheOnesAskedFor() {
        assertEquals(asked.map { it.first }, MagicStory.sections.map { it.title }, "the sections are not the ten asked for, in order")
        asked.forEach { (section, articles) ->
            val got = MagicStory.sections.first { it.title == section }.articles.map { it.title }
            assertEquals(articles, got, "$section does not have the articles asked for, in order")
        }
    }

    @Test
    fun everySectionHasAOneSentenceHook() {
        MagicStory.sections.forEach { s ->
            assertTrue(s.hook.isNotBlank(), "${s.title} has no hook")
            assertTrue(s.hook.trim().last() in ".?", "${s.title}'s hook is not a sentence: ${s.hook}")
            val stops = Regex("[.?](\\s|$)").findAll(s.hook.trim()).count()
            assertEquals(1, stops, "${s.title}'s hook is $stops sentences: ${s.hook}")
        }
    }

    @Test
    fun everyAddressIsUniqueAndAnAddress() {
        val slugs = MagicStory.articles.map { it.slug } + MagicStory.sections.map { it.slug }
        assertTrue(MagicStory.articles.size >= 48, "${MagicStory.articles.size} articles, which is not all of them")
        assertEquals(slugs.size, slugs.toSet().size, "two things share an address: ${slugs.groupBy { it }.filter { it.value.size > 1 }.keys}")
        MagicStory.articles.forEach { a ->
            assertTrue(a.slug.matches(Regex("[a-z0-9-]+")) && a.slug != MagicStory.WHO, "${a.slug} is not an address")
            val tabs = a.tabs.map { it.slug }
            assertEquals(tabs.size, tabs.toSet().size, "${a.title} has two tabs at one address: $tabs")
            tabs.forEach { assertTrue(it.matches(Regex("[a-z0-9-]+")), "${a.title}'s tab $it is not an address") }
        }
    }

    @Test
    fun everyArticleHasItsOwnTabsInStoryOrderNotAGenericSet() {
        val generic = setOf("overview", "background", "introduction", "summary", "conclusion", "aftermath", "epilogue", "prologue", "origins", "legacy", "the end")
        MagicStory.articles.forEach { a ->
            assertTrue(a.tabs.size >= 2, "${a.title} has ${a.tabs.size} tab, so it is not tabbed")
            a.tabs.forEach { t ->
                assertTrue(t.tab.isNotBlank() && t.tab.length <= 24, "${a.title}'s tab is ${t.tab.length} characters: ${t.tab}")
                assertTrue(t.tab.lowercase() !in generic, "${a.title} has a generic tab, ${t.tab}, not one picked for its story")
            }
        }
        val sets = MagicStory.articles.groupBy { a -> a.tabs.map { it.tab }.toSet() }.filter { it.value.size > 1 }
        assertTrue(sets.isEmpty(), "articles reuse one set of tabs: ${sets.values.map { v -> v.map { it.title } }}")
        val labels = MagicStory.articles.flatMap { a -> a.tabs.map { it.tab } }.groupBy { it }.filter { it.value.size > 2 }.keys
        assertTrue(labels.isEmpty(), "the same tab turns up in three or more articles, which is a template: $labels")
    }

    @Test
    fun everyTabReadsAsAStoryNotANote() {
        MagicStory.articles.forEach { a ->
            a.tabs.forEach { t ->
                assertTrue(t.story.size >= 2, "${a.title} / ${t.tab} is ${t.story.size} paragraph")
                val words = t.story.sumOf { MagicStory.plain(it).split(Regex("\\s+")).size }
                assertTrue(words >= 150, "${a.title} / ${t.tab} is $words words, a note rather than a telling")
                t.story.forEach { assertTrue(it.isNotBlank(), "${a.title} / ${t.tab} has an empty paragraph") }
            }
        }
    }

    @Test
    fun theNarrationInventsNoDialogueAndQuotesOnlyWithASource() {
        MagicStory.articles.forEach { a ->
            a.tabs.forEach { t ->
                t.story.forEach { p ->
                    val plain = MagicStory.plain(p)
                    val bad = plain.filter { it in "\"“”;:!—–[](){}" }
                    assertTrue(bad.isEmpty(), "${a.title} / ${t.tab} has $bad in its narration. A quote goes in the quote, with its source: ${plain.take(120)}")
                }
                t.quote?.let { q ->
                    assertTrue(q.words.isNotBlank(), "${a.title} / ${t.tab} quotes nothing")
                    assertTrue(q.source.isNotBlank(), "\"${q.words}\" does not say where it is from")
                }
            }
        }
    }

    @Test
    fun everyLinkGoesSomewhere() {
        MagicStory.articles.forEach { a ->
            MagicStory.links(a).forEach { target ->
                val page = assertNotNull(MagicStory.page(target), "${a.title} links to $target, which is nothing")
                if (page is StoryPage.Reading) assertTrue(page.article != a, "${a.title} links to itself")
            }
        }
        MagicStory.people.forEach { p ->
            p.profile.flatMap { MagicStory.spans(it) }.mapNotNull { it.target }.forEach {
                assertNotNull(MagicStory.page(it), "${p.name}'s profile links to $it, which is nothing")
            }
        }
    }

    @Test
    fun theStoriesMattNamedLinkToEachOtherBothWays() {
        val pairs = listOf(
            titled("Phyrexia's Origins", "Rath") to titled("Weatherlight Saga", "Rath"),
            titled("Nicol Bolas", "War of the Spark") to titled("Ravnica", "War of the Spark"),
            titled("New Phyrexia", "Invasion of the Multiverse") to titled("Planeswalkers", "Sparks Lost"),
            titled("New Phyrexia", "Invasion of the Multiverse") to titled("Omenpath Era", "The Omenpaths"),
            titled("Nicol Bolas", "Tarkir") to titled("Omenpath Era", "Tarkir Dragonstorm"),
        )
        pairs.forEach { (a, b) ->
            assertTrue(linksTo(a, b), "${MagicStory.sectionOf(a).title} / ${a.title} does not link to ${MagicStory.sectionOf(b).title} / ${b.title}")
            assertTrue(linksTo(b, a), "${MagicStory.sectionOf(b).title} / ${b.title} does not link to ${MagicStory.sectionOf(a).title} / ${a.title}")
        }
        val emrakul = titled("Eldrazi and Zendikar", "Emrakul on Innistrad")
        val innistrad = MagicStory.sections.first { it.title == "Innistrad" }.articles
        assertTrue(innistrad.any { linksTo(emrakul, it) }, "Emrakul on Innistrad does not link into the Innistrad section")
        assertTrue(innistrad.any { linksTo(it, emrakul) }, "nothing in the Innistrad section links to Emrakul on Innistrad")
    }

    @Test
    fun theStoryLinksAcrossItself() {
        val lonely = MagicStory.articles.filter { a -> MagicStory.links(a).none { !it.startsWith(MagicStory.WHO + "/") } }
        assertTrue(lonely.size <= 4, "${lonely.size} articles link to no other article: ${lonely.map { it.title }}")
    }

    @Test
    fun theCharactersWhoSpanSectionsHaveAProfile() {
        val names = MagicStory.people.map { it.name }
        listOf("Urza", "Yawgmoth", "Karn", "Sorin", "Nicol Bolas", "Ugin", "Nahiri", "Elesh Norn", "Jace").forEach { n ->
            assertTrue(names.any { n in it }, "$n has no profile: $names")
        }
        MagicStory.people.forEach { p ->
            assertTrue(p.slug.matches(Regex("[a-z-]+")), "${p.slug} is not an address")
            assertTrue(p.profile.isNotEmpty() && p.profile.all { it.isNotBlank() }, "${p.name}'s profile says nothing")
            val sections = MagicStory.appearances(p).map { MagicStory.sectionOf(it) }.toSet()
            assertTrue(sections.size >= 2, "${p.name} appears in ${sections.size} section, so does not span sections")
        }
    }

    @Test
    fun everyArticleAPersonAppearsInLinksToTheirProfile() {
        MagicStory.people.forEach { p ->
            MagicStory.appearances(p).forEach { a ->
                assertTrue(
                    "${MagicStory.WHO}/${p.slug}" in MagicStory.links(a),
                    "${a.title} names ${p.name} and never links to their profile",
                )
            }
        }
    }

    @Test
    fun aProfileListsEveryArticleThePersonIsIn() {
        val urza = assertNotNull(MagicStory.people.firstOrNull { it.name.startsWith("Urza") }, "Urza has no profile")
        val seen = MagicStory.appearances(urza).map { it.title }
        listOf("The War", "Sylex Blast", "The Legacy", "Invasion").forEach {
            assertTrue(it in seen, "Urza's profile does not list $it: $seen")
        }
        assertTrue(MagicStory.appearances(urza).all { "Urza" in text(it) }, "Urza's profile lists an article he is not in")
    }

    @Test
    fun everyArticleSaysWhereItIsToldAtTheEnd() {
        MagicStory.articles.forEach { a ->
            assertTrue(a.sources.isNotEmpty(), "${a.title} has no sources")
            a.sources.forEach {
                assertTrue(it.kind in MagicStory.SOURCE_KINDS, "${a.title} has a source of kind ${it.kind}")
                assertTrue(it.name.isNotBlank(), "${a.title} has a ${it.kind} with no name")
            }
            assertTrue(a.sources.any { it.kind != "Reference" }, "${a.title} names no set, novel or story that tells it")
            assertTrue(a.sources.any { it.kind == "Reference" }, "${a.title} says nothing it was checked against")
        }
    }

    @Test
    fun aFewRealCardsShowTheKeyMoments() {
        val withCards = MagicStory.articles.count { MagicStory.cards(it).isNotEmpty() }
        assertTrue(withCards >= MagicStory.articles.size * 3 / 4, "only $withCards of ${MagicStory.articles.size} articles name a card")
    }

    @Test
    fun markupReadsAsLinksAndCards() {
        val spans = MagicStory.spans("Then [Urza](who/urza) set off the {Golgothian Sylex} at [Argoth](argoth).")
        assertEquals(
            listOf(
                StorySpan("Then "), StorySpan("Urza", target = "who/urza"), StorySpan(" set off the "),
                StorySpan("Golgothian Sylex", card = true), StorySpan(" at "), StorySpan("Argoth", target = "argoth"), StorySpan("."),
            ),
            spans,
        )
        assertEquals("Then Urza set off the Golgothian Sylex at Argoth.", MagicStory.plain("Then [Urza](who/urza) set off the {Golgothian Sylex} at [Argoth](argoth)."))
    }

    @Test
    fun anArticleAndATabAndAPersonAreEachTheirOwnAddress() {
        val war = titled("Brothers' War", "The War")
        val second = war.tabs[1]
        val open = AppState().navigate(View.GUIDE).openGuide(tile).openStory(war)
        assertEquals("#/guide/lore/${war.slug}", open.route.toHash())
        assertEquals(StoryPage.Reading(war, war.tabs.first()), open.storyPage)
        assertEquals(tile.title, open.title, "the header stops naming the page inside an article")

        val tabbed = open.openStory(war, second)
        assertEquals("#/guide/lore/${war.slug}/${second.slug}", tabbed.route.toHash())
        assertEquals(StoryPage.Reading(war, second), tabbed.storyPage)
        assertEquals(StoryPage.Reading(war, second), AppState().navigate(Route.parse("#/guide/lore/${war.slug}/${second.slug}")).storyPage, "a bookmark does not land on its tab")

        val urza = MagicStory.people.first { it.name.startsWith("Urza") }
        val who = AppState().navigate(Route.parse("#/guide/lore/who/${urza.slug}"))
        assertEquals(StoryPage.Profile(urza), who.storyPage, "a person's address is not their profile")
    }

    @Test
    fun anAddressThatNamesNothingIsTheSectionsNotABlankPage() {
        assertEquals(StoryPage.Index, AppState().navigate(Route.parse("#/guide/lore/nowhere")).storyPage)
        assertEquals(StoryPage.Index, AppState().navigate(Route.parse("#/guide/lore/who/nobody")).storyPage)
        val war = titled("Brothers' War", "The War")
        assertEquals(StoryPage.Reading(war, war.tabs.first()), AppState().navigate(Route.parse("#/guide/lore/${war.slug}/nowhere")).storyPage, "a stale tab is not its article")
    }

    @Test
    fun noOtherPageHasAStory() {
        assertNull(AppState().navigate(Route.parse("#/guide/phyrexia")).storyPage, "the Phyrexia page has a story page")
        assertNull(AppState().navigate(View.GUIDE).storyPage, "the tiles have a story page")
        assertNull(AppState().storyPage, "the Library has a story page")
    }

    @Test
    fun backFromAnArticleOrAProfileIsTheSectionsAndBackFromThereIsTheTiles() {
        val last = MagicStory.articles.last()
        val deep = AppState().navigate(Route.parse("#/guide/lore/${last.slug}/${last.tabs.last().slug}"))
        val up = assertNotNull(deep.back(), "back from an article closed the app")
        assertEquals("#/guide/lore", up.route.toHash(), "back from an article is not the sections")
        val who = AppState().navigate(Route.parse("#/guide/lore/who/${MagicStory.people.first().slug}"))
        assertEquals("#/guide/lore", who.back()?.route?.toHash(), "back from a profile is not the sections")
        assertEquals("#/guide", up.back()?.route?.toHash(), "back from the sections is not the tiles")
    }

    @Test
    fun readOnGoesTabByTabThenToTheNextArticleAndEndsAtTheEnd() {
        val first = MagicStory.articles.first()
        first.tabs.zipWithNext().forEach { (a, b) -> assertEquals(b, MagicStory.next(first, a), "${first.title}: ${a.tab} does not lead on to ${b.tab}") }
        assertNull(MagicStory.next(first, first.tabs.last()), "the last tab leads on to another tab")
        assertEquals(MagicStory.articles[1], MagicStory.nextArticle(first))
        val last = MagicStory.articles.last()
        assertIs<StoryArticle>(last)
        assertNull(MagicStory.nextArticle(last), "the last article leads somewhere")
    }
}
