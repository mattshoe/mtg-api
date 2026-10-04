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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.MtgApi
import org.robolectric.Robolectric
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Facets, loaded through `MainActivity` rather than handed to a
 * composable.
 *
 * `LibraryParityTest` passes a hand-built `Facets` straight to
 * `LibraryScreen` and is green whether or not the phone can ever
 * produce one. These go the way a real launch does: `MainActivity`'s
 * real `onCreate`, against a fake network, reading the real
 * `FacetQueries` the web uses — so a loader that never gets written
 * (today's bug) or that refetches on every navigation shows up here
 * even though nothing about the panel's own code is wrong.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityFacetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        // `lifecycleScope.launch` dispatches onto `Dispatchers.Main`,
        // which under Robolectric only drains when the looper is
        // idled by hand. Running it unconfined instead means the
        // fake network — which never actually leaves this thread —
        // finishes inside the `launch` call, with nothing to idle.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val facetsBody = """
        {"cols":["kind","value"],"rows":[
            ["types","Creature"],["types","Instant"],
            ["layouts","normal"],["layouts","split"],
            ["setTypes","expansion"],["frames","2015"],["borders","black"],
            ["formats","commander"]
        ],"n":8}
    """.trimIndent()

    private val decksBody = """{"cols":["slug","name","owner"],"rows":[["alela","Alela","matt"]],"n":1}"""

    private val emptyBody = """{"cols":[],"rows":[],"n":0}"""

    /**
     * `MtgApi.withEngine`, the seam `core-net` built for exactly this —
     * a client over a caller-supplied engine instead of the real one.
     * The handler tells a facet read from a deck read from everything
     * else by the SQL text itself, the same way a human reading the
     * request would.
     */
    private fun fakeApi(seen: MutableList<String>): MtgApi {
        val engine = MockEngine { request ->
            val text = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            seen += text
            val body = when {
                "FROM decks" in text -> decksBody
                "AS kind" in text -> facetsBody
                else -> emptyBody
            }
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi.withEngine("https://example.invalid", client)
    }

    @Test
    fun facetsLoadOnceAtStartupAndASecondCallDoesNotRefetch() {
        val seen = mutableListOf<String>()
        val built = Robolectric.buildActivity(MainActivity::class.java)
        val realActivity = built.get()
        realActivity.useForTesting(fakeApi(seen))
        built.create().start().resume()

        val loaded = realActivity.stateForTesting()
        assertTrue(loaded.facets.loaded, "facets never loaded — app.facets.loaded is false after onCreate")
        assertTrue(loaded.facets.types.isNotEmpty(), "the type checklist's own list is empty")
        assertTrue(loaded.facets.layouts.isNotEmpty(), "layouts is empty")
        assertTrue(loaded.facets.setTypes.isNotEmpty(), "setTypes is empty")
        assertTrue(loaded.facets.frames.isNotEmpty(), "frames is empty")
        assertTrue(loaded.facets.borders.isNotEmpty(), "borders is empty")
        assertTrue(loaded.facets.formats.isNotEmpty(), "formats is empty")
        assertTrue(loaded.facets.decks.isNotEmpty(), "the deck picker's own list is empty")
        assertEquals("Alela", loaded.facets.decks.first().name, "the deck facet did not decode the deck query's rows")

        val requestsAfterFirstLoad = seen.size
        realActivity.loadFacetsForTesting()
        assertEquals(
            requestsAfterFirstLoad,
            seen.size,
            "a second load re-fetched facets instead of guarding on facets.loaded",
        )
    }

    @Test
    fun theTypeChecklistIsNotStuckOnLoadingOnceFacetsAreIn() {
        val seen = mutableListOf<String>()
        val built = Robolectric.buildActivity(MainActivity::class.java)
        val activity = built.get()
        activity.useForTesting(fakeApi(seen))
        built.create().start().resume()

        val loaded = activity.stateForTesting()
        assertTrue(loaded.facets.loaded, "facets never loaded — nothing downstream can be asserted")

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
