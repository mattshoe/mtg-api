package org.mattshoe.mtg.share

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * The whole thing, against the real API.
 *
 * Every call here is a dry run, so it resolves against Scryfall and reports
 * what it would do and writes nothing. It only runs when a token has been
 * placed on the device by the harness — no credential lives in this source,
 * and without one these pass trivially rather than failing the build on a
 * machine that has no access.
 */
class LiveApiTest : InstrumentationTestCase() {

    private var token: String? = null
    private val written = mutableListOf<Uri>()
    private var launched: Activity? = null

    override fun setUp() {
        super.setUp()
        token = ArgRunner.arg("liveToken")
    }

    override fun tearDown() {
        launched?.let { a -> a.runOnUiThread { a.finish() } }
        launched = null
        val resolver = instrumentation.targetContext.contentResolver
        written.forEach { runCatching { resolver.delete(it, null, null) } }
        written.clear()
        instrumentation.targetContext
            .getSharedPreferences("mtg", Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    /** Production really does resolve a list and describe the change. */
    fun testAPreviewAgainstTheRealApi() {
        val t = token ?: return
        val out = Api.addCards(t, "matt", "1 Sol Ring\n4 Lightning Bolt", dryRun = true)

        assertTrue("a dry run must not report itself as applied", !out.applied || out.dryRun)
        assertEquals("both lines should resolve", 2, out.resolved)
        assertEquals(0, out.failed)
        assertTrue("expected changes to describe", out.changes.isNotEmpty())
        out.changes.forEach {
            assertTrue(it.name, it.name.isNotEmpty())
            assertTrue("${it.name} ${it.before}->${it.after}", it.after > it.before)
        }
    }

    /** A typo comes back as an error rather than quietly inventing a card. */
    fun testARealApiRefusesANameThatIsNotACard() {
        val t = token ?: return
        val out = Api.addCards(t, "matt", "1 Biterblosom Definitely Not A Card", dryRun = true)
        assertEquals(0, out.resolved)
        assertTrue("expected the server to complain", out.errors.isNotEmpty())
    }

    fun testARealApiRefusesAStaleToken() {
        if (token == null) return
        try {
            Api.addCards("0.notavalidsignature", "matt", "1 Sol Ring", dryRun = true)
            fail("a forged token should be refused")
        } catch (e: Api.Failed) {
            assertNotNull(e.message)
        }
    }

    /**
     * Share to preview, through the real screens and the real server. This
     * is the flow ManaBox will drive, with nothing stubbed but the tap.
     */
    fun testASharedCsvGetsAllTheWayToAPreview() {
        val t = token ?: return
        instrumentation.targetContext
            .getSharedPreferences("mtg", Context.MODE_PRIVATE)
            .edit().putString("token", t).commit()

        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "live-${System.nanoTime()}.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        written += uri
        resolver.openOutputStream(uri)!!.use {
            it.write("Name,Quantity\nSol Ring,1\nLightning Bolt,2\n".toByteArray())
        }

        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_SEND).apply {
                setClassName(instrumentation.targetContext, ShareActivity::class.java.name)
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        launched = activity
        instrumentation.waitForIdleSync()

        assertTrue(texts(activity).toString(), texts(activity).any { it.contains("2 cards") })

        tap(activity, "Matt")
        tap(activity, "Preview · matt")

        // The preview is a real round trip through Scryfall; give it room.
        val shown = await(activity, 120_000) { t2 -> t2.any { it.contains("nothing written yet") } }
        assertTrue("never reached a preview: $shown", shown.any { it.contains("nothing written yet") })
        assertTrue(shown.toString(), shown.any { it.contains("Sol Ring") })

        // And the write is offered only now, never before.
        assertTrue(
            buttons(activity).map { it.text }.toString(),
            buttons(activity).any { it.text.startsWith("Add ") },
        )
    }

    // ------------------------------------------------------------ helpers

    private fun tap(activity: Activity, label: String) {
        val button = buttons(activity).firstOrNull { it.text == label }
            ?: fail("no button labelled $label, saw ${buttons(activity).map { it.text }}")
        activity.runOnUiThread { (button as Button).performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun await(activity: Activity, ms: Long, until: (List<String>) -> Boolean): List<String> {
        val deadline = System.currentTimeMillis() + ms
        var seen = texts(activity)
        while (System.currentTimeMillis() < deadline) {
            seen = texts(activity)
            if (until(seen)) return seen
            Thread.sleep(250)
            instrumentation.waitForIdleSync()
        }
        return seen
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
}
