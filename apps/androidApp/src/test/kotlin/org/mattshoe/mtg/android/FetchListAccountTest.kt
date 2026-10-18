package org.mattshoe.mtg.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.FetchListState
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
 * Saved Fetch lists are the account's, through the real `MainActivity`.
 *
 * Matt: "making a fetch list on web does not show on android and vice
 * versa". The Worker here is a stub that keeps what it is sent, so a
 * list saved on one launch is read back by another with its storage
 * wiped — another device, as far as the app can tell.
 *
 * Sibling of the web's `FetchListAccountTest`.
 */
@RunWith(AndroidJUnit4::class)
class FetchListAccountTest {

    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    /** What the stub Worker holds for the account: the JSON array. */
    @Volatile private var account = "[]"
    @Volatile private var loadFails = false
    @Volatile private var saveFails = false
    private val sent = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val prefs get() =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mtg", Context.MODE_PRIVATE)

    @Before
    fun signedIn() {
        prefs.edit().clear().putString(AdminToken.KEY, "t").commit()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
    }

    private fun saved(id: String, title: String) =
        """{"id":"$id","title":"$title","at":"2026-10-17T00:00:00Z","list":"1 Counterspell","result":null,"ticked":[]}"""

    private fun http() = HttpClient(
        MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            val ok = headersOf(HttpHeaders.ContentType, "application/json")
            val path = request.url.encodedPath
            if (path.endsWith("/fetch-lists")) {
                if (request.method == HttpMethod.Get) {
                    return@MockEngine if (loadFails) respond("""{"error":"D1 is down"}""", HttpStatusCode.InternalServerError, ok)
                    else respond("""{"lists":$account}""", HttpStatusCode.OK, ok)
                }
                sent += body
                if (saveFails) return@MockEngine respond("""{"error":"D1 is down"}""", HttpStatusCode.InternalServerError, ok)
                account = Json.parseToJsonElement(body).jsonObject["lists"].toString()
                return@MockEngine respond("""{"ok":true}""", HttpStatusCode.OK, ok)
            }
            val json = when {
                path.endsWith("/auth/me") -> """{"key":"e7de0cb1","name":"Matt","role":"user"}"""
                "json_each" in body ->
                    """{"cols":["name_norm","name","face1","types","colors","color_identity","rarity","qty"],"rows":[
                        ["counterspell","Counterspell","Counterspell","Instant","U","U","common",2]],"n":1}"""
                path.endsWith("/cards/search") -> """{"object":"error","code":"not_found","status":404}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(json, HttpStatusCode.OK, ok)
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun launch(): MainActivity {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://mtg.mattshoe.org/#/fetch"))
        val built = Robolectric.buildActivity(MainActivity::class.java, link)
        controllers += built
        val activity = built.get()
        val client = http()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client))
        activity.useScryfallForTesting(Scryfall.withEngine(client))
        built.create().start().resume()
        return activity
    }

    /** Another device: nothing in its storage, the same account. */
    private fun anotherDevice(): MainActivity {
        tearDown()
        prefs.edit().clear().putString(AdminToken.KEY, "t").commit()
        return launch()
    }

    private fun idleUntil(what: String, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (cond()) return
            Thread.sleep(10)
        }
        looper.idle()
        if (!cond()) error("gave up waiting for $what")
    }

    private fun titles(a: MainActivity) = a.stateForTesting().fetch.saved.map { it.title }

    private fun findAndSave(activity: MainActivity, title: String) {
        idleUntil("the account to be known") { activity.stateForTesting().admin.signedIn }
        activity.setStateForTesting(
            activity.stateForTesting().let { it.copy(fetch = it.fetch.drafted("1 Counterspell")) },
        )
        activity.fetchForTesting()
        idleUntil("the list to be filed") { activity.stateForTesting().fetch.result != null }
        activity.setStateForTesting(activity.stateForTesting().let { it.copy(fetch = it.fetch.titled(title)) })
        activity.saveFetchForTesting()
    }

    @Test
    fun aListSavedOnTheWebsiteIsOnThePhone() {
        account = "[" + saved("w1", "From the website") + "]"
        val activity = launch()
        idleUntil("the website's list on the phone") { "From the website" in titles(activity) }
    }

    @Test
    fun aListSavedOnThePhoneIsOnTheNextDevice() {
        val activity = launch()
        findAndSave(activity, "Alela")
        idleUntil("the list to be sent to the account") { sent.any { "Alela" in it } }

        val other = anotherDevice()
        idleUntil("the phone's list on another device") { "Alela" in titles(other) }
    }

    @Test
    fun aListSavedHereBeforeSyncingIsKeptAndSentUp() {
        prefs.edit().putString(FetchListState.KEY, "[" + saved("h1", "Only here") + "]").commit()
        account = "[" + saved("w1", "From the website") + "]"
        val activity = launch()
        idleUntil("both lists") { titles(activity).containsAll(listOf("From the website", "Only here")) }
        idleUntil("this phone's list to be sent up") { sent.any { "Only here" in it && "From the website" in it } }
    }

    @Test
    fun whenTheListsCannotLoadItSaysSoKeepsTheseAndTheFetchPageAsksAgain() {
        prefs.edit()
            .putString(FetchListState.KEY, "[" + saved("h1", "Only here") + "]")
            .putString(FetchListState.SYNCED, "e7de0cb1")
            .commit()
        loadFails = true
        account = "[" + saved("w1", "From the website") + "]"
        val activity = launch()
        idleUntil("a failure toast") { activity.stateForTesting().toastFailed }
        assertEquals("Your saved Fetch lists did not load: D1 is down", activity.stateForTesting().toast)
        assertEquals(listOf("Only here"), titles(activity), "this phone's lists went with the failed load")
        Thread.sleep(FetchListState.SEND_MS * 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty(), "this phone's copy was written over the account's before it had been read: $sent")

        // Try again, without restarting: leave the page and come back.
        loadFails = false
        activity.setStateForTesting(activity.stateForTesting().navigate(Route(View.LIBRARY)))
        activity.setStateForTesting(activity.stateForTesting().navigate(Route(View.FETCH)))
        activity.loadForTesting()
        idleUntil("the account's lists on a second visit") { "From the website" in titles(activity) }
        assertEquals(listOf("From the website"), titles(activity))
    }

    @Test
    fun whenASaveCannotReachTheAccountItSaysSoAndTheListIsStillSavedHere() {
        saveFails = true
        val activity = launch()
        findAndSave(activity, "Alela")
        idleUntil("a failure toast") { activity.stateForTesting().toast?.contains("did not save to your account") == true }
        assertEquals("Your Fetch lists did not save to your account: D1 is down", activity.stateForTesting().toast)
        assertTrue(activity.stateForTesting().toastFailed, "a failure toast styled as a success")
        assertEquals(listOf("Alela"), titles(activity), "the list was not kept on the phone")
        assertTrue(!account.contains("Alela"), "the stub kept a save it refused")

        // The next change — a tick — sends them all again.
        saveFails = false
        activity.setStateForTesting(activity.stateForTesting().let { it.copy(fetch = it.fetch.tick("Counterspell")) })
        idleUntil("the lists sent again after the failure") { account.contains("Alela") }
    }
}
