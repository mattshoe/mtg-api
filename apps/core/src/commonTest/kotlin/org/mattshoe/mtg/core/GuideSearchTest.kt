package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Searching the Guide's articles.
 *
 * Matt: "i want to be able to search guide articles on the Guide page
 * ... searching should show any articles which contain the keywords on
 * the search bar". An article is a Phyrexia era or one of the story's
 * articles, and it is found when every word typed is somewhere in it.
 */
class GuideSearchTest {

    /** Everything an article says, read here the plain way, so the test does not lean on the search's own reading. */
    private val texts: Map<String, String> =
        PhyrexianLore.eras.associate { e ->
            "${Guide.LORE}/${e.slug}" to (listOf(e.tab, e.title) + e.story + listOfNotNull(e.quote?.words)).joinToString(" ")
        } + MagicStory.sections.flatMap { s ->
            s.articles.map { a ->
                "${s.slug}/${a.slug}" to (listOf(a.title) + a.tabs.flatMap { t ->
                    listOf(t.tab) + t.story.map(MagicStory::plain) + listOfNotNull(t.quote?.words)
                }).joinToString(" ")
            }
        }

    private fun containing(vararg words: String): Set<String> =
        texts.filterValues { t -> words.all { t.contains(it, ignoreCase = true) } }.keys

    @Test
    fun aWordFindsEveryArticleThatHasItAndNoOther() {
        val found = AppState().searchGuide("Sylex").guideHits.map { it.address }.toSet()
        val expected = containing("Sylex")
        assertTrue(expected.size > 3, "the fixture is wrong: only ${expected.size} articles mention the Sylex")
        assertEquals(expected, found, "the articles that mention the Sylex are not what the search found")
        assertTrue("brothers-war/sylex-blast" in found, "Sylex Blast is not found by searching Sylex: $found")
    }

    @Test
    fun aPhyrexiaEraIsAnArticleToo() {
        val hits = AppState().searchGuide("Glacian").guideHits
        val thran = hits.firstOrNull { it.address == "${Guide.LORE}/thran" }
        assertNotNull(thran, "the Phyrexia era that tells of Glacian is not found: ${hits.map { it.address }}")
        assertEquals("Phyrexia", thran.part)
    }

    @Test
    fun everyWordTypedHasToBeInTheArticle() {
        val both = AppState().searchGuide("Yawgmoth Sylex").guideHits.map { it.address }.toSet()
        val onlyYawgmoth = containing("Yawgmoth") - containing("Sylex")
        assertTrue(onlyYawgmoth.isNotEmpty(), "the fixture is wrong: every Yawgmoth article mentions the Sylex")
        assertEquals(containing("Yawgmoth", "Sylex"), both)
        assertTrue(both.none { it in onlyYawgmoth }, "an article without the Sylex was found by \"Yawgmoth Sylex\": ${both.intersect(onlyYawgmoth)}")
    }

    @Test
    fun capitalsDoNotMatter() {
        assertEquals(
            AppState().searchGuide("Sylex").guideHits,
            AppState().searchGuide("  sYLEX ").guideHits,
            "the same word in other capitals found different articles",
        )
    }

    @Test
    fun aHitSaysWhatItIsWhereItIsAndShowsTheWordInContext() {
        val hit = AppState().searchGuide("golgoth").guideHits.first { it.address == "brothers-war/sylex-blast" }
        assertEquals("Sylex Blast", hit.title)
        assertEquals("Brothers' War", hit.part)
        assertTrue(hit.snippet.contains("golgoth", ignoreCase = true), "the snippet does not show the word: ${hit.snippet}")
        assertTrue(hit.snippet.length <= 200, "the snippet is a wall of text, ${hit.snippet.length} characters")
        assertTrue('[' !in hit.snippet && '{' !in hit.snippet, "the snippet shows the markup: ${hit.snippet}")
    }

    @Test
    fun nothingTypedIsNoSearch() {
        assertTrue(AppState().searchGuide("   ").guideHits.isEmpty())
        assertTrue(!AppState().searchGuide("   ").guideSearching)
        assertTrue(AppState().searchGuide("Sylex").guideSearching)
    }

    @Test
    fun aWordNoArticleHasFindsNothing() {
        assertTrue(AppState().searchGuide("xyzzyplugh").guideHits.isEmpty())
    }

    @Test
    fun openingAHitGoesToThatArticleAndBackKeepsTheSearch() {
        val searched = AppState().navigate(View.GUIDE).searchGuide("Sylex")
        val hit = searched.guideHits.first { it.address == "brothers-war/sylex-blast" }
        val opened = searched.openGuideHit(hit)
        val page = opened.storyPage as? StoryPage.Reading ?: error("opening the hit did not open an article: ${opened.route}")
        assertEquals("sylex-blast", page.article.slug)
        val back = opened.navigate(View.GUIDE)
        assertEquals("Sylex", back.guideSearch, "back to the Guide lost what was searched")

        val era = searched.searchGuide("Glacian").guideHits.first { it.address == "${Guide.LORE}/thran" }
        assertEquals("thran", searched.openGuideHit(era).loreEra?.slug)
    }

    @Test
    fun theWordsAboveTheHitsSayHowManyAndTheWordsForNoneSayWhatWasSearched() {
        assertEquals("1 article", GuideSearch.count(1))
        assertEquals("12 articles", GuideSearch.count(12))
        assertEquals("No article mentions \"blue dragon\".", GuideSearch.none("  blue   dragon "))
    }
}
