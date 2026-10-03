package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.ShareWhat
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Decks page, measured against the real stylesheet.
 *
 * "No padding between anything" and "the two owners are barely
 * distinguishable" are both numbers: the gap between one person's
 * grid and the next person's heading, and whether that heading has
 * anything separating it from the cards under it. The port rendered a
 * bare `h2` between two grids, and every heading in this stylesheet
 * is `margin: 0`.
 */
class DecksLayoutTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun mount(width: Int, block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { block() }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun twoOwners() = DecksState().loaded(
        listOf(
            deck("a", "kayla", "Bello"), deck("b", "kayla", "Chulane"),
            deck("c", "matt", "Alela"), deck("d", "matt", "Dihada"),
        ),
    )

    private fun card(name: String, type: String?, role: String? = null, owned: Int = 1) =
        DeckCard(
            name, qty = 1, role = role, owned = owned,
            nameNorm = name.lowercase(), typeLine = type, scryfallId = "abcdef12-3456",
        )

    private fun opened() = DecksState()
        .loaded(listOf(deck("a", "matt", "Alela")))
        .opened(
            "a",
            listOf(
                card("Alela, Artful Provocateur", "Legendary Creature — Faerie", "commander"),
                card("Sol Ring", "Artifact"),
                card("Zulaport Cutthroat", "Creature — Human Rogue"),
                card("Birds of Paradise", "Creature — Bird"),
                card("Rhystic Study", "Enchantment", owned = 0),
                card("Island", "Basic Land — Island"),
            ),
        )

    // ------------------------------------------------- one owner, then the next

    @Test
    fun eachOwnerIsItsOwnGroup() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        assertEquals(2, frame.all("div.owner-group").size, "the owners are not grouped at all")
        assertEquals(2, frame.all("div.owner-head").size)
    }

    @Test
    fun thereIsRealAirBetweenOneOwnersDecksAndTheNextOwnersName() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val groups = frame.all("div.owner-group")
        val firstGridBottom = groups[0].all("div.deck-grid").first().getBoundingClientRect().bottom
        val secondHeadTop = groups[1].all("div.owner-head").first().getBoundingClientRect().top
        val gap = secondHeadTop - firstGridBottom
        assertTrue(gap >= 20, "only ${gap}px between one owner's decks and the next owner's name")
    }

    @Test
    fun anOwnerHeadingIsSeparatedFromTheDecksUnderIt() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val head = frame.all("div.owner-head").first()
        val grid = frame.all("div.deck-grid").first()
        val gap = grid.getBoundingClientRect().top - head.getBoundingClientRect().bottom
        assertTrue(gap >= 8, "the heading sits ${gap}px off the cards it labels")
        assertTrue(
            window.getComputedStyle(head).borderBottomWidth != "0px",
            "nothing draws the line under an owner's name",
        )
    }

    @Test
    fun theHeadingSaysHowManyDecksAreUnderIt() = runTest {
        val frame = mount(1000) { DecksPage(twoOwners(), {}, {}) }
        settle()
        assertTrue(frame.textContent.orEmpty().contains("2 decks"), frame.textContent.orEmpty())
    }

    // ------------------------------------------------------- one deck, opened

    @Test
    fun theCardsInADeckAreRuledOffFromEachOther() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        assertEquals(6, frame.all("div.deck-line").size, "not every card is listed")
        // Within a group. The first row of a panel has nothing above
        // it to be ruled off from.
        val creatures = frame.all("div.panel")
            .first { it.textContent.orEmpty().startsWith("Creatures") }
            .all("div.deck-line")
        assertEquals(2, creatures.size)
        val gap = creatures[1].getBoundingClientRect().top - creatures[0].getBoundingClientRect().bottom
        assertTrue(gap >= 0, "the rows overlap")
        assertTrue(
            creatures[0].getBoundingClientRect().height >= 20,
            "a card line is only ${creatures[0].getBoundingClientRect().height}px tall",
        )
        assertTrue(
            window.getComputedStyle(creatures[1]).borderTopWidth != "0px",
            "nothing separates one card from the next",
        )
    }

    @Test
    fun nothingInAnOpenedDeckSitsFlushAgainstTheThingAboveIt() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val head = frame.all("div.page-head").first().getBoundingClientRect()
        val body = frame.all("div.stack").first().getBoundingClientRect()
        assertTrue(body.top - head.bottom >= 8, "only ${body.top - head.bottom}px under the header")

        // The analysis, then the list. Neither flush against the other.
        val panels = frame.all("div.panel").map { it.getBoundingClientRect() }
        assertTrue(panels.size >= 2, "expected the analysis and at least one group")
        assertTrue(
            panels[1].top - panels[0].bottom >= 8,
            "only ${panels[1].top - panels[0].bottom}px between the analysis and the list",
        )
    }

    @Test
    fun theBackButtonIsInTheHeaderRatherThanFloatingAboveIt() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val head = frame.all("div.page-head").firstOrNull() ?: error("no page head")
        assertTrue(
            head.textContent.orEmpty().contains("← Decks"),
            "the back button is not in the header: ${head.textContent}",
        )
    }

    @Test
    fun anOpenedDeckFitsOnAPhoneHoweverLongItsCommanderIsCalled() = runTest {
        // A double-faced name whose halves are identical — "Jetmir,
        // Nexus of Revels // Jetmir, Nexus of Revels" — pushed the
        // whole page sideways, header and all. Nothing on this screen
        // may be wider than the screen.
        val long = "Jetmir, Nexus of Revels // Jetmir, Nexus of Revels"
        val deck = Deck("a", "Feather Storm", "matt", long, "GRW", 3, null)
        val s = DecksState().loaded(listOf(deck)).opened(
            "a",
            listOf(
                DeckCard(long, 1, "commander", 1, nameNorm = long.lowercase(),
                    typeLine = "Legendary Creature — Cat Demon", manaCost = "{1}{R}{G}{W}", cmc = 4.0),
                DeckCard("Plains", 20, null, 20, nameNorm = "plains",
                    typeLine = "Basic Land — Plains", cmc = 0.0, producedMana = "W"),
            ),
        )
        val frame = mount(414) { DecksPage(s, {}, {}, admin = true) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.all("*")
            .filter { it.getBoundingClientRect().right > limit }
            .map { "${it.tagName.lowercase()}.${it.className} -> ${it.getBoundingClientRect().right}" }
        assertTrue(over.isEmpty(), "wider than the phone (limit $limit):\n  " + over.joinToString("\n  "))
    }

    @Test
    fun nothingOverflowsTheDecksPageAtPhoneWidth() = runTest {
        val frame = mount(390) { DecksPage(twoOwners(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val limit = frame.getBoundingClientRect().right + 1
        val over = frame.all("*")
            .filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
        assertTrue(over.isEmpty(), "hanging off the right edge: $over")
    }

    // ------------------------------------------------------ the banner

    @Test
    fun theCommanderGetsABannerAcrossTheTop() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val hero = frame.all("div.deck-hero").firstOrNull() ?: error("no banner")
        assertTrue(
            hero.textContent.orEmpty().contains("Alela, Artful Provocateur"),
            "the banner does not name the commander: ${hero.textContent}",
        )
        assertEquals(1, hero.all("img").size, "the banner has no picture")
        if (!Stylesheet.applied()) return@runTest
        assertTrue(hero.getBoundingClientRect().height >= 100, "the band is barely there")
    }

    @Test
    fun aDeckWithNoCommanderGetsNoEmptyBand() = runTest {
        // A 60-card list has no commander and nothing to crop. An
        // empty grey band is worse than none.
        val bare = Deck("a", "Sixty", "matt", null, "UW", null, null)
        val s = DecksState().loaded(listOf(bare)).opened("a", listOf(card("Sol Ring", "Artifact")))
        val frame = mount(1000) { DecksPage(s, {}, {}) }
        settle()
        assertEquals(0, frame.all("div.deck-hero").size)
        // The name lives in the header now, so what the page has to
        // still show is the list.
        assertTrue(frame.textContent.orEmpty().contains("Sol Ring"), "the deck lost its list")
        assertEquals("Sixty", AppState().navigate(Route(View.DECKS, "a")).copy(decks = s).title)
    }

    @Test
    fun theBannerTextSitsOverAWashSoItCanBeRead() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val hero = frame.all("div.deck-hero").first()
        assertEquals(1, hero.all("div.deck-hero-wash").size, "nothing darkens the art under the name")
        val text = hero.all("div.deck-hero-text").first().getBoundingClientRect()
        assertTrue(text.bottom <= hero.getBoundingClientRect().bottom + 1, "the name hangs out of the band")
    }

    @Test
    fun anOpenDeckOffersAShare() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val button = frame.all("button[aria-label='Share this deck']").firstOrNull()
            ?: error("no share button on the deck")
        assertTrue(button.querySelector(".icon-share") != null, "the share button has no icon")
        assertTrue(
            button.textContent.orEmpty().isBlank(),
            "the word is back: '${button.textContent}'",
        )
    }

    @Test
    fun theShareOffersALinkOrTheListEitherWay() = runTest {
        // Four ways to hand a deck over, and the menu has to say which
        // is which — "Copy" twice with nothing above it is a coin toss.
        val picked = mutableListOf<Pair<ShareWhat, ExportTo>>()
        val frame = mount(1000) { DecksPage(opened(), {}, {}, onShare = { w, e -> picked += w to e }) }
        settle()
        frame.all("button[aria-label='Share this deck']").first().click()
        settle()
        val menu = frame.all("div.app-menu.open").firstOrNull() ?: error("the menu did not open")
        assertEquals(
            listOf("Link", "Deck list"),
            menu.all("div.app-menu-group").map { it.textContent.orEmpty() },
        )
        assertEquals(
            listOf("Copy", "Download", "Copy", "Download"),
            menu.all("button.app-tab").map { it.textContent.orEmpty() },
        )

        menu.all("button.app-tab")[3].click()
        settle()
        assertEquals(listOf(ShareWhat.DECKLIST to ExportTo.FILE), picked)
        assertTrue(frame.all("div.app-menu.open").isEmpty(), "the menu stayed open over the page")
    }

    @Test
    fun eachOfTheFourReportsItself() = runTest {
        val picked = mutableListOf<Pair<ShareWhat, ExportTo>>()
        val frame = mount(1000) { DecksPage(opened(), {}, {}, onShare = { w, e -> picked += w to e }) }
        settle()
        repeat(4) { n ->
            frame.all("button[aria-label='Share this deck']").first().click()
            settle()
            frame.all("div.app-menu.open").first().all("button.app-tab")[n].click()
            settle()
        }
        assertEquals(
            listOf(
                ShareWhat.LINK to ExportTo.CLIPBOARD,
                ShareWhat.LINK to ExportTo.FILE,
                ShareWhat.DECKLIST to ExportTo.CLIPBOARD,
                ShareWhat.DECKLIST to ExportTo.FILE,
            ),
            picked,
        )
    }

    @Test
    fun theShareMenuReadsDownItsLeftEdge() = runTest {
        // The header aligns its own children to the right, and the
        // group labels inherited it — "LINK" hard against the far
        // edge with its two options under the near one.
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        frame.all("button[aria-label='Share this deck']").first().click()
        settle()
        val menu = frame.all("div.app-menu.open").first()
        // The boxes are full width either way, so it is the text
        // inside them that has to be checked.
        (menu.all("div.app-menu-group") + menu.all("button.app-tab")).forEach { row ->
            // "start" is what a left-to-right page computes to when
            // nothing has overridden it, and it is the same edge.
            val align = kotlinx.browser.window.getComputedStyle(row).textAlign
            assertTrue(
                align == "left" || align == "start",
                "'${row.textContent}' reads down the $align edge",
            )
        }
    }

    @Test
    fun aPressOutsideShutsTheShareMenu() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        frame.all("button[aria-label='Share this deck']").first().click()
        settle()
        assertTrue(frame.all("div.app-menu.open").isNotEmpty())
        (frame.all("div.nav-backdrop").firstOrNull() ?: error("nothing to press outside")).click()
        settle()
        assertTrue(frame.all("div.app-menu.open").isEmpty(), "the menu is stuck open")
    }

    /**
     * The menu, on a phone, with the admin buttons beside it.
     *
     * This passed for weeks while the menu was visibly cut off on
     * Matt's phone, because it rendered the deck locked: with no "Edit
     * list", "Rename" or "Disassemble" after it the share button sits
     * flush against the right edge, which is the one position a menu
     * hanging off its right edge cannot overflow from. Unlocked, those
     * three push the button inward and the menu ran off the left.
     *
     * Both widths, because the fix is a width rule and a rule that
     * only ever runs one way is half untested.
     */
    /**
     * Like `mount`, but not a containing block.
     *
     * `mount` positions its frame absolutely so the mounts do not
     * stack, and that quietly stood in for the containing block the
     * real page does not have: an absolutely positioned menu resolved
     * against the 400px frame instead of the viewport, so a rule that
     * sent the menu to the viewport's edges measured as if it had gone
     * to the page's. That is the shape of the bug being fixed here, so
     * this test cannot use a harness that hides it.
     */
    private fun column(width: Int, block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { block() }
        return frame
    }

    @Test
    fun theShareMenuStaysOnScreenWithTheAdminButtonsBesideIt() = runTest {
        listOf(360, 400, 700, 1000).forEach { width ->
            val frame = column(width) { DecksPage(opened(), {}, {}, admin = true) }
            settle()
            if (!Stylesheet.applied()) return@runTest
            frame.all("button[aria-label='Share this deck']").first().click()
            settle()
            val menu = frame.all("div.app-menu.open").first().getBoundingClientRect()
            val page = frame.getBoundingClientRect()
            assertTrue(
                menu.width > 0 && menu.height > 0,
                "at ${width}px the menu has no size, so this measures nothing",
            )
            assertTrue(
                menu.right <= page.right + 1,
                "at ${width}px the menu runs off the right: ${menu.right} > ${page.right}",
            )
            assertTrue(
                menu.left >= page.left - 1,
                "at ${width}px the menu runs off the left: ${menu.left} < ${page.left}",
            )
        }
    }

    /** Locked, the same. The old test only ever covered this one. */
    @Test
    fun theShareMenuStaysOnScreenAtPhoneWidth() = runTest {
        val frame = column(400) { DecksPage(opened(), {}, {}) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        frame.all("button[aria-label='Share this deck']").first().click()
        settle()
        val menu = frame.all("div.app-menu.open").first().getBoundingClientRect()
        val page = frame.getBoundingClientRect()
        assertTrue(menu.width > 0, "the menu has no size, so this measures nothing")
        assertTrue(menu.right <= page.right + 1, "the menu runs off the right: ${menu.right} > ${page.right}")
        assertTrue(menu.left >= page.left - 1, "the menu runs off the left")
    }

    // ---------------------------------------------------- the tokens

    private fun withTokens() = opened().withTokens(
        listOf(
            org.mattshoe.mtg.core.TokenCard(
                "abcdef12-3456", "Bird", "Token Creature — Bird", "1", "1", "W",
                tcgplayer = "https://tcg.example/bird", madeBy = 2,
            ),
            org.mattshoe.mtg.core.TokenCard(
                "bbcdef12-3456", "Bird", "Token Creature — Bird", "2", "2", "G",
            ),
            org.mattshoe.mtg.core.TokenCard("ccdef123-4567", "Clue", "Token Artifact — Clue"),
        ),
    )

    @Test
    fun theTokensAreCardsBelowTheList() = runTest {
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}) }
        settle()
        val heads = frame.all("div.panel-head h2").map { it.textContent.orEmpty() }
        assertEquals("Tokens", heads.last(), "the tokens are not the last thing on the page")
        val rows = frame.all("div.panel")
            .first { it.textContent.orEmpty().startsWith("Tokens") }
            .all("div.deck-line, a.deck-line")
        assertEquals(3, rows.size)
        // A card, the same as every other row: art, a name, a type.
        assertEquals(3, rows.count { it.querySelector("div.thumb img") != null }, "a token with no art")
    }

    @Test
    fun twoTokensThatDifferOnlyByColourDoNotLookIdentical() = runTest {
        // Two 2/2 Birds, one green and one blue, are two tokens. With
        // nothing on the row saying which, they are two identical
        // lines and the list reads as a bug.
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}) }
        settle()
        val rows = frame.all("div.panel")
            .first { it.textContent.orEmpty().startsWith("Tokens") }
            .all("div.deck-line, a.deck-line")
        val birds = rows.filter { it.textContent.orEmpty().contains("Bird") }
        assertEquals(2, birds.size)
        val pips = birds.map { row ->
            row.all("img.mana-sym").joinToString("") { it.getAttribute("alt").orEmpty() }
        }
        assertEquals(listOf("{W}", "{G}"), pips)
        assertTrue(birds[0].textContent.orEmpty().contains("1/1"), birds[0].textContent.orEmpty())
        assertTrue(birds[1].textContent.orEmpty().contains("2/2"))
    }

    @Test
    fun aColourlessTokenSaysSoRatherThanShowingNothing() = runTest {
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}) }
        settle()
        val clue = frame.all("div.deck-line").first { it.textContent.orEmpty().contains("Clue") }
        assertEquals(
            "{C}",
            clue.all("img.mana-sym").joinToString("") { it.getAttribute("alt").orEmpty() },
        )
        // An artifact token has no power and toughness to show.
        assertTrue(!clue.textContent.orEmpty().contains("/"), clue.textContent.orEmpty())
    }

    @Test
    fun aTokenYouCanBuyLinksToTcgplayer() = runTest {
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}) }
        settle()
        val link = frame.all("a.deck-line").firstOrNull() ?: error("no token links at all")
        assertEquals("https://tcg.example/bird", link.getAttribute("href"))
        // It leaves the site, so it opens away and cannot reach back
        // through `window.opener`.
        assertEquals("_blank", link.getAttribute("target"))
        assertTrue(
            link.getAttribute("rel").orEmpty().contains("noopener"),
            "a new tab with no rel=noopener",
        )
    }

    @Test
    fun aTokenNobodySellsStaysARowRatherThanALinkToNowhere() = runTest {
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}) }
        settle()
        // One of the three has a listing; the other two are plain.
        assertEquals(1, frame.all("a.deck-line").size)
        val rows = frame.all("div.panel")
            .first { it.textContent.orEmpty().startsWith("Tokens") }
            .all("div.deck-line, a.deck-line")
        assertEquals(3, rows.size, "a token without a listing went missing")
    }

    @Test
    fun aDeckWithNoTokensHasNoTokenPanel() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val heads = frame.all("div.panel-head h2").map { it.textContent.orEmpty() }
        assertTrue("Tokens" !in heads, heads.toString())
    }

    // ------------------------------------------------- grouped by type

    @Test
    fun theListIsGroupedByTypeInReadingOrder() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        // The analysis panel heads the page; the groups follow it.
        val headings = frame.all("div.panel-head h2").map { it.textContent.orEmpty() }
        assertEquals("The deck at a glance", headings.first())
        assertEquals(
            listOf("Commander", "Creatures", "Artifacts", "Enchantments", "Lands"),
            headings.drop(1),
        )
    }

    @Test
    fun eachGroupIsAlphabeticalInside() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val creatures = frame.all("div.panel")
            .first { it.textContent.orEmpty().startsWith("Creatures") }
            .all("span.t-name").map { it.textContent.orEmpty() }
        assertEquals(listOf("Birds of Paradise", "Zulaport Cutthroat"), creatures)
    }

    // ---------------------------------------------------- the card rows

    @Test
    fun everyCardHasASquareThumbnail() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val thumbs = frame.all("div.deck-line div.thumb")
        assertEquals(6, thumbs.size, "not every card has one")
        if (!Stylesheet.applied()) return@runTest
        val r = thumbs.first().getBoundingClientRect()
        assertTrue(r.width > 24, "the thumbnail is ${r.width}px wide")
        assertTrue(
            kotlin.math.abs(r.width - r.height) <= 1,
            "the thumbnail is ${r.width} by ${r.height}, which is not square",
        )
    }

    @Test
    fun tappingACardAsksForItsDetail() = runTest {
        var asked: Pair<String, String>? = null
        val frame = mount(1000) {
            DecksPage(opened(), {}, {}, onOpenCard = { c, owner -> asked = c.nameNorm to owner })
        }
        settle()
        val row = frame.all("div.deck-line").first { it.textContent.orEmpty().contains("Sol Ring") }
        row.click()
        settle()
        // The real `name_norm`, not the display name lowercased, and
        // the deck's owner rather than a guess.
        assertEquals("sol ring" to "matt", asked)
    }

    @Test
    fun aCardRowSaysItIsSomethingYouCanPress() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}) }
        settle()
        val row = frame.all("div.deck-line").first()
        assertEquals("button", row.getAttribute("role"))
        assertEquals("0", row.getAttribute("tabindex"), "it cannot be reached by keyboard")
    }

    @Test
    fun aCardNobodyOwnsStillListsWithoutABrokenPicture() = runTest {
        val s = DecksState()
            .loaded(listOf(deck("a", "matt", "Alela")))
            .opened("a", listOf(DeckCard("Unowned Thing", 1, null, 0, nameNorm = "unowned thing")))
        val frame = mount(1000) { DecksPage(s, {}, {}) }
        settle()
        assertTrue(frame.textContent.orEmpty().contains("Unowned Thing"))
        assertEquals(1, frame.all("div.deck-line div.thumb").size, "no thumbnail box")
        assertEquals(0, frame.all("div.deck-line div.thumb img").size, "an img with no source")
    }
}
