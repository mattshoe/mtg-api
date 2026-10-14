package org.mattshoe.mtg.android

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Scryfall
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.onFetchList
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Fetch list through the real `MainActivity`: Find asks the Worker
 * which printings are owned and Scryfall which rarities exist, and the
 * answer is filed by type, colour and rarity. Saving keeps it across a
 * fresh launch.
 *
 * Sibling of the web's `FetchListJourneyTest`.
 */
@RunWith(AndroidJUnit4::class)
class FetchListLoadTest {

    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val asked = mutableListOf<String>()

    /** Scryfall holds every answer until this completes, when set. */
    private var scryfallGate: CompletableDeferred<Unit>? = null

    /** The Worker answers the owned printings with a 500 while this is true. */
    private var ownedFails = false

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
    }

    private fun http() = HttpClient(
        MockEngine { request ->
            val sql = (request.body as? TextContent)?.text.orEmpty()
            val q = request.url.parameters["q"].orEmpty()
            asked += if (sql.isNotEmpty()) sql else q
            if (request.url.encodedPath.endsWith("/cards/search")) scryfallGate?.await()
            if ("json_each" in sql && ownedFails) {
                return@MockEngine respond(
                    """{"error":"D1 is down"}""",
                    HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val body = when {
                "json_each" in sql ->
                    """{"cols":["name_norm","name","face1","types","colors","color_identity","rarity","qty"],"rows":[
                        ["counterspell","Counterspell","Counterspell","Instant","U","U","common",2],
                        ["arcane signet","Arcane Signet","Arcane Signet","Artifact",null,null,"common",1]
                    ],"n":2}"""
                request.url.encodedPath.endsWith("/cards/search") && "r:uncommon" in q ->
                    """{"data":[{"name":"Arcane Signet"}]}"""
                request.url.encodedPath.endsWith("/cards/search") && "r:common" in q ->
                    """{"data":[{"name":"Arcane Signet"},{"name":"Counterspell"}]}"""
                request.url.encodedPath.endsWith("/cards/search") ->
                    """{"object":"error","code":"not_found","status":404}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun launch(): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controllers += built
        val activity = built.get()
        val client = http()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client))
        activity.useScryfallForTesting(Scryfall.withEngine(client))
        built.create().start().resume()
        return activity
    }

    private fun idleUntil(cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (cond()) return
            Thread.sleep(10)
        }
        looper.idle()
    }

    private val me = Admin().signIn(Account(key = "e7de0cb1"), "t")

    private fun found(activity: MainActivity) {
        activity.setStateForTesting(
            AppState(admin = me, route = Route(View.FETCH)).copy(
                fetch = FetchListState(draft = "1 Counterspell\n1 Arcane Signet\n1 Black Lotus"),
            ),
        )
        activity.fetchForTesting()
        idleUntil { activity.stateForTesting().fetch.result != null }
        if (scryfallGate == null) idleUntil { !activity.stateForTesting().fetch.checking }
    }

    @Test
    fun findFilesEveryCardAndSaysWhatIsMissing() {
        val activity = launch()
        found(activity)
        val r = activity.stateForTesting().fetch.result
        assertEquals(
            listOf("Instants · U · Common", "Artifacts · C · Common"),
            r?.sections?.map { it.title },
            "the list was not filed; the Worker was asked ${asked.size} things",
        )
        assertEquals(listOf("Black Lotus"), r?.missing?.map { it.name })
        assertTrue(asked.any { "json_each" in it && "e7de0cb1" in it }, "the owned printings were never asked for")
    }

    @Test
    fun theFilingLandsWhileScryfallIsStillThinking() {
        // Matt: "it takes several full minutes to run". Those minutes
        // were Scryfall's, and the whole list waited for them.
        val gate = CompletableDeferred<Unit>()
        scryfallGate = gate
        try {
            val activity = launch()
            found(activity)
            val held = activity.stateForTesting().fetch
            assertEquals(
                listOf("Instants · U · Common", "Artifacts · C · Common"),
                held.result?.sections?.map { it.title },
                "nothing was filed while Scryfall had not answered",
            )
            assertTrue(held.checking, "nothing says the Also check is still coming")
            assertTrue(!held.busy, "Find cards is still busy with the list on screen")

            gate.complete(Unit)
            idleUntil { !activity.stateForTesting().fetch.checking }
            val signet = activity.stateForTesting().fetch.result?.sections?.flatMap { it.cards }
                ?.firstOrNull { it.name == "Arcane Signet" }
            assertEquals("Also check: Uncommon", signet?.alsoCheckLine, "the Also check never followed")
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun aRarityScryfallKnowsIsAnAlsoCheck() {
        val activity = launch()
        found(activity)
        val signet = activity.stateForTesting().fetch.result?.sections?.flatMap { it.cards }
            ?.firstOrNull { it.name == "Arcane Signet" }
        assertEquals("Also check: Uncommon", signet?.alsoCheckLine)
    }

    @Test
    fun aSavedListNeedsATitleAndIsThereOnTheNextLaunch() {
        val activity = launch()
        found(activity)
        activity.saveFetchForTesting()
        assertEquals("Give it a title first", activity.stateForTesting().fetch.error)
        assertTrue(activity.stateForTesting().fetch.saved.isEmpty(), "it saved without a title")

        activity.setStateForTesting(
            activity.stateForTesting().let { it.copy(fetch = it.fetch.titled("Alela")) },
        )
        activity.saveFetchForTesting()
        assertEquals(listOf("Alela"), activity.stateForTesting().fetch.saved.map { it.title })

        val again = launch()
        idleUntil { again.stateForTesting().fetch.saved.isNotEmpty() }
        assertEquals(
            listOf("Alela"),
            again.stateForTesting().fetch.saved.map { it.title },
            "the saved list did not survive a fresh launch",
        )
    }

    @Test
    fun findGoesOntoTheListPage() {
        val activity = launch()
        found(activity)
        assertTrue(activity.stateForTesting().onFetchList, "Find stayed on the root")
    }

    @Test
    fun aFailedFindClearsTheLoadingViewSaysWhyAndCanBeTriedAgain() {
        ownedFails = true
        val activity = launch()
        activity.setStateForTesting(
            AppState(admin = me, route = Route(View.FETCH)).copy(
                fetch = FetchListState(draft = "1 Counterspell\n1 Arcane Signet"),
            ),
        )
        activity.fetchForTesting()
        idleUntil { activity.stateForTesting().fetch.error != null }
        val failed = activity.stateForTesting()
        assertTrue(!failed.fetch.busy, "the loading view is still up after a 500")
        assertTrue(failed.fetch.error.orEmpty().isNotBlank(), "nothing says what happened")
        assertTrue(failed.onFetchList, "the failure left the list page")

        ownedFails = false
        activity.fetchForTesting()
        idleUntil { activity.stateForTesting().fetch.result != null }
        assertEquals(
            listOf("Instants · U · Common", "Artifacts · C · Common"),
            activity.stateForTesting().fetch.result?.sections?.map { it.title },
            "trying again did not find the list",
        )
        assertEquals(null, activity.stateForTesting().fetch.error, "the error stayed after it worked")
    }

    @Test
    fun aTickIsThereOnTheNextLaunch() {
        val activity = launch()
        found(activity)
        activity.setStateForTesting(
            activity.stateForTesting().let { it.copy(fetch = it.fetch.tick("Counterspell")) },
        )
        val again = launch()
        idleUntil { again.stateForTesting().fetch.result != null }
        assertTrue(again.stateForTesting().fetch.isTicked("Counterspell"), "the tick did not survive a fresh launch")
        assertTrue(!again.stateForTesting().fetch.isTicked("Arcane Signet"))
    }
}
