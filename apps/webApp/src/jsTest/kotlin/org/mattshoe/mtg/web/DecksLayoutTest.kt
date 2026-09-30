package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
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
        var shared = 0
        val frame = mount(1000) { DecksPage(opened(), {}, {}, onShare = { shared++ }) }
        settle()
        val button = frame.all("button").firstOrNull { it.textContent?.trim() == "Share" }
            ?: error("no Share button on the deck")
        button.click()
        settle()
        assertEquals(1, shared)
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
