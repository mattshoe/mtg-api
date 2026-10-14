package org.mattshoe.mtg.core

/** A line that exists in official story text or on a card, and where it is from. */
data class StoryQuote(val words: String, val source: String)

/**
 * One tab of an article: a short label, and the telling.
 *
 * A paragraph may carry two kinds of markup and nothing else.
 * `[words](target)` links to another article, `the-war` or
 * `the-war/kroog`, or to a person, `who/urza`. `{Card Name}` is a
 * real card, named exactly as it is printed. [MagicStory.spans] reads
 * both, so the shells never parse anything.
 */
data class StoryTab(val slug: String, val tab: String, val story: List<String>, val quote: StoryQuote? = null)

/** Where a part of the story is told. `kind` is one of [MagicStory.SOURCE_KINDS]. */
data class StorySource(val kind: String, val name: String)

/** One story, told as tabs in the order it happens, with where it is told at the end. */
data class StoryArticle(val slug: String, val title: String, val tabs: List<StoryTab>, val sources: List<StorySource>)

/** A run of the story: a title, one sentence that hooks, and its articles in order. */
data class StorySection(val slug: String, val title: String, val hook: String, val articles: List<StoryArticle>)

/**
 * Somebody who turns up across the story.
 *
 * `names` are what the narration calls them, so the articles they
 * appear in are found by reading, not kept by hand: a list kept by
 * hand is a list that goes stale the first time an article is edited.
 */
data class StoryPerson(val slug: String, val name: String, val names: List<String>, val profile: List<String>)

/** A run of words: plain, a link to [target], or a card's name. */
data class StorySpan(val text: String, val target: String? = null, val card: Boolean = false)

/** What a section's tile is showing. */
sealed interface StoryPage {
    /** The section whose tile this is. */
    val section: StorySection

    /** An article, open as a tab of its section. */
    data class Reading(override val section: StorySection, val article: StoryArticle) : StoryPage

    /** A person's profile, reached from [section], and every article they are in. */
    data class Profile(override val section: StorySection, val person: StoryPerson) : StoryPage
}

/**
 * The story of Magic, for the Guide: ten sections, each a run of
 * articles, each article a run of tabs.
 *
 * Matt: "Add Magic the Gathering lore content to the Guide. 10
 * sections, each with the articles listed below. Every article has its
 * own tabs ... Narrate it like literature, not a textbook ... No
 * invented dialogue ... Don't invent lore ... When an article mentions
 * something covered in another article, link to it ... Characters who
 * span sections ... get a short shared profile their names link to,
 * listing every article they appear in."
 *
 * The words are in the `Story*.kt` files beside this one, a section
 * to a file. Both shells draw everything from here.
 */
object MagicStory {

    val SOURCE_KINDS = listOf("Set", "Novel", "Story", "Reference")

    /** A person's address, as a link target: `who/urza`. */
    const val WHO = "who"

    val sections: List<StorySection> = listOf(
        storyBrothersWar,
        storyPhyrexiaOrigins,
        storyWeatherlight,
        storyNewPhyrexia,
        storyPlaneswalkers,
        storyNicolBolas,
        storyRavnica,
        storyEldrazi,
        storyInnistrad,
        storyOmenpaths,
    )

    val people: List<StoryPerson> get() = storyPeople

    val articles: List<StoryArticle> get() = sections.flatMap { it.articles }

    fun article(slug: String): StoryArticle? = articles.firstOrNull { it.slug == slug }

    fun person(slug: String): StoryPerson? = people.firstOrNull { it.slug == slug }

    fun sectionOf(article: StoryArticle): StorySection = sections.first { article in it.articles }

    // Every bracket escaped: JavaScript reads this in unicode mode,
    // where a bare `]` or `}` is a syntax error, and the site died of it.
    private val markup = Regex("""\[([^\]]+)\]\(([^)]+)\)|\{([^}]+)\}""")

    /** A paragraph as runs of plain words, links and card names. */
    fun spans(paragraph: String): List<StorySpan> {
        val out = mutableListOf<StorySpan>()
        var at = 0
        markup.findAll(paragraph).forEach { m ->
            if (m.range.first > at) out += StorySpan(paragraph.substring(at, m.range.first))
            out += if (m.groupValues[1].isNotEmpty()) StorySpan(m.groupValues[1], target = m.groupValues[2])
            else StorySpan(m.groupValues[3], card = true)
            at = m.range.last + 1
        }
        if (at < paragraph.length) out += StorySpan(paragraph.substring(at))
        return out
    }

    /** A paragraph as it reads, with the markup gone. */
    fun plain(paragraph: String): String = spans(paragraph).joinToString("") { it.text }

    /** Every link target an article's narration carries. */
    fun links(article: StoryArticle): Set<String> =
        article.tabs.flatMap { t -> t.story.flatMap { p -> spans(p).mapNotNull { it.target } } }.toSet()

    /** Every card an article names. */
    fun cards(article: StoryArticle): List<String> =
        article.tabs.flatMap { t -> t.story.flatMap { p -> spans(p).filter { it.card }.map { it.text } } }.distinct()

    /** Does this article's narration name this person, linked or not? */
    fun mentions(article: StoryArticle, person: StoryPerson): Boolean {
        val text = article.tabs.joinToString(" ") { t -> t.story.joinToString(" ") { plain(it) } }
        return person.names.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
    }

    /** Every article a person appears in, in story order. */
    fun appearances(person: StoryPerson): List<StoryArticle> = articles.filter { mentions(it, person) }

    fun section(slug: String): StorySection? = sections.firstOrNull { it.slug == slug }

    /** The page a link target opens from [section], or null when it names nothing. */
    fun page(section: StorySection, target: String): StoryPage? = null

    /** A person's link target. */
    fun target(person: StoryPerson): String = "$WHO/${person.slug}"

    /** The article after this one, across sections, or null at the very end. */
    fun nextArticle(article: StoryArticle): StoryArticle? = articles.getOrNull(articles.indexOf(article) + 1)
}

/** What a section's tile shows, or null anywhere else. */
val AppState.storyPage: StoryPage? get() = null

/** A link target, at its own address. */
fun AppState.openStory(target: String): AppState = this
