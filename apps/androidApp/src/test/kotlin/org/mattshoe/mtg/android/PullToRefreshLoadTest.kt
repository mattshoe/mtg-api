package org.mattshoe.mtg.android

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pull, all the way to the network and back, on the real activity.
 *
 * `PullToRefreshTest` proves the gesture reaches `onRefresh`. This
 * proves `onRefresh` asks the server again and that the spinner goes
 * away when the answer lands — the half a screen test cannot see,
 * because it hands `AppShell` a lambda.
 */
@RunWith(AndroidJUnit4::class)
class PullToRefreshLoadTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private var controller: ActivityController<MainActivity>? = null

    /** Every query the app sent, by its SQL. */
    private val asked = mutableListOf<String>()

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun fakeApi() = MtgApi.withEngine(
        "https://example.invalid",
        HttpClient(
            MockEngine { request ->
                val text = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                synchronized(asked) { asked += text }
                respond(
                    """{"cols":[],"rows":[],"n":0}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    )

    private fun count(fragment: String) = synchronized(asked) { asked.count { fragment in it } }

    /** Pump the main looper until [done], or give up after two seconds. */
    private fun pumpUntil(done: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            looper.idle()
            if (done()) return
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun start(on: (AppState) -> AppState): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controller = built
        val activity = built.get()
        activity.useForTesting(fakeApi())
        built.create().start().resume()
        pumpUntil { false }
        val signedIn = activity.stateForTesting().copy(
            admin = activity.stateForTesting().admin.signIn(Account("matt"), session = "t").settle(),
        )
        activity.setStateForTesting(on(signedIn))
        return activity
    }

    private fun pullAndLand(activity: MainActivity, fragment: String, page: String) {
        val before = count(fragment)
        activity.refreshForTesting()
        assertTrue(activity.stateForTesting().refreshing, "a pull on $page left nothing spinning")
        pumpUntil { count(fragment) > before && !activity.stateForTesting().refreshing }
        assertTrue(count(fragment) > before, "a pull on $page asked the server nothing")
        val after = activity.stateForTesting()
        assertFalse(after.refreshing, "the answer landed and $page is still spinning")
        assertNull(after.pulled, "the pull on $page outlived its answer")
    }

    @Test
    fun aPulledLibraryAsksForItsCardsAgain() {
        val activity = start { it.navigate(View.LIBRARY) }
        pullAndLand(activity, "MIN(c.id) AS id", "the Library")
    }

    @Test
    fun aPulledCardAsksForTheCardAgain() {
        val activity = start {
            it.navigate(Route(View.CARD, "sol+ring"))
                .copy(card = CardDetail(name = "Sol Ring", nameNorm = "sol ring"))
        }
        pullAndLand(activity, "FROM card_faces cf JOIN pick", "a card")
    }

    @Test
    fun aPulledDeckAsksForItsCardsAgain() {
        val activity = start { it.navigate(Route(View.DECKS, "alela")) }
        pullAndLand(activity, "FROM deck_cards", "an open deck")
    }

    @Test
    fun aPullOnEntryLeavesNothingSpinning() {
        val activity = start { it.navigate(View.ENTRY) }
        activity.refreshForTesting()
        assertFalse(activity.stateForTesting().refreshing, "a pull on Entry spins with nothing coming")
        assertNull(activity.stateForTesting().pulled)
    }
}
