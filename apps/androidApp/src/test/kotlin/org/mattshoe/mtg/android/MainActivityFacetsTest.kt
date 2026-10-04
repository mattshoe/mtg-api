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
import androidx.compose.runtime.snapshots.Snapshot
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
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


    /**
     * Drain the main looper, which is where `lifecycleScope.launch`
     * puts the load.
     *
     * This used to swap in an unconfined test dispatcher instead.
     * That worked when the class ran alone and failed when it ran
     * after the rest of the suite, because `createComposeRule` also
     * owns the main dispatcher and the two fought over it — a test
     * that passes by itself and fails in company is worse than one
     * that just fails. Idling Robolectric's own looper asks the
     * framework to finish what it has queued and takes nothing over.
     */
    private fun settle(activity: MainActivity? = null) {
        shadowOf(Looper.getMainLooper()).idle()
        // Wait for the load itself rather than hoping a dispatcher ran
        // it inline. That hope is what made this pass alone and fail
        // after other classes had installed their own `Main`.
        activity?.facetsJob?.let { kotlinx.coroutines.runBlocking { it.join() } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before
    fun setUp() {
        // Both halves are needed, and neither alone is enough.
        // `lifecycleScope.launch` goes to `Dispatchers.Main`; an
        // unconfined test dispatcher runs the fake network inline so
        // there is something to find, and idling Robolectric's looper
        // drains whatever the framework queued around it.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /**
     * Destroy the activity before resetting the dispatcher.
     *
     * A resumed `MainActivity` owns a Compose root, and
     * `ComposeRootRegistry` keeps every root that was created and never
     * detached for the life of the JVM. Compose's Robolectric idling
     * strategy spins until all of them report idle, so a leaked
     * activity here hangs the next `createComposeRule` test in an
     * unrelated class. See the same note in `DownloadDecisionTest`.
     */
    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        // Then flush the global snapshot. `ComposeIdlingResource`
        // decides whether to keep pumping frames from
        // `clock.hasAwaiters || Snapshot.current.hasPendingChanges() ||
        // recomposer.hasPendingWork`, and the middle one is
        // process-global, not per-test. A `MainActivity` that wrote
        // state outside composition and was torn down before Compose's
        // apply-notification runnable reached a paused Robolectric
        // looper leaves `hasPendingChanges` true for the life of the
        // JVM — so the next `createComposeRule` test in some unrelated
        // class spins in `advanceTimeByFrame` forever. Draining the
        // looper and sending the notifications ourselves ends it here.
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        Dispatchers.resetMain()
    }

    private var controller: ActivityController<MainActivity>? = null

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
        controller = built
        val realActivity = built.get()
        realActivity.useForTesting(fakeApi(seen))
        built.create().start().resume()
        settle(realActivity)

        val loaded = realActivity.stateForTesting()
        // Ask why before asserting what. Production swallows a facet
        // failure on purpose, so without this the only thing this test
        // could ever say was "nothing arrived".
        realActivity.facetsError?.let { throw AssertionError("the facet load threw: $it", it) }
        assertTrue(realActivity.facetsJob != null, "loadFacets never ran — facetsJob is still null after onCreate")
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
        settle(realActivity)
        assertEquals(
            requestsAfterFirstLoad,
            seen.size,
            "a second load re-fetched facets instead of guarding on facets.loaded",
        )
    }

}
