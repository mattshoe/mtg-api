package org.mattshoe.mtg.android

import org.junit.Rule
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tokens a deck makes, on Android.
 *
 * Matt: "what the fuck happened to the tokens section at the bottom??"
 *
 * Nothing happened to it. `TokenList` has been at the bottom of the
 * deck page since the port and `DecksState.tokens` has been empty
 * every time it drew, because nothing on Android ever asked
 * Scryfall for them — `withTokens` appears exactly once in the
 * repository and it is in the website's `Main.kt`. The section was
 * rendering an empty list and an empty list draws nothing, so it
 * read as a section that had been removed.
 *
 * Which is why this test goes through the real `MainActivity` and a
 * real `openDeck` rather than handing `DecksState` a list of tokens:
 * a screen test would have been green throughout.
 */
@RunWith(AndroidJUnit4::class)
class DeckTokensLoadTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    /** Pump Robolectric's looper until the load has finished. See `MainActivityFacetsTest`. */
    private fun settle(activity: MainActivity) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            looper.idle()
            if (activity.tokensJob?.isCompleted == true) {
                looper.idle()
                return
            }
            Thread.sleep(10)
        }
        looper.idle()
    }

    private val deckRow = """
        {"cols":["slug","name","owner","commander","colors","bracket","art_id"],
         "rows":[["alela","Alela","matt",null,"UW",3,null]],"n":1}
    """.trimIndent()

    private val cardRow = """
        {"cols":["name","name_norm","qty","role","owned","type_line","scryfall_id"],
         "rows":[["Alela, Artful Provocateur","alela, artful provocateur",1,"commander",1,
                  "Legendary Creature","abcdef12-3456"]],"n":1}
    """.trimIndent()

    private fun fakeApi() = MtgApi.withEngine(
        "https://example.invalid",
        HttpClient(
            MockEngine { request ->
                // The text, not `body.toString()`, which is the class
                // name and a content type — so every branch below fell
                // through to the empty answer and the deck opened with
                // no cards in it.
                val text = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                val body = when {
                    "FROM deck_cards" in text -> cardRow
                    "FROM decks" in text -> deckRow
                    else -> """{"cols":[],"rows":[],"n":0}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    )

    /** One card that makes one token, in Scryfall's own shape. */
    private val collection = """
        {"data":[{"id":"abcdef12-3456","name":"Alela, Artful Provocateur",
                  "all_parts":[{"id":"99999999-0000","component":"token",
                                "name":"Faerie","type_line":"Token Creature — Faerie"}]}],
         "not_found":[]}
    """.trimIndent()

    /**
     * The second call.
     *
     * `Scryfall.tokens` asks `/cards/collection` twice — once for the
     * deck's cards, to read their `all_parts`, and once for the token
     * ids it found, because `all_parts` carries a name and a type
     * line and nothing else. Both are the same endpoint, so the fake
     * answers by which call it is.
     */
    private val tokenCollection = """
        {"data":[{"id":"99999999-0000","name":"Faerie",
                  "type_line":"Token Creature — Faerie",
                  "power":"1","toughness":"1","colors":["U"],"all_parts":[]}],
         "not_found":[]}
    """.trimIndent()

    private var asked = 0

    private fun fakeScryfall() = Scryfall.withEngine(
        HttpClient(
            MockEngine { request ->
                asked++
                val body = if (asked == 1) collection else tokenCollection
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private fun openTheDeck(): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controller = built
        val activity = built.get()
        activity.useForTesting(fakeApi())
        activity.useScryfallForTesting(fakeScryfall())
        built.create().start().resume()
        activity.setStateForTesting(
            activity.stateForTesting().navigate(Route(View.DECKS, "alela")),
        )
        activity.loadForTesting()
        settle(activity)
        return activity
    }

    @Test
    fun theFixtureActuallyOpensADeck() {
        // Asked first, so "no tokens" cannot mean "no deck".
        val s = openTheDeck().stateForTesting()
        assertEquals("alela", s.decks.openSlug, "the deck never opened")
        assertTrue(s.decks.cards.isNotEmpty(), "the deck opened with no cards")
        assertTrue(s.decks.scryfallIds.isNotEmpty(), "the cards carry no scryfall ids")
    }

    @Test
    fun openingADeckAsksScryfallWhatItMakes() {
        val activity = openTheDeck()
        assertTrue(asked > 0, "nothing ever asked Scryfall for the deck's tokens")
    }

    @Test
    fun theTokensLandOnTheDeck() {
        val activity = openTheDeck()
        val tokens = activity.stateForTesting().decks.tokens
        assertTrue(tokens.isNotEmpty(), "the deck's token list is empty after a successful load")
        assertEquals("Faerie", tokens.first().name)
    }
}
