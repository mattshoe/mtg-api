package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.MtgApi
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter panel, given facets that came out of the real decoder.
 *
 * Deliberately a class of its own, with no activity in it. Building a
 * `MainActivity` and holding a `createComposeRule` in the same class
 * means two things want the main dispatcher, and the result passed
 * alone and failed intermittently in the full suite — which is worse
 * than failing, because it goes red on somebody else's change. The
 * loader is proven next door, through the real `onCreate`; this only
 * has to show the panel does the right thing with a real decode.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FilterPanelFacetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val facetsBody = """
        {"cols":["kind","value"],"rows":[
            ["types","Creature"],["types","Instant"],
            ["layouts","normal"],["layouts","split"],
            ["setTypes","expansion"],["frames","2015"],["borders","black"],
            ["formats","commander"]
        ],"n":8}
    """.trimIndent()

    private val decksBody = """{"cols":["slug","name","owner"],"rows":[["alela","Alela","matt"]],"n":1}"""

    /**
     * The same facets, decoded the way the server's answer is decoded,
     * without launching an activity.
     *
     * Building `MainActivity` under `createComposeRule` means two
     * things both want to own the main dispatcher, and the test passed
     * alone and failed after the rest of the suite — order-dependent,
     * which is worse than failing outright. The load itself is already
     * proven by the test above, through the real `onCreate`; what is
     * left to show here is that the panel does something sensible with
     * a real decode, so this goes through `FacetQueries.decodeEverything`
     * — the same function the loader calls — rather than hand-building
     * a `Facets` the server could never produce.
     */
    private fun decodedFacets(): org.mattshoe.mtg.core.Facets {
        val rows = Json.parseToJsonElement(facetsBody).jsonObject["rows"]!!
            .jsonArray.map { it.jsonArray }
        val decks = Json.parseToJsonElement(decksBody).jsonObject["rows"]!!
            .jsonArray.map { it.jsonArray }
        return org.mattshoe.mtg.core.FacetQueries.decodeEverything(
            listOf(listOf("kind", "value") to rows),
            org.mattshoe.mtg.core.FacetQueries.decodeDecks(
                listOf("slug", "name", "owner"),
                decks,
            ),
        )
    }

    @Test
    fun theTypeChecklistIsNotStuckOnLoadingOnceFacetsAreIn() {
        val loaded = org.mattshoe.mtg.core.AppState(facets = decodedFacets())
        assertTrue(loaded.facets.types.isNotEmpty(), "the fixture decoded no types, so this proves nothing")

        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = loaded,
                        onState = {},
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onRunSql = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // "Card type" folds away until it holds a filter or is opened
        // by hand — the same accordion the website does not have, so
        // the checklist it wraps is not even composed until then.
        composeRule.onNodeWithTag("header-type").performClick()
        composeRule.waitForIdle()

        // The checklist is drawn at all, and it has moved past its
        // "loading…" placeholder.
        composeRule.onNodeWithTag("checks-types").assertExists()
        composeRule.onAllNodesWithText("loading…").fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "the type checklist is still showing \"loading…\" with facets present")
        }
    }
}
