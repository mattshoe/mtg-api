package org.mattshoe.mtg.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.ColourWheel
import org.mattshoe.mtg.core.Combo
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.GuideHit
import org.mattshoe.mtg.core.GuideSearch
import org.mattshoe.mtg.core.GuideTile
import org.mattshoe.mtg.core.LoreEra
import org.mattshoe.mtg.core.PhyrexianLore
import org.mattshoe.mtg.core.Rule
import org.mattshoe.mtg.core.Rules
import org.mattshoe.mtg.core.MagicStory
import org.mattshoe.mtg.core.StoryArticle
import org.mattshoe.mtg.core.StoryPage
import org.mattshoe.mtg.core.StoryPerson
import org.mattshoe.mtg.core.StorySection
import org.mattshoe.mtg.core.WheelColour
import org.mattshoe.mtg.core.WheelLore
import org.mattshoe.mtg.core.WheelPair

/**
 * Tips and references, on Android. Sibling of `GuidePage`.
 *
 * The front page is a column of tiles; a tile is a page of its own,
 * `#/guide/<slug>` on the site. Everything on it is in `Guide`, so
 * the two cannot list different names for the same colours.
 *
 * A plain scrolling column rather than a lazy grid: thirty-one tiles
 * is nothing, and a lazy grid never composes what is below the fold,
 * which is exactly where a test would go looking.
 */
@Composable
fun GuideScreen(
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
    search: String = "",
    onSearch: (String) -> Unit = {},
    hits: List<GuideHit> = emptyList(),
    onHit: (GuideHit) -> Unit = {},
) {
    // The rules page keeps its search bar on screen and scrolls the
    // cards under it, so it is laid out on its own.
    if (tile?.slug == Guide.RULES) {
        RulesSearch(rules, onRules, onLoadRules, onBack)
        return
    }
    val scroll = rememberScrollState()
    // A new era is a new chapter, so it starts at the top, as the
    // site's Read on does.
    LaunchedEffect(era?.slug, story) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(scroll)
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("guide"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tile == null) {
            GuideFind(search, onSearch, hits, onHit)
            // While something is searched, what it found stands in for the tiles.
            if (GuideSearch.words(search).isEmpty()) Guide.tiles.forEach { t ->
                Card(
                    "WUBRG",
                    Modifier.fillMaxWidth().testTag("guide-tile-${t.slug}").clickable { onOpen(t) },
                    pad = 18,
                ) {
                    Line(t.title, Ink, size = 18, weight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Line(t.blurb, Ink2, size = Design.SMALL)
                }
            }
        } else {
            // Inside a profile, the way out is up to the section it was
            // opened from, which `StoryProfile` draws, not the tiles.
            if (story !is StoryPage.Profile) {
                Btn("‹ Guide", modifier = Modifier.testTag("guide-back")) { onBack() }
            }
            when (tile.slug) {
                Guide.COLOURS -> Colours()
                Guide.WHEEL -> Wheel()
                Guide.LORE -> Lore(era ?: PhyrexianLore.eras.first(), onEra)
                else -> story?.let { Story(it, onStory) }
            }
        }
    }
}

/**
 * The search bar over the tiles, and what it found in their place.
 * Sibling of `GuideFind` in `GuidePage`.
 *
 * Matt: "i want to be able to search guide articles on the Guide page
 * ... searching should show any articles which contain the keywords on
 * the search bar". What is found is `GuideSearch`'s. As wide as the
 * story at most, centred, so a tablet does not stretch it.
 */
@Composable
private fun GuideFind(search: String, onSearch: (String) -> Unit, hits: List<GuideHit>, onHit: (GuideHit) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = STORY.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                value = search,
                onValueChange = onSearch,
                placeholder = "Search the articles: Urza, Sylex, Ravnica",
                modifier = Modifier.testTag("guide-search"),
            )
            when {
                GuideSearch.words(search).isEmpty() -> {}
                hits.isEmpty() -> Line(GuideSearch.none(search), Ink3, modifier = Modifier.testTag("guide-search-none"))
                else -> {
                    Line(GuideSearch.count(hits.size), Ink3, size = Design.MINI, modifier = Modifier.testTag("guide-search-count"))
                    Column(Modifier.fillMaxWidth().testTag("guide-hits"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        hits.forEach { GuideHitCard(it) { onHit(it) } }
                    }
                }
            }
        }
    }
}

/** The site's `.guide-hit`: where it is from, its title, and a line round the word, with the accent down the left edge. */
@Composable
private fun GuideHitCard(hit: GuideHit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .testTag("guide-hit")
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius)
            .drawBehind { drawRect(Accent, size = Size(3.dp.toPx(), size.height)) }
            .clickable { onOpen() }
            .padding(start = 17.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Line(hit.part, Ink3, size = Design.MINI, modifier = Modifier.testTag("guide-hit-part"))
        Spacer(Modifier.height(3.dp))
        Line(hit.title, Ink, size = 16, weight = FontWeight.Bold, modifier = Modifier.testTag("guide-hit-title"))
        Spacer(Modifier.height(6.dp))
        Line(hit.snippet, Ink2, size = Design.SMALL, modifier = Modifier.testTag("guide-hit-snippet"))
    }
}

/**
 * Matt: "just a list of color combos and names, in wubrg order, so
 * they can easily be scanned to find what i need". The groups and
 * what they mean follow underneath, for reading rather than finding.
 */
@Composable
private fun Colours() {
    Column(Modifier.fillMaxWidth().border(1.dp, Line, Radius).clip(Radius)) {
        Guide.list.forEachIndexed { i, combo ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            ComboRow(combo)
        }
    }
    Guide.colours.forEach { group ->
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("guide-group")) {
            Line(group.title, Ink, size = Design.H2, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Line(group.about, Ink3, size = Design.MINI)
            Spacer(Modifier.height(6.dp))
            group.combos.forEach { combo ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Line(
                        combo.pips, Ink3, size = Design.TINY, weight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp),
                    )
                    Line(combo.name, Ink, size = Design.SMALL)
                }
                if (combo.note.isNotEmpty()) {
                    Line(combo.note, Ink2, size = Design.MINI, modifier = Modifier.padding(start = 52.dp).testTag("combo-note"))
                }
            }
        }
    }
}

@Composable
private fun ComboRow(combo: Combo) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("combo-row-${combo.pips}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            combo.pips.forEach { ManaSymbol(it.toString(), size = 20.dp, text = 11.sp) }
        }
        Line(
            combo.name, Ink, size = 15, weight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).testTag("combo-name"),
        )
        Line(combo.pips, Ink3, size = Design.TINY, weight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * The colour wheel: what it is, the wheel drawn, then each colour and
 * each pair. Everything said is in `ColourWheel`. Sibling of the
 * site's `Wheel` in `GuidePage`.
 */
@Composable
private fun Wheel() {
    ColourWheel.intro.forEach { Line(it, Ink2, size = 14) }
    // A fixed size, as on the site, so a tablet gets the same wheel as
    // a phone rather than one taller than the screen.
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(WHEEL.dp).testTag("wheel")) {
            // Allies joined by a solid line round the outside, enemies by
            // a dashed one across the middle: told apart by the dash, not
            // by a hue, because Matt is colourblind.
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2 * RADIUS
                val at = ColourWheel.colours.map { c ->
                    ColourWheel.point(c.pip).let { (x, y) -> Offset(center.x + (x * r).toFloat(), center.y + (y * r).toFloat()) }
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                for (i in 0 until 5) {
                    drawLine(Ink3, at[i], at[(i + 1) % 5], strokeWidth = 2.dp.toPx())
                    drawLine(Ink3, at[i], at[(i + 2) % 5], strokeWidth = 2.dp.toPx(), pathEffect = dash)
                }
            }
            ColourWheel.colours.forEach { c ->
                val (x, y) = ColourWheel.point(c.pip)
                val dx = (x * WHEEL / 2 * RADIUS).dp
                val dy = (y * WHEEL / 2 * RADIUS).dp
                Box(
                    Modifier.align(Alignment.Center).offset(dx, dy).size(52.dp).clip(CircleShape).background(Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    ManaSymbol(c.pip, size = 44.dp, text = 20.sp, tag = "wheel-spoke-${c.pip}")
                }
                Line(
                    c.name, Ink, size = Design.MINI, weight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).offset(dx, dy + 34.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Key(dashed = false, "Solid: allies, side by side")
        Spacer(Modifier.width(18.dp))
        Key(dashed = true, "Dashed: enemies, across")
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // One column on a phone, two once there is room, as the site's
        // grid does.
        val across = if (maxWidth >= 560.dp) 2 else 1
        Column(Modifier.fillMaxWidth()) {
            Group("The five colours", "Clockwise from the top.")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColourWheel.colours.chunked(across).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { WheelColourCard(it, Modifier.weight(1f)) }
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Pairs("Allied pairs", "Side by side on the wheel. They want similar things.", ColourWheel.allied)
            Spacer(Modifier.height(12.dp))
            Pairs("Enemy pairs", "Across the wheel. They clash, and a deck of both gets the best of each.", ColourWheel.enemy)
            Spacer(Modifier.height(12.dp))
            WheelLores(ColourWheel.SHARDS, ColourWheel.SHARDS_ABOUT, ColourWheel.shards, across)
            Spacer(Modifier.height(12.dp))
            WheelLores(ColourWheel.CLANS, ColourWheel.CLANS_ABOUT, ColourWheel.clans, across)
        }
    }
}

/**
 * Matt: "lore inserts for the shards of alara and the tribes of
 * tarkir ... the land scapes and culture with the missing colors of
 * magic." A card each, everything said in `ColourWheel`. Sibling of
 * the site's `WheelLores` in `GuidePage`.
 */
@Composable
private fun WheelLores(title: String, about: String, lore: List<WheelLore>, across: Int) {
    Group(title, about)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lore.chunked(across).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { l ->
                    Card(l.pips, Modifier.weight(1f).testTag("wheel-lore-${l.pips}"), pad = 14) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            l.pips.forEach { ManaSymbol(it.toString(), size = 22.dp, text = 12.sp) }
                            Spacer(Modifier.width(5.dp))
                            Line(l.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("wheel-lore-name"))
                        }
                        Spacer(Modifier.height(4.dp))
                        Line(
                            ColourWheel.lacking(l.pips), Ink3, size = Design.MINI, weight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("wheel-lore-missing-${l.pips}"),
                        )
                        Said("The land", l.land, "wheel-lore-land")
                        Said("The people", l.people, "wheel-lore-people")
                        Said("Without them", l.without, "wheel-lore-without")
                    }
                }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Group(title: String, about: String) {
    Spacer(Modifier.height(10.dp))
    Line(title, Ink, size = Design.H2, weight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Line(about, Ink3, size = Design.MINI)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Key(dashed: Boolean, words: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(22.dp).height(2.dp)) {
            drawLine(
                Ink3, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Line(words, Ink3, size = Design.MINI)
    }
}

@Composable
private fun WheelColourCard(c: WheelColour, modifier: Modifier) {
    Card(c.pip, modifier.testTag("wheel-colour-${c.pip}"), pad = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ManaSymbol(c.pip, size = 26.dp, text = 13.sp)
            Spacer(Modifier.width(8.dp))
            Line(c.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("wheel-colour-name"))
        }
        Said("Wants", c.wants, "wheel-wants")
        Said("Good at", c.goodAt, "wheel-good")
        Said("Bad at", c.badAt, "wheel-bad")
        Said("Allies", ColourWheel.alliesOf(c.pip), "wheel-allies-${c.pip}")
        Said("Enemies", ColourWheel.enemiesOf(c.pip), "wheel-enemies-${c.pip}")
    }
}

@Composable
private fun Said(label: String, words: String, tag: String) {
    Spacer(Modifier.height(6.dp))
    Line(label.uppercase(), Ink3, size = Design.TINY, weight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    Line(words, Ink2, size = Design.SMALL, modifier = Modifier.testTag(tag))
}

@Composable
private fun Pairs(title: String, about: String, pairs: List<WheelPair>) {
    Group(title, about)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pairs.chunked(2).forEach { two ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                two.forEach { p ->
                    Card(p.pips, Modifier.weight(1f).testTag("wheel-pair-${p.pips}"), pad = 12) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            p.pips.forEach { ManaSymbol(it.toString(), size = 22.dp, text = 12.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Line(p.name, Ink, size = 17, weight = FontWeight.Bold, modifier = Modifier.testTag("wheel-pair-name"))
                        Spacer(Modifier.height(3.dp))
                        Line(p.why, Ink2, size = Design.MINI)
                    }
                }
                if (two.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The story of Phyrexia: a row of tabs, one per era, then the era's
 * chapter set for reading. Everything said is in `PhyrexianLore`, and
 * the open tab is in the address. Sibling of `Lore` in `GuidePage`.
 *
 * One column no wider than the site's 680px, centred, so a tablet
 * does not stretch a line past reading. The tab row scrolls sideways
 * on a phone rather than wrapping into a pile.
 */
@Composable
private fun Lore(era: LoreEra, onEra: (LoreEra) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = STORY.dp).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("lore-tabs"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PhyrexianLore.eras.forEach { e ->
                    val open = e == era
                    // Told apart by lightness and an underline, not by
                    // a hue, because Matt is colourblind.
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        Line(
                            e.tab, if (open) Ink else Ink3, size = 14, weight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier
                                .selectable(selected = open, role = Role.Tab) { onEra(e) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("lore-tab"),
                        )
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (open) Ink else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("lore-story")) {
                androidx.compose.material3.Text(
                    era.title, Modifier.testTag("lore-title"), color = Ink,
                    fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
                )
                Spacer(Modifier.height(14.dp))
                era.story.forEach {
                    androidx.compose.material3.Text(
                        it, Modifier.padding(bottom = 18.dp).testTag("lore-paragraph"), color = Ink,
                        fontSize = 17.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif,
                    )
                }
                era.quote?.let { q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(IntrinsicSize.Min).testTag("lore-quote")) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Line2))
                        Spacer(Modifier.width(18.dp))
                        Column {
                            androidx.compose.material3.Text(
                                q.words, color = Ink, fontSize = 18.sp, lineHeight = 29.sp,
                                fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                            )
                            Spacer(Modifier.height(6.dp))
                            Line(q.source, Ink3, size = Design.MINI, modifier = Modifier.testTag("lore-quote-source"))
                        }
                    }
                }
                PhyrexianLore.next(era)?.let { next ->
                    Spacer(Modifier.height(10.dp))
                    Btn("Read on: " + next.tab + " ›", modifier = Modifier.testTag("lore-next")) { onEra(next) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * A section of the story of Magic, one tile of the Guide: a row of
 * tabs, one per article, then the article set for reading, or a
 * person's profile. Everything said is in `MagicStory`, and where you
 * are is in the address. Sibling of `Story` in the site's `GuidePage`.
 *
 * Matt: "each of those sections should be their own article, with its
 * own tabs, just like the phyrexian one".
 */
@Composable
private fun Story(page: StoryPage, onStory: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = STORY.dp).fillMaxWidth()) {
            when (page) {
                is StoryPage.Reading -> StoryReading(page.section, page.article, onStory)
                is StoryPage.Profile -> StoryProfile(page.section, page.person, onStory)
            }
        }
    }
}

/**
 * A paragraph with its links and cards. Links are underlined, not
 * coloured, and cards are in italics, because Matt is colourblind.
 */
@Composable
private fun StoryParagraph(paragraph: String, onStory: (String) -> Unit, tag: String = "lore-paragraph") {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        MagicStory.spans(paragraph).forEach { s ->
            val target = s.target
            when {
                target != null -> withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        target,
                        androidx.compose.ui.text.TextLinkStyles(
                            androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        ),
                    ) { onStory(target) },
                ) { append(s.text) }
                s.card -> withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                else -> append(s.text)
            }
        }
    }
    androidx.compose.material3.Text(
        text, Modifier.padding(bottom = 18.dp).testTag(tag), color = Ink,
        fontSize = 17.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif,
    )
}

@Composable
private fun StoryReading(section: StorySection, article: StoryArticle, onStory: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("lore-tabs"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        section.articles.forEach { a ->
            val open = a == article
            // Told apart by lightness and an underline, not by a hue.
            Column(Modifier.width(IntrinsicSize.Max)) {
                Line(
                    a.title, if (open) Ink else Ink3, size = 14, weight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier
                        .selectable(selected = open, role = Role.Tab) { onStory(a.slug) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("lore-tab"),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (open) Ink else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("lore-story")) {
        androidx.compose.material3.Text(
            article.title, Modifier.testTag("lore-title"), color = Ink,
            fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
        )
        // An article's parts, told one after another under their own
        // headings, so the tab reads as one chapter as Phyrexia's do.
        article.tabs.forEachIndexed { i, part ->
            Spacer(Modifier.height(if (i == 0) 8.dp else 14.dp))
            Line(part.tab.uppercase(), Ink3, size = Design.MINI, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("story-part-title"))
            Spacer(Modifier.height(10.dp))
            part.story.forEach { StoryParagraph(it, onStory) }
            part.quote?.let { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(IntrinsicSize.Min).testTag("lore-quote")) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Line2))
                    Spacer(Modifier.width(18.dp))
                    Column {
                        androidx.compose.material3.Text(
                            q.words, color = Ink, fontSize = 18.sp, lineHeight = 29.sp,
                            fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                        )
                        Spacer(Modifier.height(6.dp))
                        Line(q.source, Ink3, size = Design.MINI, modifier = Modifier.testTag("lore-quote-source"))
                    }
                }
            }
        }
        MagicStory.nextArticle(article)?.let { next ->
            val label = if (next in section.articles) "Read on: " + next.title + " ›" else "Next: " + MagicStory.sectionOf(next).title + " ›"
            Spacer(Modifier.height(10.dp))
            Btn(label, modifier = Modifier.testTag("lore-next")) { onStory(next.slug) }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 30.dp).testTag("story-sources")) {
            Line("WHERE IT IS TOLD", Ink3, size = Design.MINI, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            article.sources.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("story-source")) {
                    Line(s.kind.uppercase(), Ink3, size = Design.MINI, fontFamily = FontFamily.Monospace, modifier = Modifier.width(84.dp))
                    Line(s.name, Ink2, size = Design.SMALL)
                }
            }
        }
    }
}

@Composable
private fun StoryProfile(section: StorySection, person: StoryPerson, onStory: (String) -> Unit) {
    Btn("‹ " + section.title, modifier = Modifier.testTag("story-up")) { onStory("") }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("story-profile")) {
        androidx.compose.material3.Text(
            person.name, Modifier.testTag("lore-title"), color = Ink,
            fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
        )
        Spacer(Modifier.height(14.dp))
        person.profile.forEach { StoryParagraph(it, onStory) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 30.dp)) {
            Line("APPEARS IN", Ink3, size = Design.MINI, fontFamily = FontFamily.Monospace)
            MagicStory.appearances(person).forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onStory(a.slug) }.padding(vertical = 10.dp).testTag("story-appears-${a.slug}"),
                ) {
                    Line(MagicStory.sectionOf(a).title.uppercase(), Ink3, size = Design.MINI, fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
                    Line(a.title, Ink, size = Design.SMALL)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        }
    }
}

/** The story's widest, the same as the site's `.lore-story`. */
private const val STORY = 680

/** The wheel's width, the same as the site's `.wheel`. */
private const val WHEEL = 260

/** How far out the symbols sit, as a share of the wheel's radius: the site's 38% of its width. */
private const val RADIUS = 0.76

/**
 * The Comprehensive Rules, searched as you type. Sibling of the site's
 * `RulesSearch`.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default". The file is fetched once, through the Worker,
 * and `RuleBook` parses and searches it here, on the phone.
 *
 * A plain scrolling column, not a lazy one, for the reason the tiles
 * give: a page of results is at most `Rules.SHOWN` cards.
 */
@Composable
private fun RulesSearch(rules: Rules, onRules: (Rules) -> Unit, onLoad: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Design.WRAP_PAD_NARROW.dp).testTag("guide")) {
        Btn("‹ Guide", modifier = Modifier.testTag("guide-back")) { onBack() }
        Spacer(Modifier.height(10.dp))
        Field(
            value = rules.query,
            onValueChange = { onRules(rules.searched(it)) },
            placeholder = "Search the rules: trample, 702.19, commander tax",
            modifier = Modifier.testTag("rules-search"),
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val book = rules.book
            when {
                rules.busy -> Line("Loading the Comprehensive Rules…", Ink3, modifier = Modifier.testTag("rules-busy"))
                rules.error != null -> {
                    Line("Could not load the rules: ${rules.error}", Bad, modifier = Modifier.testTag("rules-err"))
                    Btn("Try again", modifier = Modifier.testTag("rules-retry")) { onLoad() }
                }
                book == null -> Line("The rules have not loaded yet.", Ink3)
                rules.query.isBlank() -> {
                    Line("Type a word or a rule number to find every rule that mentions it.", Ink2, modifier = Modifier.testTag("rules-hint"))
                    if (book.effective.isNotEmpty()) Line(book.effective, Ink3, size = Design.MINI)
                }
                rules.total == 0 -> Line("No rule mentions \"${rules.query.trim()}\".", Ink3, modifier = Modifier.testTag("rules-none"))
                else -> {
                    Line(rules.count, Ink3, size = Design.MINI, modifier = Modifier.testTag("rules-count"))
                    rules.hits.forEach { r -> RuleCard(r, rules.isOpen(r)) { onRules(rules.toggled(r)) } }
                }
            }
        }
    }
}

/**
 * The site's `.rule-card`: the number as the heading, heavy and in the
 * accent, with a rule of it down the left edge; a tap on the heading
 * folds the text and examples away.
 */
@Composable
private fun RuleCard(rule: Rule, open: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .testTag("rule-${rule.number}")
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius)
            .drawBehind { drawRect(Accent, size = Size(3.dp.toPx(), size.height)) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onToggle() }
                    .testTag("rule-head")
                    .padding(start = 17.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Line(
                    rule.shownNumber, Accent, size = 15, weight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("rule-number"),
                )
                Line(rule.section, Ink3, size = Design.MINI, maxLines = 1, modifier = Modifier.weight(1f))
                Line(if (open) "▾" else "▸", Ink2)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 17.dp, end = 14.dp, bottom = 13.dp).testTag("rule-body"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Line(rule.text, Ink, size = Design.BODY, modifier = Modifier.testTag("rule-text"))
                    rule.examples.forEach { e ->
                        Line(
                            e, Ink2, size = Design.SMALL,
                            modifier = Modifier.fillMaxWidth().clip(RadiusSm).background(Bg).padding(8.dp).testTag("rule-example"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The site's `.guide-tile` and `.combo`: a raised surface with the
 * colours as a strip across its top. The strip is decoration; the
 * words and the mana symbols carry it, because Matt is colourblind.
 */
@Composable
private fun Card(pips: String, modifier: Modifier, pad: Int, content: @Composable () -> Unit) {
    val colours = pips.map { c(Design.pip(it.toString())) }
    val strip = if (colours.size == 1) Brush.horizontalGradient(colours + colours) else Brush.horizontalGradient(colours)
    Box(
        modifier
            .clip(Radius)
            .background(Brush.linearGradient(listOf(Bg3, Bg2)))
            .border(1.dp, Line, Radius),
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(strip).testTag("guide-band"))
        Column(Modifier.fillMaxWidth().padding(start = pad.dp, end = pad.dp, top = (pad + 4).dp, bottom = pad.dp)) {
            content()
        }
    }
}
