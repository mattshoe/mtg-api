package org.mattshoe.mtg.core

/**
 * One article a Guide search found: its title, what it is part of,
 * a line of it around the first word searched, and where it is,
 * after `#/guide/`.
 */
data class GuideHit(val title: String, val part: String, val snippet: String, val address: String)

/**
 * Searching the Guide's articles.
 *
 * Matt: "i want to be able to search guide articles on the Guide page
 * ... searching should show any articles which contain the keywords on
 * the search bar". An article is a Phyrexia era or one of the story's
 * articles. It is found when every word typed is somewhere in it, in
 * any capitals, and the hits come in the order the Guide tells them.
 */
object GuideSearch {

    /** How much of an article a hit shows either side of the word. */
    private const val AROUND = 70

    private class Article(val title: String, val part: String, val address: String, val paragraphs: List<String>) {
        val text = paragraphs.joinToString(" ")
    }

    private val articles: List<Article> by lazy {
        PhyrexianLore.eras.map { e ->
            Article(e.tab, "Phyrexia", "${Guide.LORE}/${e.slug}", listOf(e.tab, e.title) + e.story + listOfNotNull(e.quote?.words))
        } + MagicStory.sections.flatMap { s ->
            s.articles.map { a ->
                Article(
                    a.title, s.title, "${s.slug}/${a.slug}",
                    listOf(a.title) + a.tabs.flatMap { t -> listOf(t.tab) + t.story.map(MagicStory::plain) + listOfNotNull(t.quote?.words) },
                )
            }
        }
    }

    /** The words typed, without the spaces round them. */
    fun words(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun search(query: String): List<GuideHit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return articles
            .filter { a -> words.all { a.text.contains(it, ignoreCase = true) } }
            .map { GuideHit(it.title, it.part, snippet(it, words.first()), it.address) }
    }

    /** The line above the hits. */
    fun count(n: Int): String = if (n == 1) "1 article" else "$n articles"

    /** What the page says when nothing has every word. */
    fun none(query: String): String = "No article mentions \"${words(query).joinToString(" ")}\"."

    /** The first paragraph with the word in it, cut down to the stretch round it. */
    private fun snippet(article: Article, word: String): String {
        val p = article.paragraphs.first { it.contains(word, ignoreCase = true) }
        val at = p.indexOf(word, ignoreCase = true)
        val from = (at - AROUND).coerceAtLeast(0)
        val to = (at + word.length + AROUND).coerceAtMost(p.length)
        return (if (from > 0) "…" else "") + p.substring(from, to).trim() + (if (to < p.length) "…" else "")
    }
}

/** What the Guide's search found, in the order the Guide tells it. */
val AppState.guideHits: List<GuideHit> get() = GuideSearch.search(guideSearch)

/** Whether anything is typed in the search bar, so the tiles give way to what it found. */
val AppState.guideSearching: Boolean get() = GuideSearch.words(guideSearch).isNotEmpty()

fun AppState.searchGuide(query: String): AppState = copy(guideSearch = query)

/** A hit, at its article's own address. Back to the Guide finds the search still there. */
fun AppState.openGuideHit(hit: GuideHit): AppState = navigate(Route(View.GUIDE, hit.address))
