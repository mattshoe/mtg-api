package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardFacts
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card page shows everything, links to EDHREC, the carousel says
 * the rank, and a double-faced card turns over.
 *
 * Every fixture here is what the real query returned against
 * `schema.sql` and `test/fixtures/seed.sql` in sqlite3, pasted as it
 * came out, and every page is the real `AppShell` over a real
 * `AppState`. #35 described all of this and shipped none of it, and
 * nothing noticed, so this measures what is on the screen.
 */
class CardEverythingTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: MutableState<AppState>

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

    private fun shell(start: AppState) {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(start) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
    }

    private fun all(css: String): List<HTMLElement> =
        document.querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** `{cols, rows}` out of one sqlite3 `-json` array. */
    private fun answer(json: String): Pair<List<String>, List<JsonArray>> {
        val rows = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
        val cols = rows.firstOrNull()?.keys?.toList().orEmpty()
        return cols to rows.map { r -> JsonArray(cols.map { r[it]!! }) }
    }

    /** The card page, loaded the way `loadCard` loads it, from real answers. */
    private fun opened(norm: String, name: String, facts: String, faces: String, printings: String): AppState {
        val empty = emptyList<String>() to emptyList<JsonArray>()
        val answers = Load.card(norm).map {
            when (it.sql) {
                CardFacts.query(norm).sql -> answer(facts)
                CardQueries.face(norm).sql -> answer(faces)
                CardQueries.printings(norm).sql -> answer(printings)
                else -> empty
            }
        }
        return AppState().navigate(View.LIBRARY).openCard(CardRef(norm), name)
            .copy(card = Load.cardDetail(name, norm, answers))
    }

    private fun agent() = opened(AGENT, "Aetherblade Agent // Gitaxian Mindstinger", AGENT_FACTS, AGENT_FACES, AGENT_PRINTINGS)
    private fun counterspell() = opened("counterspell", "Counterspell", "[$COUNTERSPELL_FACTS]", "[]", "[$COUNTERSPELL_PRINTING]")

    /** The Details rows, read back off the page as label → value. */
    private fun facts(): Map<String, String> = all(".fact").associate {
        (it.querySelector(".fact-label")?.textContent?.trim() ?: "") to
            (it.querySelector(".fact-value")?.textContent?.trim() ?: "")
    }

    @Test
    fun theCardPageHasADetailsSectionNamingTheArtistAndTheKeywords() = runTest {
        shell(agent())
        settle()
        assertTrue(all("h3").any { it.textContent?.trim() == "Details" }, "no Details heading on the card page")
        val f = facts()
        assertEquals("Alexander Mokhov", f["Artist"], "the Details rows: $f")
        assertEquals("Deathtouch, Transform", f["Keywords"])
        assertEquals("foil, nonfoil", f["Finishes"])
        assertEquals("#20,135", f["EDHREC rank"])
        assertEquals("2015", f["Frame"])
        assertEquals("black", f["Border"])
        assertEquals("Yes", f["In boosters"])
    }

    @Test
    fun theDetailsDoNotHangOffTheSideOfThePage() = runTest {
        shell(agent())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val page = all("div.card-page").firstOrNull() ?: error("no card page")
        val limit = page.getBoundingClientRect().right + 1
        val over = all(".fact, .fact *").filter { it.getBoundingClientRect().right > limit }
            .map { it.className + ": " + it.textContent }
        assertTrue(over.isEmpty(), "Details rows wider than the page: $over")
    }

    @Test
    fun theEdhrecLinkGoesToTheCardsOwnPageInANewTab() = runTest {
        shell(agent())
        settle()
        val link = all("a").firstOrNull { it.textContent?.trim() == "EDHREC ↗" } as? HTMLAnchorElement
        assertNotNull(link, "no EDHREC link on the card page")
        assertEquals("https://edhrec.com/cards/aetherblade-agent", link.href)
        assertEquals("_blank", link.target)
    }

    @Test
    fun aDoubleFacedCardTurnsOverOnItsPage() = runTest {
        shell(agent())
        settle()
        val scan = { (document.querySelector("img.card-scan") as? HTMLImageElement)?.src.orEmpty() }
        assertTrue("/front/" in scan(), "the page opened on ${scan()}")
        val toggle = assertNotNull(document.querySelector(".card-page .flip-toggle") as? HTMLElement, "no flip toggle on a transform card")
        toggle.click()
        settle()
        assertTrue("/back/" in scan(), "after a tap the picture is still ${scan()}")
        toggle.click()
        settle()
        assertTrue("/front/" in scan(), "a second tap left it on ${scan()}")
    }

    @Test
    fun theToggleIsADarkDiscWithALightMark() = runTest {
        shell(agent())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val toggle = assertNotNull(document.querySelector(".flip-toggle") as? HTMLElement)
        val style = window.getComputedStyle(toggle)
        val bg = luminance(style.backgroundColor)
        val ink = luminance(style.color)
        val ratio = (maxOf(bg, ink) + 0.05) / (minOf(bg, ink) + 0.05)
        assertTrue(ratio >= 7.0, "the mark is ${style.color} on ${style.backgroundColor}, a contrast of $ratio")
        assertTrue(toggle.getBoundingClientRect().width >= 36.0, "a ${toggle.getBoundingClientRect().width}px target")
    }

    private fun luminance(css: String): Double {
        val (r, g, b) = Regex("""\d+""").findAll(css).take(3).map { it.value.toDouble() / 255 }.toList()
        fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    @Test
    fun aSingleFacedCardHasNoToggle() = runTest {
        shell(counterspell())
        settle()
        assertTrue(all(".fact").isNotEmpty(), "the facts never reached the page, so this proves nothing")
        assertNull(document.querySelector(".flip-toggle"), "a toggle on Counterspell")
    }

    // ----------------------------------------------------- the Library

    private fun library(): AppState {
        val (cols, rows) = answer("[$AGENT_ROW, $COUNTERSPELL_ROW]")
        val cards = Rows.cards(cols, rows)
        return AppState().navigate(View.LIBRARY).let { it.copy(library = it.library.loaded(cards, cards.size)) }
    }

    private fun tile(name: String): HTMLElement =
        all("div.card").first { it.querySelector(".nm")?.textContent?.trim() == name }

    @Test
    fun aDoubleFacedTileTurnsOverWithoutOpeningAnything() = runTest {
        shell(library())
        settle()
        val agent = tile("Aetherblade Agent // Gitaxian Mindstinger")
        assertNull(tile("Counterspell").querySelector(".flip-toggle"), "a toggle on a single-faced tile")
        val toggle = assertNotNull(agent.querySelector(".flip-toggle") as? HTMLElement, "no toggle on the transform tile")
        toggle.click()
        settle()
        assertTrue(Overlay.CARD_PEEK !in held.value.overlays, "the toggle opened the carousel")
        val src = (tile("Aetherblade Agent // Gitaxian Mindstinger").querySelector("img") as HTMLImageElement).src
        assertTrue("/back/" in src, "the tile still shows $src")
    }

    @Test
    fun theLibrarysCarouselSaysTheRankAndTurnsOver() = runTest {
        shell(library())
        settle()
        tile("Aetherblade Agent // Gitaxian Mindstinger").click()
        settle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the tile did not open the carousel")
        val tags = all(".peek-tags .tag").map { it.textContent?.trim() }
        assertTrue("EDHREC #20,135" in tags, "the carousel's tags: $tags")
        val card = all(".peek-card").first { (it.querySelector("img") as? HTMLImageElement)?.alt == "Aetherblade Agent // Gitaxian Mindstinger" }
        (assertNotNull(card.querySelector(".flip-toggle") as? HTMLElement, "no toggle in the carousel")).click()
        settle()
        val now = all(".peek-card").first { (it.querySelector("img") as? HTMLImageElement)?.alt == "Aetherblade Agent // Gitaxian Mindstinger" }
        assertTrue("/back/" in (now.querySelector("img") as HTMLImageElement).src, "the carousel did not turn it over")
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "turning it over closed the carousel")
    }

    companion object {
        const val AGENT = "aetherblade agent // gitaxian mindstinger"

        const val AGENT_FACTS = """[{"id":3240,"owner":"matt","qty":1,"finish":"nonfoil","foil_flag":"","scryfall_id":"dad34ae5-56b4-4394-be02-e043dc1cc23d","oracle_id":"80ecb069-36e2-490d-9f6c-ef553c00e997","name":"Aetherblade Agent // Gitaxian Mindstinger","name_norm":"aetherblade agent // gitaxian mindstinger","face1":"Aetherblade Agent","face2":"Gitaxian Mindstinger","mana_cost":"{1}{B}","cmc":2.0,"oracle_text":"Deathtouch\n{4}{U/P}: Transform this creature. Activate only as a sorcery. ({U/P} can be paid with either {U} or 2 life.)\n//\nDeathtouch\nWhenever this creature deals combat damage to a player or battle, draw a card.","flavor_text":null,"power":"1","toughness":"1","loyalty":null,"defense":null,"type_line":"Creature — Human Rogue // Creature — Phyrexian Rogue","supertypes":null,"types":"Creature","subtypes":"Human Rogue // Creature — Phyrexian Rogue","colors":"B","color_identity":"BU","color_identity_count":2,"produced_mana":null,"rarity":"common","setcode":"mom","set_name":"March of the Machine","set_type":"expansion","released_at":"2023-04-21","collector_number":"88","artist":"Alexander Mokhov","layout":"transform","frame":"2015","border_color":"black","watermark":null,"security_stamp":null,"reserved":0,"game_changer":0,"full_art":0,"textless":0,"promo":0,"reprint":0,"variation":0,"oversized":0,"story_spotlight":0,"booster":1,"edhrec_rank":20135,"keywords":"Deathtouch, Transform","finishes":"foil, nonfoil","games":"arena, mtgo, paper","promo_types":null,"frame_effects":null,"tags":"activated ability, alliteration, curiosity, cycle-mom-c-dfc, draw engine, life for cards, namesake spell, repeatable pure draw, synergy-battle, transform-improvement, triggered ability"}]"""

        const val AGENT_FACES = """[{"face_index":0,"name":"Aetherblade Agent","mana_cost":"{1}{B}","type_line":"Creature — Human Rogue","oracle_text":"Deathtouch\n{4}{U/P}: Transform this creature. Activate only as a sorcery. ({U/P} can be paid with either {U} or 2 life.)","flavor_text":"The Consulate of Kaladesh had always valued his particular talent for extracting information . . .","power":"1","toughness":"1","loyalty":null,"defense":null},
{"face_index":1,"name":"Gitaxian Mindstinger","mana_cost":"","type_line":"Creature — Phyrexian Rogue","oracle_text":"Deathtouch\nWhenever this creature deals combat damage to a player or battle, draw a card.","flavor_text":". . . but the Chrome Host's methods were far more efficient.","power":"3","toughness":"3","loyalty":null,"defense":null}]"""

        const val AGENT_PRINTINGS = """[{"id":3240,"name":"Aetherblade Agent // Gitaxian Mindstinger","face2":"Gitaxian Mindstinger","owner":"matt","setcode":"mom","set_name":"March of the Machine","collector_number":"88","finish":"nonfoil","qty":1,"scryfall_id":"dad34ae5-56b4-4394-be02-e043dc1cc23d","price":null,"tcg_url":null}]"""

        const val COUNTERSPELL_FACTS = """{"id":410,"owner":"kayla","qty":1,"finish":"nonfoil","foil_flag":"","scryfall_id":"4f616706-ec97-4923-bb1e-11a69fbaa1f8","oracle_id":"cc187110-1148-4090-bbb8-e205694a39f5","name":"Counterspell","name_norm":"counterspell","face1":"Counterspell","face2":null,"mana_cost":"{U}{U}","cmc":2.0,"oracle_text":"Counter target spell.","flavor_text":null,"power":null,"toughness":null,"loyalty":null,"defense":null,"type_line":"Instant","supertypes":null,"types":"Instant","subtypes":null,"colors":"U","color_identity":"U","color_identity_count":1,"produced_mana":null,"rarity":"uncommon","setcode":"dsc","set_name":"Duskmourn: House of Horror Commander","set_type":"commander","released_at":"2024-09-27","collector_number":"114","artist":"Zack Stella","layout":"normal","frame":"2015","border_color":"black","watermark":null,"security_stamp":null,"reserved":0,"game_changer":0,"full_art":0,"textless":0,"promo":0,"reprint":1,"variation":0,"oversized":0,"story_spotlight":0,"booster":0,"edhrec_rank":16,"keywords":null,"finishes":"nonfoil","games":"mtgo, paper","promo_types":null,"frame_effects":null,"tags":"counterspell, interrupt, meme, single english word name, single target instant/sorcery"}"""

        const val COUNTERSPELL_PRINTING = """{"id":410,"name":"Counterspell","face2":null,"owner":"kayla","setcode":"dsc","set_name":"Duskmourn: House of Horror Commander","collector_number":"114","finish":"nonfoil","qty":1,"scryfall_id":"4f616706-ec97-4923-bb1e-11a69fbaa1f8","price":null,"tcg_url":null}"""

        /** The Library's own query, `library page` in `core-sql.json`, against the seed. */
        const val AGENT_ROW = """{"id": 3240, "owner": "matt", "name": "Aetherblade Agent // Gitaxian Mindstinger", "name_norm": "aetherblade agent // gitaxian mindstinger", "face2": "Gitaxian Mindstinger", "layout": "transform", "scryfall_id": "dad34ae5-56b4-4394-be02-e043dc1cc23d", "mana_cost": "{1}{B}", "cmc": 2.0, "type_line": "Creature — Human Rogue // Creature — Phyrexian Rogue", "color_identity": "BU", "rarity": "common", "setcode": "mom", "set_name": "March of the Machine", "collector_number": "88", "edhrec_rank": 20135, "released_at": "2023-04-21", "finish": "nonfoil", "power": "1", "toughness": "1", "artist": "Alexander Mokhov", "qty": 1, "printings": 1, "free": 1, "price": null, "value": null, "unpriced": 1}"""

        const val COUNTERSPELL_ROW = """{"id": 410, "owner": "kayla", "name": "Counterspell", "name_norm": "counterspell", "face2": null, "layout": "normal", "scryfall_id": "4f616706-ec97-4923-bb1e-11a69fbaa1f8", "mana_cost": "{U}{U}", "cmc": 2.0, "type_line": "Instant", "color_identity": "U", "rarity": "uncommon", "setcode": "dsc", "set_name": "Duskmourn: House of Horror Commander", "collector_number": "114", "edhrec_rank": 16, "released_at": "2024-09-27", "finish": "nonfoil", "power": null, "toughness": null, "artist": "Zack Stella", "qty": 1, "printings": 1, "free": 1, "price": null, "value": null, "unpriced": 1}"""
    }
}
