package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardFacts
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.Load
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Rows
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card sheet shows everything, links to EDHREC, the carousel says
 * the rank, and a double-faced card turns over. The phone's half of
 * the web's `CardEverythingTest`, asserting the same facts.
 *
 * Driven through `AppShell` over a real `AppState`, and every fixture
 * is what the real query returned against `schema.sql` and
 * `test/fixtures/seed.sql` in sqlite3, pasted as it came out. The
 * picture is read off the image's own semantics (`CardPicture`), which
 * is the address that was handed to Coil.
 */
@RunWith(AndroidJUnit4::class)
class CardEverythingParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: MutableState<AppState>
    private val opened = mutableListOf<String>()

    private fun shell(start: AppState) {
        val uris = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uris) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = remember { mutableStateOf(start) }
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
            }
        }
        rule.waitForIdle()
    }

    /** `{cols, rows}` out of one sqlite3 `-json` array. */
    private fun answer(json: String): Pair<List<String>, List<JsonArray>> {
        val rows = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
        val cols = rows.firstOrNull()?.keys?.toList().orEmpty()
        return cols to rows.map { r -> JsonArray(cols.map { r[it]!! }) }
    }

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

    private fun nodes(m: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes()

    private fun pictures(): List<String> =
        nodes(SemanticsMatcher.keyIsDefined(CardPicture)).map { it.config[CardPicture] }

    /** A Details row, label and value, both on screen and level with each other. */
    private fun fact(label: String, value: String): Boolean {
        rule.onNodeWithText(value, useUnmergedTree = true).performScrollTo()
        val l = nodes(hasText(label)).map { it.boundsInRoot.top }
        val v = nodes(hasText(value)).map { it.boundsInRoot.top }
        return l.any { a -> v.any { kotlin.math.abs(it - a) < 4 * rule.density.density } }
    }

    @Test
    fun theCardSheetHasADetailsSectionNamingTheArtistAndTheKeywords() {
        shell(agent())
        assertTrue(nodes(hasText("Details")).isNotEmpty(), "no Details heading on the card sheet")
        assertTrue(fact("Artist", "Alexander Mokhov"), "no Artist row")
        assertTrue(fact("Keywords", "Deathtouch, Transform"), "no Keywords row")
        assertTrue(fact("Finishes", "foil, nonfoil"), "no Finishes row")
        assertTrue(fact("EDHREC rank", "#20,135"), "no EDHREC rank row")
        assertTrue(fact("Border", "black"), "no Border row")
        assertTrue(fact("In boosters", "Yes"), "no In boosters row")
    }

    @Test
    fun theEdhrecLinkOpensTheCardsOwnPage() {
        shell(agent())
        rule.onNodeWithText("EDHREC ↗").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://edhrec.com/cards/aetherblade-agent"), opened)
    }

    @Test
    fun aDoubleFacedCardTurnsOverOnItsSheet() {
        shell(agent())
        assertTrue(pictures().single().contains("/front/"), "the sheet opened on ${pictures()}")
        val toggles = nodes(hasTestTag("flip-toggle"))
        assertEquals(1, toggles.size, "flip toggles on a transform card's sheet")
        rule.onNode(hasTestTag("flip-toggle"), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(pictures().single().contains("/back/"), "after a tap the picture is still ${pictures()}")
    }

    @Test
    fun aSingleFacedCardHasNoToggle() {
        shell(counterspell())
        assertTrue(nodes(hasText("Zack Stella")).isNotEmpty(), "the facts never reached the sheet, so this proves nothing")
        assertTrue(nodes(hasTestTag("flip-toggle")).isEmpty(), "a toggle on Counterspell")
    }

    // ----------------------------------------------------- the Library

    private fun library(): AppState {
        val (cols, rows) = answer("[$AGENT_ROW, $COUNTERSPELL_ROW]")
        val cards = Rows.cards(cols, rows)
        return AppState().navigate(View.LIBRARY).let { it.copy(library = it.library.loaded(cards, cards.size)) }
    }

    private val agentId = "dad34ae5-56b4-4394-be02-e043dc1cc23d"

    @Test
    fun aDoubleFacedTileTurnsOverWithoutOpeningAnything() {
        shell(library())
        assertEquals(2, pictures().size, "the two tiles are not both on screen: ${pictures()}")
        assertEquals(1, nodes(hasTestTag("flip-toggle")).size, "toggles on one transform and one instant")
        rule.onNode(hasTestTag("flip-toggle"), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK !in held.value.overlays, "the toggle opened the carousel")
        val agent = pictures().single { agentId in it }
        assertTrue("/back/" in agent, "the tile still shows $agent")
    }

    @Test
    fun theLibrarysCarouselSaysTheRankAndTurnsOver() {
        shell(library())
        rule.onAllNodes(hasTestTag("card-tile"))[0].performClick()
        rule.waitForIdle()
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "the tile did not open the carousel")
        assertTrue(nodes(hasText("EDHREC #20,135")).isNotEmpty(), "no rank on the carousel's sheet")
        val inCarousel = rule.onAllNodes(hasTestTag("flip-toggle"), useUnmergedTree = true)
        // The tile's toggle is still under the scrim; the carousel's is the last drawn.
        val n = inCarousel.fetchSemanticsNodes().size
        assertTrue(n >= 2, "no toggle in the carousel: $n in all")
        inCarousel[n - 1].performClick()
        rule.waitForIdle()
        assertTrue(held.value.showsBack(AGENT), "the carousel's toggle did not turn the card over")
        assertTrue(pictures().filter { agentId in it }.all { "/back/" in it }, "pictures after the tap: ${pictures()}")
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
