package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.ColourWheel
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideTile
import org.mattshoe.mtg.core.LoreEra
import org.mattshoe.mtg.core.PhyrexianLore
import org.mattshoe.mtg.core.Rule
import org.mattshoe.mtg.core.Rules
import org.mattshoe.mtg.core.MagicStory
import org.mattshoe.mtg.core.StoryArticle
import org.mattshoe.mtg.core.StoryPage
import org.mattshoe.mtg.core.StoryPerson
import org.mattshoe.mtg.core.StoryTab
import org.mattshoe.mtg.core.WheelLore
import org.mattshoe.mtg.core.WheelPair

/**
 * Tips and references, on the web. Sibling of `GuideScreen`.
 *
 * The front page is a grid of tiles; a tile is a page of its own at
 * `#/guide/<slug>`. Everything on it is in `Guide`, so the phone and
 * the site cannot list different names for the same colours.
 */
@Composable
fun GuidePage(
    tile: GuideTile?,
    onOpen: (GuideTile) -> Unit,
    onBack: () -> Unit,
    era: LoreEra? = null,
    onEra: (LoreEra) -> Unit = {},
    story: StoryPage? = null,
    onStory: (String) -> Unit = {},
    rules: Rules = Rules(),
    onRules: (Rules) -> Unit = {},
    onLoadRules: () -> Unit = {},
) {
    Div(attrs = { classes("wrap", "guide") }) {
        if (tile == null) {
            Div(attrs = { classes("guide-tiles") }) {
                Guide.tiles.forEach { t ->
                    Button(attrs = {
                        classes("guide-tile")
                        onClick { onOpen(t) }
                    }) {
                        // The five colours, as a strip of light across
                        // the top: decoration, so the words carry it.
                        Div(attrs = {
                            classes("guide-band")
                            style { property("background-image", band("WUBRG")) }
                        }) {}
                        Div(attrs = { classes("guide-tile-title") }) { Text(t.title) }
                        Div(attrs = { classes("guide-tile-blurb") }) { Text(t.blurb) }
                    }
                }
            }
        } else {
            // Inside an article or a profile, the way out is up to the
            // story's sections, which `Story` draws, not the tiles.
            if (story == null || story == StoryPage.Index) {
                Button(attrs = {
                    classes("btn", "guide-back")
                    onClick { onBack() }
                }) { Text("‹ Guide") }
            }
            when (tile.slug) {
                Guide.COLOURS -> Colours()
                Guide.WHEEL -> Wheel()
                Guide.LORE -> Lore(era ?: PhyrexianLore.eras.first(), onEra)
                Guide.STORY -> Story(story ?: StoryPage.Index, onStory)
                Guide.RULES -> RulesSearch(rules, onRules, onLoadRules)
            }
        }
    }
}

/**
 * Matt: "just a list of color combos and names, in wubrg order, so
 * they can easily be scanned to find what i need". The groups and
 * what they mean follow underneath, for reading rather than finding.
 */
@Composable
private fun Colours() {
    Div(attrs = { classes("combo-list") }) {
        Guide.list.forEach { ComboRow(it) }
    }
    Guide.colours.forEach { group ->
        Section(attrs = { classes("guide-group") }) {
            H2(attrs = { classes("guide-group-title") }) { Text(group.title) }
            Div(attrs = { classes("guide-group-about") }) { Text(group.about) }
            group.combos.forEach { combo ->
                Div(attrs = { classes("guide-group-line") }) {
                    Span(attrs = { classes("combo-letters") }) { Text(combo.pips) }
                    Text(combo.name)
                    if (combo.note.isNotEmpty()) {
                        Span(attrs = { classes("combo-note") }) { Text(combo.note) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboRow(combo: Combo) {
    Div(attrs = {
        classes("combo-row")
        attr("data-pips", combo.pips)
    }) {
        Div(attrs = { classes("combo-pips") }) {
            combo.pips.forEach { ManaPip(it.toString(), "combo-pip") }
        }
        Span(attrs = { classes("combo-name") }) { Text(combo.name) }
        Span(attrs = { classes("combo-letters") }) { Text(combo.pips) }
    }
}

/**
 * The colour wheel: what it is, the wheel drawn, then each colour and
 * each pair. Everything said is in `ColourWheel`.
 */
@Composable
private fun Wheel() {
    Div(attrs = { classes("wheel-intro") }) {
        ColourWheel.intro.forEach { P { Text(it) } }
    }
    Div(attrs = { classes("wheel") }) {
        // Allies joined by a solid line round the outside, enemies by a
        // dashed one across the middle: told apart by the dash, not by
        // a hue, because Matt is colourblind.
        Div(attrs = {
            classes("wheel-lines")
            ref { it.innerHTML = lines(); onDispose { } }
        }) {}
        ColourWheel.colours.forEach { c ->
            val (x, y) = ColourWheel.point(c.pip)
            Div(attrs = {
                classes("wheel-spoke")
                attr("data-pip", c.pip)
                style {
                    property("left", "${50 + x * RADIUS}%")
                    property("top", "${50 + y * RADIUS}%")
                }
            }) {
                ManaPip(c.pip)
                Span(attrs = { classes("wheel-spoke-name") }) { Text(c.name) }
            }
        }
    }
    Div(attrs = { classes("wheel-key") }) {
        Span(attrs = { classes("wheel-key-ally") }) { Text("Solid: allies, side by side") }
        Span(attrs = { classes("wheel-key-enemy") }) { Text("Dashed: enemies, across") }
    }

    Section(attrs = { classes("guide-group") }) {
        H2(attrs = { classes("guide-group-title") }) { Text("The five colours") }
        Div(attrs = { classes("guide-group-about") }) { Text("Clockwise from the top.") }
        Div(attrs = { classes("wheel-colours") }) {
            ColourWheel.colours.forEach { c ->
                Div(attrs = {
                    classes("wheel-colour")
                    attr("data-pip", c.pip)
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band(c.pip)) }
                    }) {}
                    Div(attrs = { classes("wheel-colour-head") }) {
                        ManaPip(c.pip)
                        Span(attrs = { classes("wheel-colour-name") }) { Text(c.name) }
                    }
                    Said("Wants", c.wants, "wheel-wants")
                    Said("Good at", c.goodAt, "wheel-good")
                    Said("Bad at", c.badAt, "wheel-bad")
                    Said("Allies", ColourWheel.alliesOf(c.pip), "wheel-allies")
                    Said("Enemies", ColourWheel.enemiesOf(c.pip), "wheel-enemies")
                }
            }
        }
    }
    Pairs("Allied pairs", "Side by side on the wheel. They want similar things.", ColourWheel.allied)
    Pairs("Enemy pairs", "Across the wheel. They clash, and a deck of both gets the best of each.", ColourWheel.enemy)
    WheelLores(ColourWheel.SHARDS, ColourWheel.SHARDS_ABOUT, ColourWheel.shards)
    WheelLores(ColourWheel.CLANS, ColourWheel.CLANS_ABOUT, ColourWheel.clans)
}

/**
 * Matt: "lore inserts for the shards of alara and the tribes of
 * tarkir ... the land scapes and culture with the missing colors of
 * magic." A card each, everything said in `ColourWheel`. Sibling of
 * the phone's `WheelLores` in `GuideScreen`.
 */
@Composable
private fun WheelLores(title: String, about: String, lore: List<WheelLore>) {
    Section(attrs = { classes("guide-group") }) {
        H2(attrs = { classes("guide-group-title") }) { Text(title) }
        Div(attrs = { classes("guide-group-about") }) { Text(about) }
        Div(attrs = { classes("wheel-lores") }) {
            lore.forEach { l ->
                Div(attrs = {
                    classes("wheel-lore")
                    attr("data-pips", l.pips)
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band(l.pips)) }
                    }) {}
                    Div(attrs = { classes("wheel-lore-head") }) {
                        Div(attrs = { classes("combo-pips") }) {
                            l.pips.forEach { ManaPip(it.toString(), "combo-pip") }
                        }
                        Span(attrs = { classes("wheel-lore-name") }) { Text(l.name) }
                    }
                    Div(attrs = { classes("wheel-lore-missing") }) { Text(ColourWheel.lacking(l.pips)) }
                    Said("The land", l.land, "wheel-lore-land")
                    Said("The people", l.people, "wheel-lore-people")
                    Said("Without them", l.without, "wheel-lore-without")
                }
            }
        }
    }
}

@Composable
private fun Said(label: String, words: String, cls: String) {
    Div(attrs = { classes("wheel-said") }) {
        Span(attrs = { classes("wheel-said-label") }) { Text(label) }
        Span(attrs = { classes(cls) }) { Text(words) }
    }
}

@Composable
private fun Pairs(title: String, about: String, pairs: List<WheelPair>) {
    Section(attrs = { classes("guide-group") }) {
        H2(attrs = { classes("guide-group-title") }) { Text(title) }
        Div(attrs = { classes("guide-group-about") }) { Text(about) }
        Div(attrs = { classes("wheel-pairs") }) {
            pairs.forEach { p ->
                Div(attrs = {
                    classes("wheel-pair")
                    attr("data-pips", p.pips)
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band(p.pips)) }
                    }) {}
                    Div(attrs = { classes("combo-pips") }) {
                        p.pips.forEach { ManaPip(it.toString(), "combo-pip") }
                    }
                    Div(attrs = { classes("wheel-pair-name") }) { Text(p.name) }
                    Div(attrs = { classes("combo-note") }) { Text(p.why) }
                }
            }
        }
    }
}

/**
 * The story of Phyrexia: a row of tabs, one per era, then the era's
 * chapter set for reading. Everything said is in `PhyrexianLore`, and
 * the open tab is in the address. Sibling of `Lore` in `GuideScreen`.
 */
@Composable
private fun Lore(era: LoreEra, onEra: (LoreEra) -> Unit) {
    Div(attrs = {
        classes("lore-tabs")
        attr("role", "tablist")
    }) {
        PhyrexianLore.eras.forEach { e ->
            Button(attrs = {
                classes("lore-tab")
                attr("role", "tab")
                attr("aria-selected", (e == era).toString())
                onClick { onEra(e) }
            }) { Text(e.tab) }
        }
    }
    Div(attrs = { classes("lore-story") }) {
        H2(attrs = { classes("lore-title") }) { Text(era.title) }
        era.story.forEach { P { Text(it) } }
        era.quote?.let { q ->
            Div(attrs = { classes("lore-quote") }) {
                Div(attrs = { classes("lore-quote-words") }) { Text(q.words) }
                Div(attrs = { classes("lore-quote-source") }) { Text(q.source) }
            }
        }
        PhyrexianLore.next(era)?.let { next ->
            Button(attrs = {
                classes("btn", "lore-next")
                onClick {
                    onEra(next)
                    kotlinx.browser.window.scrollTo(0.0, 0.0)
                }
            }) { Text("Read on: ${next.tab} ›") }
        }
    }
}

/**
 * The story of Magic: the ten sections, an article in tabs, or a
 * person's profile. Everything said is in `MagicStory`, and where you
 * are is in the address. Sibling of `Story` in `GuideScreen`.
 */
@Composable
private fun Story(page: StoryPage, onStory: (String) -> Unit) {
    when (page) {
        StoryPage.Index -> StoryIndex(onStory)
        is StoryPage.Reading -> StoryReading(page.article, page.tab, onStory)
        is StoryPage.Profile -> StoryProfile(page.person, onStory)
    }
}

@Composable
private fun StoryIndex(onStory: (String) -> Unit) {
    Div(attrs = { classes("story-index") }) {
        MagicStory.sections.forEachIndexed { i, s ->
            Section(attrs = {
                classes("story-section")
                attr("data-section", s.slug)
            }) {
                Div(attrs = { classes("story-section-number") }) { Text("${i + 1}") }
                H2(attrs = { classes("story-section-title") }) { Text(s.title) }
                Div(attrs = { classes("story-hook") }) { Text(s.hook) }
                Div(attrs = { classes("story-articles") }) {
                    s.articles.forEach { a ->
                        Button(attrs = {
                            classes("story-article")
                            attr("data-article", a.slug)
                            onClick { onStory(a.slug) }
                        }) { Text(a.title) }
                    }
                }
            }
        }
    }
}

/** Up to the sections, from an article or a profile. */
@Composable
private fun StoryUp(onStory: (String) -> Unit) {
    Button(attrs = {
        classes("btn", "story-up")
        onClick { onStory("") }
    }) { Text("‹ The story of Magic") }
}

/**
 * A paragraph with its links and cards. A link is a real `a` with a
 * real address, so it can be opened in a new tab, but a tap stays in
 * the app. Links are underlined, not coloured, because Matt is
 * colourblind.
 */
@Composable
private fun StoryParagraph(paragraph: String, onStory: (String) -> Unit) {
    P {
        MagicStory.spans(paragraph).forEach { s ->
            val target = s.target
            when {
                target != null -> A(href = "#/guide/${Guide.STORY}/$target", attrs = {
                    classes("lore-link")
                    attr("data-target", target)
                    onClick {
                        it.preventDefault()
                        onStory(target)
                        kotlinx.browser.window.scrollTo(0.0, 0.0)
                    }
                }) { Text(s.text) }
                s.card -> Span(attrs = { classes("lore-card") }) { Text(s.text) }
                else -> Text(s.text)
            }
        }
    }
}

@Composable
private fun StoryReading(article: StoryArticle, tab: StoryTab, onStory: (String) -> Unit) {
    Div(attrs = { classes("story-head") }) {
        StoryUp(onStory)
        Div(attrs = { classes("story-kicker") }) { Text(MagicStory.sectionOf(article).title) }
        H2(attrs = { classes("story-article-title") }) { Text(article.title) }
    }
    Div(attrs = {
        classes("lore-tabs")
        attr("role", "tablist")
    }) {
        article.tabs.forEach { t ->
            Button(attrs = {
                classes("lore-tab")
                attr("role", "tab")
                attr("aria-selected", (t == tab).toString())
                onClick { onStory(MagicStory.target(article, t)) }
            }) { Text(t.tab) }
        }
    }
    Div(attrs = { classes("lore-story") }) {
        tab.story.forEach { StoryParagraph(it, onStory) }
        tab.quote?.let { q ->
            Div(attrs = { classes("lore-quote") }) {
                Div(attrs = { classes("lore-quote-words") }) { Text(q.words) }
                Div(attrs = { classes("lore-quote-source") }) { Text(q.source) }
            }
        }
        val nextTab = MagicStory.next(article, tab)
        val nextArticle = MagicStory.nextArticle(article)
        val (label, target) = when {
            nextTab != null -> "Read on: ${nextTab.tab} ›" to MagicStory.target(article, nextTab)
            nextArticle != null -> "Next: ${nextArticle.title} ›" to nextArticle.slug
            else -> null to null
        }
        if (label != null && target != null) {
            Button(attrs = {
                classes("btn", "lore-next")
                onClick {
                    onStory(target)
                    kotlinx.browser.window.scrollTo(0.0, 0.0)
                }
            }) { Text(label) }
        }
        Section(attrs = { classes("story-sources") }) {
            H2(attrs = { classes("story-sources-title") }) { Text("Where it is told") }
            article.sources.forEach { s ->
                Div(attrs = { classes("story-source") }) {
                    Span(attrs = { classes("story-source-kind") }) { Text(s.kind) }
                    Span(attrs = { classes("story-source-name") }) { Text(s.name) }
                }
            }
        }
    }
}

@Composable
private fun StoryProfile(person: StoryPerson, onStory: (String) -> Unit) {
    Div(attrs = { classes("story-head") }) { StoryUp(onStory) }
    Div(attrs = { classes("lore-story", "story-profile") }) {
        H2(attrs = { classes("lore-title") }) { Text(person.name) }
        person.profile.forEach { StoryParagraph(it, onStory) }
        Section(attrs = { classes("story-sources") }) {
            H2(attrs = { classes("story-sources-title") }) { Text("Appears in") }
            MagicStory.appearances(person).forEach { a ->
                Button(attrs = {
                    classes("story-appears")
                    attr("data-article", a.slug)
                    onClick {
                        onStory(a.slug)
                        kotlinx.browser.window.scrollTo(0.0, 0.0)
                    }
                }) {
                    Span(attrs = { classes("story-source-kind") }) { Text(MagicStory.sectionOf(a).title) }
                    Span(attrs = { classes("story-source-name") }) { Text(a.title) }
                }
            }
        }
    }
}

/** How far out the symbols sit, as a percentage of the wheel's width. */
private const val RADIUS = 38.0

/** The ten lines between the five colours, as SVG on a 100 by 100 box. */
private fun lines(): String {
    val at = ColourWheel.colours.map { c -> ColourWheel.point(c.pip).let { (x, y) -> (50 + x * RADIUS) to (50 + y * RADIUS) } }
    fun line(a: Int, b: Int, cls: String) =
        "<line class=\"$cls\" x1=\"${at[a].first}\" y1=\"${at[a].second}\" x2=\"${at[b].first}\" y2=\"${at[b].second}\"/>"
    val allies = (0 until 5).joinToString("") { line(it, (it + 1) % 5, "wheel-ally") }
    val enemies = (0 until 5).joinToString("") { line(it, (it + 2) % 5, "wheel-enemy") }
    return "<svg viewBox=\"0 0 100 100\">$allies$enemies</svg>"
}

/**
 * The Comprehensive Rules, searched as you type. Sibling of Android's
 * `RulesSearch`.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default". The file is fetched once, through the Worker,
 * and `RuleBook` parses and searches it here, on the device.
 */
@Composable
private fun RulesSearch(rules: Rules, onRules: (Rules) -> Unit, onLoad: () -> Unit) {
    Div(attrs = { classes("rules-bar") }) {
        Input(InputType.Search, attrs = {
            classes("rules-search")
            placeholder("Search the rules: trample, 702.19, commander tax")
            attr("aria-label", "Search the rules")
            value(rules.query)
            onInput { onRules(rules.searched(it.value)) }
        })
    }
    val book = rules.book
    when {
        rules.busy -> Div(attrs = { classes("empty", "rules-loading") }) { Text("Loading the Comprehensive Rules…") }
        rules.error != null -> Div(attrs = { classes("rules-failed") }) {
            Div(attrs = { classes("err") }) { Text("Could not load the rules: ${rules.error}") }
            Button(attrs = {
                classes("btn", "rules-retry")
                onClick { onLoad() }
            }) { Text("Try again") }
        }
        book == null -> Div(attrs = { classes("empty") }) { Text("The rules have not loaded yet.") }
        rules.query.isBlank() -> Div(attrs = { classes("empty", "rules-hint") }) {
            Text("Type a word or a rule number to find every rule that mentions it.")
            if (book.effective.isNotEmpty()) Div(attrs = { classes("muted", "small") }) { Text(book.effective) }
        }
        rules.total == 0 -> Div(attrs = { classes("empty") }) { Text("No rule mentions \"${rules.query.trim()}\".") }
        else -> {
            Div(attrs = { classes("muted", "small", "rules-count") }) { Text(rules.count) }
            Div(attrs = { classes("rules-list") }) {
                rules.hits.forEach { r -> RuleCard(r, rules.isOpen(r)) { onRules(rules.toggled(r)) } }
            }
        }
    }
}

/** One rule: its number as the heading, a tap on that to fold it, then its text and examples. */
@Composable
private fun RuleCard(rule: Rule, open: Boolean, onToggle: () -> Unit) {
    Div(attrs = {
        classes("rule-card")
        attr("data-rule", rule.number)
        attr("data-open", open.toString())
    }) {
        Button(attrs = {
            classes("rule-head")
            attr("aria-expanded", open.toString())
            onClick { onToggle() }
        }) {
            Span(attrs = { classes("rule-number") }) { Text(rule.shownNumber) }
            Span(attrs = { classes("rule-section") }) { Text(rule.section) }
            Span(attrs = { classes("rule-chevron") }) { Text(if (open) "▾" else "▸") }
        }
        if (open) {
            Div(attrs = { classes("rule-body") }) {
                Div(attrs = { classes("rule-text") }) { Text(rule.text) }
                rule.examples.forEach { e -> Div(attrs = { classes("rule-example") }) { Text(e) } }
            }
        }
    }
}

/** The colours left to right, each its own stretch of the strip. */
private fun band(pips: String): String {
    val colours = pips.map { Design.css(Design.pip(it.toString())) }
    if (colours.size == 1) return "linear-gradient(90deg, ${colours[0]}, ${colours[0]})"
    return "linear-gradient(90deg, ${colours.joinToString(", ")})"
}
