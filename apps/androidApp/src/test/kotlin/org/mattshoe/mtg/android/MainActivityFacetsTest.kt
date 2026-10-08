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
import org.junit.After
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

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()


    /**
     * Pump Robolectric's main looper until the load has finished.
     *
     * No test dispatcher. That is the whole fix, and the four
     * attempts before it all failed because they kept one.
     *
     * In production `lifecycleScope.launch` resumes on
     * `Dispatchers.Main.immediate`, so every continuation of every
     * load — and every `app = app.copy(...)` that ends one — runs on
     * the single main thread. Two loads in flight interleave only at
     * suspension points and can never write at the same moment.
     * `onCreate` starts exactly two: the view's own fetch and this
     * facet load.
     *
     * Swapping in an unconfined dispatcher threw that away. Ktor
     * suspends on its own dispatcher and an unconfined resumption
     * runs wherever the call happened to finish, so the two loads
     * resumed on two different background threads and both did a
     * read-modify-write of `app` with nothing between them. Whichever
     * read first and wrote last erased the other. That is a genuine
     * lost update, and it explains everything the symptom did: one
     * run in several, only under load, passing alone, and reporting
     * "facets never loaded" while the load itself had plainly
     * succeeded.
     *
     * Idling the real looper instead reproduces production's
     * threading exactly: every continuation lands back on the main
     * thread and waits its turn. Bounded, so a real hang still fails.
     */
    private fun settle(activity: MainActivity? = null) {
        val looper = shadowOf(Looper.getMainLooper())
        val job = activity?.facetsJob
        repeat(SETTLE_TRIES) {
            looper.idle()
            if (job == null || job.isCompleted) {
                looper.idle()
                return
            }
            Thread.sleep(SETTLE_STEP_MS)
        }
        looper.idle()
    }

    private companion object {
        /** Two seconds in total, which is twenty times the honest cost. */
        const val SETTLE_TRIES = 200
        const val SETTLE_STEP_MS = 10L
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
        val job = realActivity.facetsJob
        assertTrue(job != null, "loadFacets never ran — facetsJob is still null after onCreate")
        // Four distinct stories used to arrive as the one sentence
        // "facets never loaded": the load never started, it is still
        // running, it was cancelled, or it failed. Each says its own
        // name now.
        assertTrue(job!!.isCompleted, "the facet load had not finished when the test looked")
        assertTrue(!job.isCancelled, "the facet load was cancelled before it could finish")
        assertTrue(
            loaded.facets.loaded,
            if (realActivity.facetsApplied) {
                "the facet load wrote its result and something overwrote it afterwards — " +
                    "a lost update between two coroutines, not a failed load"
            } else {
                "facets never loaded — the load finished without writing anything"
            },
        )
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
