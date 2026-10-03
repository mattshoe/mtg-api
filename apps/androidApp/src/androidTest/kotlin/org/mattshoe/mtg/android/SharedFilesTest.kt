package org.mattshoe.mtg.android

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real `content://` URI, owned by another app, read the way a share
 * delivers it.
 *
 * This is the step the installed web app could never complete: every
 * share reached Chrome and arrived as an empty body, because Chrome
 * cannot read another app's file handle on the web app's behalf.
 *
 * These tests used to live in `:app`, against its own copy of the
 * reading. `:app` shared this module's applicationId, so it could
 * never be installed and nothing it proved was about the app anybody
 * runs. They test `SharedFiles` now, which is what the activity
 * actually calls.
 */
class SharedFilesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val written = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        written.forEach { runCatching { resolver.delete(it, null, null) } }
        written.clear()
    }

    /** MediaStore renames a colliding name, so never collide. */
    private fun unique(name: String): String {
        val dot = name.lastIndexOf('.')
        return name.substring(0, dot) + "-" + System.nanoTime() + name.substring(dot)
    }

    /** Put a real file into MediaStore and get back a real content:// URI. */
    private fun publish(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique(name))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore would not take the file")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        written += uri
        assertEquals("expected a content:// uri, got $uri", "content", uri.scheme)
        return uri
    }

    private fun readOf(vararg uris: Uri) = SharedFiles.read(resolver, uris.toList())

    @Test
    fun readsACsvSharedAsAContentUri() {
        val csv = "Name,Quantity\nLightning Bolt,4\nSol Ring,1\n"
        val read = readOf(publish("manabox-export.csv", "text/csv", csv.toByteArray()))
        assertEquals(1, read.chunks.size)
        assertEquals(csv, read.chunks.first())
        assertTrue(read.complaints.isEmpty())
    }

    @Test
    fun readsAPlainDecklist() {
        val list = "4 Lightning Bolt\n1 Sol Ring\n"
        val read = readOf(publish("deck.txt", "text/plain", list.toByteArray()))
        assertEquals(list, read.chunks.first())
    }

    @Test
    fun readsSeveralFilesAtOnce() {
        val read = readOf(
            publish("one.txt", "text/plain", "1 Sol Ring\n".toByteArray()),
            publish("two.txt", "text/plain", "1 Arcane Signet\n".toByteArray()),
        )
        assertEquals(2, read.chunks.size)
        assertEquals(2, read.names.size)
        assertTrue(read.chunks.joinToString("\n").contains("Arcane Signet"))
    }

    @Test
    fun readsAFileWhateverItsDeclaredTypeIs() {
        // ManaBox has labelled its export everything from text/csv to
        // application/octet-stream. The bytes decide, not the label.
        val read = readOf(
            publish("export.csv", "application/octet-stream", "Name,Quantity\nSol Ring,1\n".toByteArray()),
        )
        assertEquals(1, read.chunks.size)
        assertTrue(read.complaints.isEmpty())
    }

    @Test
    fun aLargeExportSurvivesIntact() {
        val big = buildString {
            append("Name,Quantity\n")
            repeat(4000) { append("Sol Ring,1\n") }
        }
        val read = readOf(publish("big.csv", "text/csv", big.toByteArray()))
        assertEquals(big.length, read.chunks.first().length)
    }

    @Test
    fun binaryIsRefusedAndSaysSo() {
        val jpeg = ByteArray(2048) { (it % 251).toByte() }
        val read = readOf(publish("photo.jpg", "image/jpeg", jpeg))
        assertTrue("a picture was taken for a card list", read.isEmpty)
        assertTrue(read.complaints.isNotEmpty())
    }

    @Test
    fun anEmptyFileIsNotAList() {
        val read = readOf(publish("empty.txt", "text/plain", ByteArray(0)))
        assertTrue(read.isEmpty)
        assertTrue(read.complaints.isNotEmpty())
    }

    @Test
    fun aFileThatCannotBeOpenedIsSaidOutLoudRatherThanDroppedQuietly() {
        val gone = Uri.parse("content://media/external/downloads/999999999")
        val read = SharedFiles.read(resolver, listOf(gone))
        assertTrue(read.isEmpty)
        assertEquals(1, read.complaints.size)
    }

    @Test
    fun oneBadFileDoesNotTakeTheGoodOneWithIt() {
        val read = readOf(
            publish("good.txt", "text/plain", "1 Sol Ring\n".toByteArray()),
            publish("photo.jpg", "image/jpeg", ByteArray(2048) { (it % 251).toByte() }),
        )
        assertEquals(1, read.chunks.size)
        assertEquals(1, read.complaints.size)
    }

    @Test
    fun everyFileThatIsReadIsAlsoNamed() {
        val read = readOf(publish("manabox.csv", "text/csv", "Name,Quantity\nSol Ring,1\n".toByteArray()))
        assertEquals(read.chunks.size, read.names.size)
        assertTrue("a file came back unnamed", read.names.first().isNotBlank())
    }

    @Test
    fun nothingSharedIsNothingRead() {
        val read = SharedFiles.read(resolver, emptyList())
        assertTrue(read.isEmpty)
        assertTrue(read.complaints.isEmpty())
    }
}
