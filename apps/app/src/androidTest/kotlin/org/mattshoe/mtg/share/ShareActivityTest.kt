package org.mattshoe.mtg.share

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * The activity, launched by a real share intent carrying a real
 * `content://` URI — the whole path, not just the parsing.
 */
class ShareActivityTest : InstrumentationTestCase() {

    private val written = mutableListOf<Uri>()
    private var launched: Activity? = null

    override fun tearDown() {
        launched?.let { a -> a.runOnUiThread { a.finish() } }
        launched = null
        val resolver = instrumentation.targetContext.contentResolver
        written.forEach { runCatching { resolver.delete(it, null, null) } }
        written.clear()
        // The token is this app's own state; never leave a test's behind.
        instrumentation.targetContext
            .getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        super.tearDown()
    }

    private fun unique(name: String): String {
        val dot = name.lastIndexOf('.')
        return name.substring(0, dot) + "-" + System.nanoTime() + name.substring(dot)
    }

    private fun publish(name: String, mime: String, body: String): Uri {
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique(name))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(body.toByteArray()) }
        written += uri
        return uri
    }

    private fun texts(activity: Activity): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        activity.findViewById<View>(android.R.id.content)?.let { walk(it) }
        return out
    }

    private fun buttons(activity: Activity): List<Button> {
        val out = mutableListOf<Button>()
        fun walk(v: View) {
            if (v is Button) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        activity.findViewById<View>(android.R.id.content)?.let { walk(it) }
        return out
    }

    private fun launch(intent: Intent): Activity {
        intent.setClassName(instrumentation.targetContext, ShareActivity::class.java.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        launched = activity
        return activity
    }

    private fun pretendUnlocked() {
        instrumentation.targetContext
            .getSharedPreferences("mtg", android.content.Context.MODE_PRIVATE)
            .edit().putString("token", "test-token").commit()
    }

    /**
     * The manifest actually claims the share. If this fails the app never
     * appears in ManaBox's share sheet, whatever else works.
     */
    fun testTheManifestClaimsAFileShare() {
        val pm = instrumentation.targetContext.packageManager
        for (mime in listOf("text/csv", "text/plain", "application/octet-stream",
            "application/vnd.ms-excel", "text/comma-separated-values")) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/downloads/1"))
            }
            val hits = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(
                "nothing handles a $mime share",
                hits.any { it.activityInfo.packageName == instrumentation.targetContext.packageName },
            )
        }
    }

    fun testAsksForThePasswordBeforeAnythingElse() {
        val uri = publish("x.csv", "text/csv", "Name,Quantity\nOpt,1\n")
        val activity = launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        assertTrue(texts(activity).toString(), buttons(activity).any { it.text == "Unlock" })
    }

    /** The one that matters: a shared CSV, on screen, ready to go. */
    fun testASharedCsvArrivesOnScreen() {
        pretendUnlocked()
        val uri = publish(
            "manabox.csv", "text/csv",
            "Name,Quantity\nLightning Bolt,4\nSol Ring,1\nArcane Signet,2\n",
        )
        val activity = launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

        val shown = texts(activity).joinToString(" | ")
        assertTrue(shown, shown.contains("3 cards"))
        assertTrue(shown, shown.contains("CSV"))
        assertTrue(shown, shown.contains("manabox-"))
        assertTrue(shown, shown.contains("Lightning Bolt"))
        assertTrue(shown, shown.contains("Whose collection?"))
    }

    /**
     * The owner is never preselected, and there is no way to reach a write
     * without choosing one. A list in the wrong collection is the mistake
     * this shape exists to prevent.
     */
    fun testNeitherOwnerIsPreselectedAndPreviewIsNotOfferedUntilOneIs() {
        pretendUnlocked()
        val uri = publish("y.csv", "text/csv", "Name,Quantity\nOpt,1\n")
        val activity = launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

        val labels = buttons(activity).map { it.text.toString() }
        assertTrue(labels.toString(), labels.contains("Matt"))
        assertTrue(labels.toString(), labels.contains("Kayla"))
        assertFalse(
            "a preview was offered before an owner was chosen: $labels",
            labels.any { it.startsWith("Preview") },
        )
        assertTrue(texts(activity).toString(), texts(activity).any { it.contains("Pick one") })

        // Choosing one offers the preview, and only the preview — never a write.
        val matt = buttons(activity).first { it.text == "Matt" }
        activity.runOnUiThread { matt.performClick() }
        instrumentation.waitForIdleSync()

        val after = buttons(activity).map { it.text.toString() }
        assertTrue(after.toString(), after.any { it == "Preview · matt" })
        assertFalse("a write was reachable without a preview: $after", after.any { it.startsWith("Add ") })
    }

    fun testABinaryShareSaysWhatWasWrongInsteadOfGoingQuiet() {
        pretendUnlocked()
        val junk = StringBuilder().apply { repeat(2000) { append('\u0000') } }.toString()
        val uri = publish("photo.jpg", "image/jpeg", junk)
        val activity = launch(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

        val shown = texts(activity).joinToString(" | ")
        assertTrue(shown, shown.contains("came through empty"))
        assertTrue(shown, shown.contains("not text"))
    }
}
