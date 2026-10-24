package org.mattshoe.mtg.android

import android.os.Looper
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.Change
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.NameCheck
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.Validation
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A finished entry flow is over. It does not ask for more.
 *
 * Matt: "when a 'entry' flow of any kind finishes, DO NOT fucking ask
 * for more you dipshit just exit the fucking flow back to home or
 * finish the activity or something".
 *
 * Through the real `MainActivity` over a fake network, because what
 * happens after the write comes back is the activity's own code: the
 * entry wizard sat on a receipt that said "Enter more", and the deck
 * wizard on a "Created" step with a Done button. Each has its failing
 * twin, so a refused write is seen to leave the person in the wizard
 * with the spinner gone, the reason said, and the button live again.
 * Sibling of the web's `AppDriverTest` cases of the same names.
 */
@RunWith(AndroidJUnit4::class)
class EntryFlowsLeaveWhenDoneTest {

    private var controller: ActivityController<MainActivity>? = null

    /** Makes the real writes fail with a 500. Dry runs and reads still answer. */
    private var writesFail = false

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    private fun client() = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val sent = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            if (writesFail && (path == "/cards/add" || path == "/decks/create")) {
                return@MockEngine respond(
                    """{"error":"the database is down"}""",
                    HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val body = when {
                path == "/cards/add" ->
                    """{"applied":true,"dry_run":false,"resolved":1,"failed":0,""" +
                        """"changes":[["Sol Ring","M3C","409","nonfoil",0,4]],"errors":[]}"""
                path == "/decks/create" ->
                    """{"created":true,"applied":true,"key":"q8ytka9m","card_count":1,"rows":1,""" +
                        """"deck":{"key":"q8ytka9m","name":"Test Deck"}}"""
                path == "/auth/me" -> """{"key":"e7de0cb1","name":"Matt","role":"admin"}"""
                "FROM decks" in sent ->
                    """{"cols":["key","name","owner"],"rows":[["q8ytka9m","Test Deck","e7de0cb1"]],"n":1}"""
                else -> """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun launch(): MainActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString(AdminToken.KEY, "t").commit()
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controller = built
        val activity = built.get()
        activity.useForTesting(MtgApi.withEngine("https://example.invalid", client()))
        built.create().start().resume()
        settleUntil(activity) { true }
        return activity
    }

    /** Pump the main looper until [done] holds, for two seconds at most. */
    private fun settleUntil(activity: MainActivity, done: (AppState) -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            looper.idle()
            if (done(activity.stateForTesting())) {
                looper.idle()
                return
            }
            Thread.sleep(10)
        }
        looper.idle()
    }

    /** The entry wizard on its review step, a dry run back and Apply armed. */
    private fun reviewing(activity: MainActivity): AppState {
        val s = activity.stateForTesting().navigate(View.ENTRY)
        val preview = Applied(dryRun = true, changes = listOf(Change("Sol Ring", "M3C", "409", "nonfoil", 0, 4)))
        val entry = MassEntry(direction = Direction.ADD).type("4 Sol Ring").previewed(preview)
        assertTrue(entry.canApply, "the fixture cannot apply")
        return s.copy(entry = entry)
    }

    /** The deck wizard over the entry wizard, names checked and Create armed. */
    private fun aboutToCreate(activity: MainActivity): AppState {
        val s = activity.stateForTesting().navigate(View.ENTRY)
            .copy(entry = MassEntry().type("1 Sol Ring").startADeck())
            .startingADeck()
        val deck = NewDeck(
            format = Format.COMMANDER,
            name = "Test Deck",
            commander = "Alela, Cunning Conqueror",
            list = "1 Sol Ring",
            checked = Validation(ok = true, checked = 1, cards = listOf(NameCheck("Sol Ring", "sol ring", ok = true))),
        )
        assertTrue(deck.canCreate, "the fixture cannot create")
        return s.copy(newDeck = deck)
    }

    @Test
    fun aWriteThatLandsLeavesTheWizardForTheLibrary() {
        val activity = launch()
        activity.setStateForTesting(reviewing(activity))
        activity.applyEntryForTesting()
        settleUntil(activity) { it.entry.busy == null }

        val s = activity.stateForTesting()
        assertEquals(View.LIBRARY, s.view, "it stayed in the wizard after the write landed")
        assertEquals(MassEntry(), s.entry, "the finished run is still in the wizard for next time")
        assertEquals("Added 4 copies of 1 printing", s.toast, "nothing said what the write did")
        assertFalse(s.toastFailed, "a write that landed is styled as a failure")
        assertEquals(1, s.history.entries.size, "the write was not recorded in the history")
    }

    @Test
    fun aRefusedWriteStaysInTheWizardAndSaysWhy() {
        writesFail = true
        val activity = launch()
        activity.setStateForTesting(reviewing(activity))
        activity.applyEntryForTesting()
        settleUntil(activity) { it.entry.busy == null }

        val s = activity.stateForTesting()
        assertEquals(View.ENTRY, s.view, "a refused write left the wizard anyway")
        assertNull(s.entry.busy, "still spinning after the refusal")
        assertTrue(s.entry.error.orEmpty().contains("the database is down"), "nothing says what happened: ${s.entry.error}")
        assertEquals(Step.REVIEW, s.entry.step, "the review it could try again from is gone")
        assertTrue(s.entry.canApply, "there is no way to try again")
    }

    @Test
    fun aCreatedDeckClosesTheWizardAndOpensTheDeck() {
        val activity = launch()
        activity.setStateForTesting(aboutToCreate(activity))
        activity.createDeckForTesting()
        settleUntil(activity) { it.newDeck.busy == null && !it.decks.busy }

        val s = activity.stateForTesting()
        assertFalse(Overlay.NEW_DECK in s.overlays, "the deck wizard is still open over the deck")
        assertEquals(Route(View.DECKS, "q8ytka9m"), s.route, "it did not land on the deck it made")
        assertEquals("Created Test Deck", s.toast, "nothing said the deck was made")
        assertEquals(MassEntry(), s.entry, "the entry wizard still holds the list the deck was made from")
    }

    @Test
    fun aRefusedCreateKeepsTheWizardAndSaysWhy() {
        writesFail = true
        val activity = launch()
        activity.setStateForTesting(aboutToCreate(activity))
        activity.createDeckForTesting()
        settleUntil(activity) { it.newDeck.busy == null }

        val s = activity.stateForTesting()
        assertTrue(Overlay.NEW_DECK in s.overlays, "a refused create closed the wizard anyway")
        assertNull(s.newDeck.busy, "still spinning after the refusal")
        assertTrue(s.newDeck.error.orEmpty().contains("the database is down"), "nothing says what happened: ${s.newDeck.error}")
        assertTrue(s.newDeck.canCreate, "there is no way to try again")
    }
}
