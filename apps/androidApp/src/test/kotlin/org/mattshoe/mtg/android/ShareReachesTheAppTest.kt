package org.mattshoe.mtg.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
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
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.MtgApi
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "sharing a text file from manabox did not work. It opens the
 * app as part of the manabox process instead of its own process ...
 * when i selected the new deck option the Continue button is dead".
 *
 * A share used to land on `NextShareActivity`, a second activity that
 * drew the entry wizard on its own with nothing behind "New deck" and
 * nothing behind "Upload a file". Both buttons did nothing. A share is
 * now the whole app: `MainActivity`, in its own task, with the list
 * in the entry box.
 */
@RunWith(AndroidJUnit4::class)
class ShareReachesTheAppTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fakeApi(): MtgApi {
        val engine = MockEngine {
            respond(
                content = """{"cols":[],"rows":[],"n":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MtgApi.withEngine("https://example.invalid", client)
    }

    private fun settle() = repeat(40) { shadowOf(Looper.getMainLooper()).idle() }

    /** A file another app holds, readable once through our resolver. */
    private fun file(name: String, text: String): Uri {
        val uri = Uri.parse("content://com.manabox.files/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        return uri
    }

    private fun shareOf(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun launch(intent: Intent? = null): ActivityController<MainActivity> {
        val built =
            if (intent == null) Robolectric.buildActivity(MainActivity::class.java)
            else Robolectric.buildActivity(MainActivity::class.java, intent)
        controllers += built
        built.get().useForTesting(fakeApi())
        built.create().start().resume()
        settle()
        return built
    }

    @Test
    fun aShareIsOfferedToTheWholeAppAndNothingElse() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
        val answers = context.packageManager
            .queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
        assertEquals(
            listOf(MainActivity::class.java.name),
            answers,
            "a share is answered by something other than the app itself",
        )
    }

    @Test
    fun aSharedFileOpensTheAppWithTheListInTheEntryBox() {
        val activity = launch(shareOf(file("kayla.txt", "1 Sol Ring\n1 Arcane Signet"))).get()
        val state = activity.stateForTesting()
        assertEquals("1 Sol Ring\n1 Arcane Signet", state.entry.list, "the shared file never reached the entry box")
        assertEquals("1 Sol Ring\n1 Arcane Signet", state.sharedList, "the share is not held for the gated wizard")
    }

    @Test
    fun aShareReachesTheAppThatIsAlreadyOpen() {
        val built = launch()
        assertEquals("", built.get().stateForTesting().entry.list)

        built.newIntent(shareOf(file("second.txt", "4 Lightning Bolt")))
        settle()

        assertEquals(
            "4 Lightning Bolt",
            built.get().stateForTesting().entry.list,
            "a share to an already-open app did nothing",
        )
    }

    @Test
    fun aFilePickedOnTheDeckWizardLandsInTheDecksList() {
        val activity = launch().get()
        activity.setStateForTesting(AppState().startingADeck())

        activity.readFilesForTesting(listOf(file("deck.txt", "1 Sol Ring")))
        settle()

        val state = activity.stateForTesting()
        assertEquals("1 Sol Ring", state.newDeck.list, "the picked file did not reach the deck wizard")
        assertTrue(state.entry.list.isEmpty(), "the picked file went into the entry box behind the wizard")
    }

    // What `:app`'s device tests proved about a share, proved again on
    // the app that ships. `:app` could never be installed beside this
    // one, so its tests were proving a build nobody could run.

    private fun bytes(name: String, content: ByteArray): Uri {
        val uri = Uri.parse("content://com.manabox.files/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(content))
        return uri
    }

    @Test
    fun aSharedFileIsReadWhateverTypeItClaims() {
        val csv = "Name,Quantity\nSol Ring,1"
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, file("manabox.csv", csv))
        }
        val state = launch(share).get().stateForTesting()
        assertEquals(csv, state.entry.list, "a text file labelled octet-stream was thrown away for its label")
    }

    @Test
    fun severalFilesSharedAtOnceAllArrive() {
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(file("one.txt", "1 Sol Ring"), file("two.txt", "1 Arcane Signet")),
            )
        }
        val state = launch(share).get().stateForTesting()
        assertEquals("1 Sol Ring\n1 Arcane Signet", state.entry.list, "only some of the shared files arrived")
    }

    @Test
    fun sharedTextWithNoFileIsTakenToo() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "4 Lightning Bolt")
        }
        val state = launch(share).get().stateForTesting()
        assertEquals("4 Lightning Bolt", state.entry.list, "text shared without a file was dropped")
    }

    @Test
    fun aLargeExportArrivesIntact() {
        val rows = (1..4000).joinToString("\n") { "1 Card Number $it" }
        val state = launch(shareOf(file("big.txt", rows))).get().stateForTesting()
        assertEquals(4000, state.entry.list.lines().size, "a 4,000 row export lost rows on the way in")
        assertEquals(rows, state.entry.list, "a 4,000 row export arrived altered")
    }

    @Test
    fun aBareLinkIsNotTakenForACardList() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://manabox.app/decks/abc123")
        }
        val state = launch(share).get().stateForTesting()
        assertEquals("", state.entry.list, "a shared link went into the entry box as if it were cards")
        assertTrue(state.toastFailed, "a share with nothing usable in it was not reported as a failure")
    }

    @Test
    fun aBinaryShareSaysWhatWasWrong() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0x0D, 0, 0, 0, 0)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, bytes("photo.png", png))
        }
        val state = launch(share).get().stateForTesting()
        assertEquals("", state.entry.list, "binary went into the entry box")
        assertTrue(state.toastFailed, "a binary share was not reported as a failure")
        assertEquals("photo.png is not text", state.toast, "a binary share went quiet about what was wrong")
    }

    @Test
    fun anEmptyShareIsReportedRatherThanIgnored() {
        val state = launch(Intent(Intent.ACTION_SEND).setType("text/plain")).get().stateForTesting()
        assertTrue(state.toastFailed, "an empty share was not reported as a failure")
        assertEquals("the share arrived with no file and no text in it.", state.toast)
    }
}
