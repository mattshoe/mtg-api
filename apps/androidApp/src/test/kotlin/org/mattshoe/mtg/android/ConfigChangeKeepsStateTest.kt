package org.mattshoe.mtg.android

import org.junit.Rule
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
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
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rotating the phone does not empty the app.
 *
 * It did. `MainActivity` held the whole `AppState` in a field —
 * `private var held by mutableStateOf(AppState())` — and a field dies
 * with the activity. Android destroys and recreates the activity on
 * every configuration change: a rotation, the keyboard appearing, the
 * user changing the font size or switching to dark mode. All of it
 * threw away the route, the filters, the search results, the open
 * deck, the unsaved mass-entry list and the unlock.
 *
 * Matt: "Configuration changes wipe out all state in the Android app."
 *
 * Driven through the real `ActivityController`, because this is
 * entirely about the lifecycle. `recreate()` is what the framework
 * does: `onSaveInstanceState`, `onDestroy`, then a fresh `onCreate`
 * with the retained non-configuration state carried across — which is
 * the exact seam a `ViewModel` lives in and a field does not.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ConfigChangeKeepsStateTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    @After
    fun tearDown() {
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val empty = """{"cols":[],"rows":[],"n":0}"""

    /** Nothing real, because the lifecycle is the subject. */
    private fun fakeApi(): MtgApi {
        val engine = MockEngine {
            respond(
                content = empty,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi.withEngine("https://example.invalid", client)
    }

    private fun launch(): ActivityController<MainActivity> {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controllers += built
        built.get().useForTesting(fakeApi())
        built.create().start().resume()
        settle()
        return built
    }

    private fun settle() {
        repeat(40) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** What the framework does on a rotation. */
    private fun rotate(built: ActivityController<MainActivity>): MainActivity {
        built.recreate()
        settle()
        return built.get()
    }

    @Test
    fun theRouteSurvivesARotation() {
        val built = launch()
        built.get().setStateForTesting(
            built.get().stateForTesting().navigate(Route(View.DECKS, "alela")),
        )
        settle()
        assertEquals(View.DECKS, built.get().stateForTesting().view)

        val after = rotate(built).stateForTesting()
        assertEquals(
            View.DECKS,
            after.view,
            "a rotation sent the app back to the Library — the route was in an activity field",
        )
        assertEquals("alela", after.route.rest, "the open deck was forgotten")
    }

    @Test
    fun theSearchAndItsFiltersSurviveARotation() {
        val built = launch()
        val searched = built.get().stateForTesting().let {
            it.copy(library = it.library.copy(filters = Filters(q = "bolt", colors = listOf("R"))))
        }
        built.get().setStateForTesting(searched)
        settle()

        val after = rotate(built).stateForTesting()
        // Retyping a search because the phone turned over is the
        // single most annoying version of this bug.
        assertEquals("bolt", after.library.filters.q, "the search box was emptied by a rotation")
        assertEquals(
            listOf("R"),
            after.library.filters.colors,
            "the colour filter was cleared by a rotation",
        )
    }

    @Test
    fun anUnsavedMassEntryListSurvivesARotation() {
        val built = launch()
        val typed = built.get().stateForTesting().let {
            it.copy(entry = it.entry.type("4 Lightning Bolt\n1 Sol Ring"))
        }
        built.get().setStateForTesting(typed)
        settle()

        val after = rotate(built).stateForTesting()
        // `wouldExitWithUnsavedEntry` exists to stop back throwing
        // this away. A rotation was throwing it away with no warning
        // at all, which is worse.
        assertTrue(
            after.entry.list.contains("Lightning Bolt"),
            "a rotation discarded a pasted decklist that back refuses to discard",
        )
    }

    @Test
    fun theUnlockSurvivesARotation() {
        val built = launch()
        built.get().setStateForTesting(
            built.get().stateForTesting().copy(admin = Admin().signIn(Account(slug = "matt", role = "admin"), "t")),
        )
        settle()

        val after = rotate(built).stateForTesting()
        assertTrue(after.admin.unlocked, "a rotation locked the app again")
    }

    @Test
    fun theStateIsNotHeldByTheActivityAtAll() {
        // The structural version of all four above. Two activity
        // instances, one state: if the second instance reads the
        // first's state then the state does not live in the activity,
        // which is the only way any of this can hold.
        val built = launch()
        val first = built.get()
        first.setStateForTesting(first.stateForTesting().navigate(Route(View.STATS)))
        settle()

        val second = rotate(built)
        assertTrue(
            first !== second,
            "the activity was not actually recreated, so this proves nothing",
        )
        assertEquals(
            View.STATS,
            second.stateForTesting().view,
            "the new activity started from a blank state — it is still holding its own",
        )
    }
}
