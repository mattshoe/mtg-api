package org.mattshoe.mtg.share

import android.test.AndroidTestCase
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * The API client, against a stub served from inside the test.
 *
 * On-device, no network, no credentials. What it checks is the part that
 * would otherwise only be found in production: that the request is shaped
 * the way the Worker expects, that the token is presented, that `dry_run`
 * says what it is told to, and that a refusal surfaces the server's own
 * words rather than a stack trace.
 */
class ApiTest : AndroidTestCase() {

    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private val seen = mutableListOf<Request>()

    data class Request(val method: String, val path: String, val auth: String?, val body: String)

    /** Replies, in the order requests arrive. */
    private var replies: MutableList<Pair<Int, String>> = mutableListOf()

    override fun setUp() {
        super.setUp()
        seen.clear()
        replies = mutableListOf()
        server = ServerSocket(0)
        Api.base = "http://127.0.0.1:${server.localPort}"
        pool.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { return@execute }
                pool.execute { serve(socket) }
            }
        }
    }

    override fun tearDown() {
        Api.base = Api.BASE_DEFAULT
        runCatching { server.close() }
        pool.shutdownNow()
        super.tearDown()
    }

    private fun serve(socket: java.net.Socket): Unit = socket.use {
        val input = it.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        val (method, path) = request.split(" ").let { p -> p[0] to p[1] }

        var length = 0
        var auth: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) length = line.substringAfter(":").trim().toInt()
            if (lower.startsWith("authorization:")) auth = line.substringAfter(":").trim()
        }
        val body = CharArray(length).also { buf ->
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
        }.concatToString()

        synchronized(seen) { seen += Request(method, path, auth, body) }

        val (code, payload) = synchronized(replies) {
            if (replies.isEmpty()) 200 to "{}" else replies.removeAt(0)
        }
        val bytes = payload.toByteArray()
        it.getOutputStream().apply {
            write(
                ("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private fun reply(code: Int, json: String) = synchronized(replies) { replies += code to json }

    // ------------------------------------------------------------------

    fun testUnlockSendsThePasswordAndKeepsTheToken() {
        reply(200, """{"ok":true,"token":"0.abc","expires_at":null}""")

        val token = Api.unlock("hunter2")

        assertEquals("0.abc", token)
        val req = seen.single()
        assertEquals("POST", req.method)
        assertEquals("/admin", req.path)
        assertEquals("hunter2", JSONObject(req.body).getString("password"))
        assertNull("the password call must not carry a token", req.auth)
    }

    fun testAWrongPasswordSurfacesTheServersOwnWords() {
        reply(401, """{"error":"wrong password"}""")
        try {
            Api.unlock("nope")
            fail("a refusal should throw")
        } catch (e: Api.Failed) {
            assertEquals("wrong password", e.message)
        }
    }

    fun testAPreviewSaysDryRunAndCarriesTheToken() {
        reply(200, """{"applied":false,"dry_run":true,"resolved":2,"failed":0,
            "changes":[["Lightning Bolt","2X2","117","nonfoil",0,4]],"errors":[],"notes":[]}""")

        val out = Api.addCards("0.abc", "matt", "4 Lightning Bolt", dryRun = true)

        val req = seen.single()
        assertEquals("/cards/add", req.path)
        assertEquals("Bearer 0.abc", req.auth)
        val body = JSONObject(req.body)
        assertEquals("matt", body.getString("owner"))
        assertEquals("4 Lightning Bolt", body.getString("list"))
        assertTrue("a preview must say dry_run", body.getBoolean("dry_run"))

        assertFalse(out.applied)
        assertTrue(out.dryRun)
        assertEquals(2, out.resolved)
        assertEquals(1, out.changes.size)
        out.changes[0].let {
            assertEquals("Lightning Bolt", it.name)
            assertEquals("2X2", it.set)
            assertEquals("117", it.collectorNumber)
            assertEquals(0, it.before)
            assertEquals(4, it.after)
        }
    }

    fun testAWriteSaysItIsNotADryRun() {
        reply(200, """{"applied":true,"dry_run":false,"resolved":1,"failed":0,
            "changes":[["Sol Ring","M3C","409","foil",1,2]],"errors":[],"notes":[]}""")

        val out = Api.addCards("0.abc", "kayla", "1 Sol Ring", dryRun = false)

        assertFalse(JSONObject(seen.single().body).getBoolean("dry_run"))
        assertEquals("kayla", JSONObject(seen.single().body).getString("owner"))
        assertTrue(out.applied)
        assertEquals(1, out.changes[0].before)
        assertEquals(2, out.changes[0].after)
    }

    fun testErrorsAndNotesComeBackWhole() {
        reply(200, """{"applied":true,"resolved":1,"failed":2,"changes":[],
            "errors":["no card named Biterblosom","line 4 unreadable"],
            "notes":["rulings skipped on a big import"]}""")

        val out = Api.addCards("0.abc", "matt", "x", dryRun = false)

        assertEquals(2, out.failed)
        assertEquals(2, out.errors.size)
        assertEquals("no card named Biterblosom", out.errors[0])
        assertEquals(1, out.notes.size)
    }

    fun testAnExpiredTokenIsReportedNotSwallowed() {
        reply(401, """{"error":"admin mode required"}""")
        try {
            Api.addCards("stale", "matt", "1 Sol Ring", dryRun = true)
            fail("a 401 should throw")
        } catch (e: Api.Failed) {
            assertEquals("admin mode required", e.message)
        }
    }

    fun testAReplyThatIsNotJsonSaysSoRatherThanCrashing() {
        reply(500, "<html>upstream is having a moment</html>")
        try {
            Api.addCards("0.abc", "matt", "1 Sol Ring", dryRun = true)
            fail("garbage should throw")
        } catch (e: Api.Failed) {
            assertTrue(e.message!!, e.message!!.contains("not JSON"))
        }
    }

    fun testAWholeCollectionExportGoesUpIntact() {
        val list = (1..4000).joinToString("\n") { "1 Card Number $it" }
        reply(200, """{"applied":false,"dry_run":true,"resolved":4000,"failed":0,"changes":[],"errors":[]}""")

        Api.addCards("0.abc", "matt", list, dryRun = true)

        assertEquals(list, JSONObject(seen.single().body).getString("list"))
    }

    fun testAListWithQuotesAndCommasSurvivesTheJson() {
        val list = "1 Kardur, Doomscourge\n1 Ambition's Cost\n\"quoted\",2"
        reply(200, """{"applied":false,"dry_run":true,"resolved":3,"failed":0,"changes":[],"errors":[]}""")

        Api.addCards("0.abc", "matt", list, dryRun = true)

        assertEquals(list, JSONObject(seen.single().body).getString("list"))
    }
}
