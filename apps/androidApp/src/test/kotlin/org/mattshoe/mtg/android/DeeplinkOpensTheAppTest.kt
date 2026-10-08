package org.mattshoe.mtg.android

import org.junit.Rule
import android.content.Intent
import android.net.Uri
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
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import androidx.test.core.app.ApplicationProvider
import android.content.pm.PackageManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tapping an `mtg.mattshoe.org` link opens the app on that thing.
 *
 * Matt: "I want the Android app to support deeplinks for
 * mtg.mattshoe.org".
 *
 * Where the link *goes* is `DeeplinkTest` in `:core`, which is where
 * the URL parsing lives and where it can be tested without a device.
 * This is the other half: that the activity is actually offered the
 * link by the system, and that it acts on one when it arrives — both
 * cold, through `onCreate`, and warm, through `onNewIntent`, because
 * `MainActivity` is `singleTask` and a second link arrives at the
 * running instance rather than a new one.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DeeplinkOpensTheAppTest {

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

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun launch(intent: Intent? = null): MainActivity {
        val built =
            if (intent == null) Robolectric.buildActivity(MainActivity::class.java)
            else Robolectric.buildActivity(MainActivity::class.java, intent)
        controllers += built
        built.get().useForTesting(fakeApi())
        built.create().start().resume()
        settle()
        return built.get()
    }

    private fun settle() = repeat(40) { shadowOf(Looper.getMainLooper()).idle() }

    @Test
    fun theSystemOffersThisAppMtgLinks() {
        // The manifest half. Without an intent filter that resolves,
        // nothing below matters — the link opens a browser and the
        // app never hears about it.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val matches = context.packageManager.queryIntentActivities(
            viewIntent("https://mtg.mattshoe.org/#/decks/alela"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            matches.any { it.activityInfo?.name == MainActivity::class.java.name },
            "no activity in this app answers an mtg.mattshoe.org link",
        )
    }

    @Test
    fun aDeckLinkOpensTheDeckOnAColdStart() {
        val activity = launch(viewIntent("https://mtg.mattshoe.org/#/decks/alela"))
        val state = activity.stateForTesting()
        assertEquals(View.DECKS, state.view, "a deck link did not open the decks view")
        assertEquals("alela", state.route.rest, "a deck link did not name the deck")
    }

    @Test
    fun aCardLinkOpensTheCard() {
        val activity = launch(viewIntent("https://mtg.mattshoe.org/#/card/matt:sol+ring"))
        assertEquals(View.CARD, activity.stateForTesting().view)
    }

    @Test
    fun aSearchLinkArrivesWithItsFilters() {
        val activity = launch(viewIntent("https://mtg.mattshoe.org/#/search?q=bolt"))
        val state = activity.stateForTesting()
        assertEquals(View.LIBRARY, state.view)
        assertEquals("bolt", state.library.filters.q, "the shared search lost its own query")
    }

    @Test
    fun aSecondLinkReachesTheAppThatIsAlreadyOpen() {
        // `MainActivity` is `singleTask`, so the second link does not
        // start a second activity — it arrives at the live one
        // through `onNewIntent`. An app that only reads the intent in
        // `onCreate` ignores every link after the first and looks
        // broken in exactly the way that is hardest to report.
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controllers += built
        built.get().useForTesting(fakeApi())
        built.create().start().resume()
        settle()
        assertEquals(View.LIBRARY, built.get().stateForTesting().view)

        built.newIntent(viewIntent("https://mtg.mattshoe.org/#/decks/alela"))
        settle()

        val state = built.get().stateForTesting()
        assertEquals(View.DECKS, state.view, "a link to an already-open app did nothing")
        assertEquals("alela", state.route.rest)
    }

    @Test
    fun aPlainLaunchStillOpensWhereItAlwaysDid() {
        // No intent data at all, which is the icon on the home screen.
        assertEquals(View.LIBRARY, launch().stateForTesting().view)
    }

    @Test
    fun somebodyElsesLinkDoesNotMoveTheApp() {
        // Belt and braces with the manifest: if an intent for another
        // host ever reaches this activity, it is ignored rather than
        // parsed for whatever happens to be after the hash.
        val activity = launch(viewIntent("https://example.com/#/decks/alela"))
        assertEquals(
            View.LIBRARY,
            activity.stateForTesting().view,
            "a link from another site navigated the app",
        )
    }
}
