package org.mattshoe.mtg.share

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.test.AndroidTestCase

/**
 * The tests that matter: a real `content://` URI, owned by another app
 * (MediaProvider), read the way a share delivers it.
 *
 * This is the step the installed web app could never complete. Every share
 * reached Chrome and every one arrived as an empty body, because Chrome
 * could not read another app's file handle on the web app's behalf. A
 * native activity holds the read grant itself, and these prove it.
 */
class SharedListTest : AndroidTestCase() {

    private val written = mutableListOf<Uri>()

    override fun tearDown() {
        written.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        written.clear()
        super.tearDown()
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
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
        ) ?: throw AssertionError("MediaStore would not take the file")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        written += uri
        assertTrue("expected a content:// uri, got $uri", uri.scheme == "content")
        return uri
    }

    private fun shareOf(uri: Uri, mime: String) = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun testReadsACsvSharedAsAContentUri() {
        val csv = "Name,Quantity\nLightning Bolt,4\nSol Ring,1\n"
        val uri = publish("manabox-export.csv", "text/csv", csv.toByteArray())

        val result = SharedList.from(context, shareOf(uri, "text/csv"))

        assertFalse("nothing came through: ${result.problem}", result.isEmpty)
        assertEquals(csv.trim(), result.list)
        assertEquals(1, result.sources.size)
        assertTrue(result.sources[0].name, result.sources[0].name.startsWith("manabox-export-"))
        assertEquals(2, SharedList.countCards(result.list))
        assertTrue(SharedList.looksLikeCsv(result.list))
    }

    /**
     * The case that broke the web app: a file labelled with a MIME type
     * that has nothing to do with what it is. Nothing here reads the label.
     */
    fun testReadsAFileWhateverItsDeclaredTypeIs() {
        val csv = "Name,Quantity\nBrainstorm,4\n"
        val uri = publish("export-mislabelled.csv", "application/octet-stream", csv.toByteArray())

        val result = SharedList.from(context, shareOf(uri, "application/octet-stream"))

        assertFalse("nothing came through: ${result.problem}", result.isEmpty)
        assertEquals(csv.trim(), result.list)
    }

    fun testReadsAPlainDecklist() {
        val text = "4 Lightning Bolt\n1 Sol Ring (M3C) 409 *F*\nArcane Signet\n"
        val uri = publish("deck.txt", "text/plain", text.toByteArray())

        val result = SharedList.from(context, shareOf(uri, "text/plain"))

        assertEquals(text.trim(), result.list)
        assertEquals(3, SharedList.countCards(result.list))
        assertFalse(SharedList.looksLikeCsv(result.list))
    }

    fun testReadsSeveralFilesAtOnce() {
        val a = publish("a.txt", "text/plain", "4 Lightning Bolt\n".toByteArray())
        val b = publish("b.txt", "text/plain", "2 Counterspell\n".toByteArray())

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = SharedList.from(context, intent)

        assertTrue(result.list, result.list.contains("Lightning Bolt"))
        assertTrue(result.list, result.list.contains("Counterspell"))
        assertEquals(2, result.sources.size)
    }

    fun testOpenWithIsReadTheSameWay() {
        val uri = publish("opened.csv", "text/csv", "Name,Quantity\nOpt,2\n".toByteArray())
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = SharedList.from(context, intent)

        assertTrue(result.list, result.list.contains("Opt,2"))
    }

    fun testSharedTextIsTakenToo() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "3 Ponder\n1 Brainstorm")
        }
        val result = SharedList.from(context, intent)
        assertEquals("3 Ponder\n1 Brainstorm", result.list)
    }

    fun testABareLinkIsNotACardList() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://moxfield.com/decks/abc")
        }
        val result = SharedList.from(context, intent)
        assertTrue(result.isEmpty)
        assertNotNull(result.problem)
    }

    fun testBinaryIsRefusedAndSaysSo() {
        val junk = ByteArray(3000) { (it * 53 % 256 - 128).toByte() }
        val uri = publish("photo.jpg", "image/jpeg", junk)

        val result = SharedList.from(context, shareOf(uri, "image/jpeg"))

        assertTrue(result.isEmpty)
        assertNotNull(result.problem)
        assertTrue(result.problem!!, result.problem!!.contains("not text"))
    }

    fun testAnEmptyShareIsReportedRatherThanIgnored() {
        val result = SharedList.from(context, Intent(Intent.ACTION_SEND).apply { type = "text/csv" })
        assertTrue(result.isEmpty)
        assertNotNull(result.problem)
    }

    fun testALargeExportSurvivesIntact() {
        val rows = (1..4000).joinToString("\n") { "Card Number $it,1" }
        val csv = "Name,Quantity\n$rows\n"
        val uri = publish("big.csv", "text/csv", csv.toByteArray())

        val result = SharedList.from(context, shareOf(uri, "text/csv"))

        assertEquals(csv.trim(), result.list)
        assertEquals(4000, SharedList.countCards(result.list))
    }
}
