package org.mattshoe.mtg.android

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.View
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.9 and 1.10: the decision Android makes about "Download", exercised
 * through a real `MainActivity` the way `MainActivityFacetsTest` does.
 *
 * `MainActivity.shareDeck` used to never look at the `ExportTo` it was
 * handed — both menu rows landed on the clipboard with the same toast.
 * `exportList` had the same shape of gap for the Library's own export.
 * These do not use `createComposeRule` — mixing that with
 * `Robolectric.buildActivity` in one class is what made a past suite
 * flaky — so they drive `MainActivity` through the internal test seams
 * instead of a tap on screen; the screen-level wiring (the Library
 * offering both rows, the share menu reaching the shell with the right
 * `ExportTo`) is covered in `ScreensTest` and `DeckActionsReachTheShellTest`.
 *
 * What these cannot check: that a byte actually lands in a real
 * Downloads folder. `Downloads` is a seam for exactly that reason —
 * see its own doc comment — and a fake stands in for
 * `MediaStoreDownloads` here. `MediaStoreDownloads` itself, the real
 * `MediaStore.Downloads` write, is unverified by any test in this
 * suite; it needs a device.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadDecisionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /**
     * Destroy the activity, not just the dispatcher.
     *
     * A resumed `MainActivity` owns a Compose root, and
     * `ComposeRootRegistry` holds every root that was created and never
     * detached — for the life of the JVM, across test classes. Compose's
     * Robolectric idling strategy busy-spins `advanceTimeByFrame` until
     * *every* registered root reports idle, so one leaked resumed
     * activity makes `waitForIdle()` spin forever in whichever
     * unrelated test happens to run next. That is exactly what
     * happened: six leaked activities from this class, and the first
     * `createComposeRule` test after it hung with no XML entry, so the
     * previous run's results were still on disk and the suite read as
     * green at the old count.
     */
    @After
    fun tearDown() {
        controllers.asReversed().forEach {
            runCatching { it.pause().stop().destroy() }
                .onFailure { e -> println("TEARDOWN-THREW: ${e::class.simpleName}: ${e.message}") }
        }
        controllers.clear()
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

    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun deck() = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun deckOpenState() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin().signIn(Account(slug = "matt", role = "admin"), "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(card("Alela, Artful Provocateur", "commander"), card("Sol Ring")),
        ),
    )

    private val decksBody = """{"cols":["slug","name","owner"],"rows":[],"n":0}"""
    private val facetsBody = """{"cols":["kind","value"],"rows":[],"n":0}"""

    // Matched on `LIMIT 5000`, the cap `Export.query` asks for and the
    // ordinary Library page never does — see `buildQuery`.
    private val exportBody = """
        {"cols":["name","name_norm","qty"],"rows":[["Sol Ring","sol ring",2],["Arcane Signet","arcane signet",1]],"n":2}
    """.trimIndent()

    private val emptyBody = """{"cols":[],"rows":[],"n":0}"""

    private fun fakeApi(): MtgApi {
        val engine = MockEngine { request ->
            val text = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val body = when {
                "FROM decks" in text -> decksBody
                "AS kind" in text -> facetsBody
                "LIMIT 5000" in text -> exportBody
                else -> emptyBody
            }
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi.withEngine("https://example.invalid", client)
    }

    private fun clipboardText(activity: MainActivity): String? {
        val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return if (clip.hasPrimaryClip()) clip.primaryClip?.getItemAt(0)?.text?.toString() else null
    }

    private fun launch(downloads: Downloads): MainActivity {
        val built = Robolectric.buildActivity(MainActivity::class.java)
        controllers += built
        val activity = built.get()
        activity.useForTesting(fakeApi())
        activity.useDownloadsForTesting(downloads)
        built.create().start().resume()
        settle()
        return activity
    }

    private class FakeDownloads(private val result: DownloadResult) : Downloads {
        val calls = mutableListOf<Pair<String, String>>()
        override fun save(name: String, text: String): DownloadResult {
            calls += name to text
            return result
        }
    }

    // ------------------------------------------------------- deck share

    @Test
    fun shareDeckDownloadAndCopyTakeDifferentPaths() {
        val downloads = FakeDownloads(DownloadResult.SAVED)
        val activity = launch(downloads)
        activity.setStateForTesting(deckOpenState())

        val downloaded = activity.shareDeckForTesting(ShareWhat.DECKLIST, ExportTo.FILE)
        assertEquals(1, downloads.calls.size, "Download never reached the downloader")
        assertEquals("2 cards exported", downloaded.toast, "the toast does not describe a download")
        assertNull(clipboardText(activity), "Download touched the clipboard — it is supposed to take the file route")

        val copied = activity.shareDeckForTesting(ShareWhat.DECKLIST, ExportTo.CLIPBOARD)
        assertEquals(1, downloads.calls.size, "Copy reached the downloader — it is supposed to skip it entirely")
        assertEquals("2 cards copied", copied.toast, "the toast does not describe a copy")
        assertNotNull(clipboardText(activity), "Copy never reached the clipboard")
    }

    @Test
    fun shareDeckLinkDownloadAndCopyTakeDifferentPaths() {
        val downloads = FakeDownloads(DownloadResult.SAVED)
        val activity = launch(downloads)
        activity.setStateForTesting(deckOpenState())

        val downloaded = activity.shareDeckForTesting(ShareWhat.LINK, ExportTo.FILE)
        assertEquals(1, downloads.calls.size)
        assertEquals("Link downloaded", downloaded.toast)

        val copied = activity.shareDeckForTesting(ShareWhat.LINK, ExportTo.CLIPBOARD)
        assertEquals(1, downloads.calls.size, "Copy reached the downloader")
        assertEquals("Link copied", copied.toast)
    }

    @Test
    fun shareDeckFallsBackHonestlyWhenTheOsCannotDownload() {
        // minSdk is 26; `MediaStoreDownloads` refuses below API 29.
        // Below that there is no file route at all, so this is the
        // decision for every phone older than Android 10.
        val downloads = FakeDownloads(DownloadResult.UNSUPPORTED_OS)
        val activity = launch(downloads)
        activity.setStateForTesting(deckOpenState())

        val result = activity.shareDeckForTesting(ShareWhat.DECKLIST, ExportTo.FILE)
        assertEquals(1, downloads.calls.size, "it never even asked for a download")
        assertEquals(
            "2 cards copied — downloads need Android 10 or newer",
            result.toast,
            "the fallback toast must say a copy happened, not a download",
        )
        assertNotNull(clipboardText(activity), "the fallback did not actually copy anything")
    }

    @Test
    fun shareDeckFallsBackHonestlyWhenTheWriteFails() {
        val downloads = FakeDownloads(DownloadResult.FAILED)
        val activity = launch(downloads)
        activity.setStateForTesting(deckOpenState())

        val result = activity.shareDeckForTesting(ShareWhat.DECKLIST, ExportTo.FILE)
        assertEquals(
            "could not save the file — copied instead",
            result.toast,
            "a real write failure must not be reported as success",
        )
        assertNotNull(clipboardText(activity))
    }

    // ---------------------------------------------------- library export

    @Test
    fun libraryExportDownloadAndCopyTakeDifferentPaths() {
        val downloads = FakeDownloads(DownloadResult.SAVED)
        val activity = launch(downloads)

        val downloaded = runBlocking { activity.exportListForTesting(ExportTo.FILE) }
        assertEquals(1, downloads.calls.size, "Download never reached the downloader")
        assertEquals("Exported 2 cards", downloaded.toast)
        assertNull(clipboardText(activity), "Download touched the clipboard")

        val copied = runBlocking { activity.exportListForTesting(ExportTo.CLIPBOARD) }
        assertEquals(1, downloads.calls.size, "Copy reached the downloader")
        assertEquals("Copied 2 cards as a decklist", copied.toast)
        assertNotNull(clipboardText(activity), "Copy never reached the clipboard")
    }

    @Test
    fun libraryExportFallsBackHonestlyWhenTheOsCannotDownload() {
        val downloads = FakeDownloads(DownloadResult.UNSUPPORTED_OS)
        val activity = launch(downloads)

        val result = runBlocking { activity.exportListForTesting(ExportTo.FILE) }
        assertEquals(
            "Copied 2 cards as a decklist — downloads need Android 10 or newer",
            result.toast,
        )
        assertNotNull(clipboardText(activity))
    }

    @Test
    fun sharingACardPutsItsLinkOnTheClipboard() {
        // Through the real activity, because `CardShareParityTest`
        // drives `AppShell` directly — and `AppShell`'s `onShareCard`
        // has a no-op default, so a button wired to nothing at all
        // passes there and does nothing on the phone. That gap is the
        // whole reason this one exists.
        val activity = launch(FakeDownloads(DownloadResult.SAVED))
        activity.setStateForTesting(
            activity.stateForTesting().openCard(org.mattshoe.mtg.core.CardRef("sol ring")),
        )
        settle()

        val after = activity.shareCard(org.mattshoe.mtg.core.Share.link(activity.stateForTesting()))
        settle()

        assertEquals("Link copied", after.toast)
        val clip = clipboardText(activity).orEmpty()
        assertTrue(
            clip.startsWith("https://mtg.mattshoe.org/#/card/"),
            "the clipboard does not hold a link to the card: $clip",
        )
    }
}
