package org.mattshoe.mtg.android.e2e

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/**
 * The worker, for real, on the phone.
 *
 * An end-to-end test is worth having only if the app underneath it
 * is the whole app: the real `MainActivity`, the real `MtgViewModel`,
 * the real `MtgApi` over a real socket, carrying the real SQL that
 * `:core` builds. The only thing that must not be real is the
 * database at the other end — pointing a test suite at the live D1
 * would be slow, flaky and destructive.
 *
 * Recording responses was the other option and it is the wrong one.
 * The app sends SQL, not request names: a filter changes the
 * statement, and a recorded fixture keyed by that statement goes
 * stale the moment anybody edits a query, while still answering the
 * old one perfectly. What it would be testing is that the SQL has
 * not changed, which is the opposite of the point.
 *
 * So this runs the SQL. Android ships SQLite, the repository's own
 * `schema.sql` and `test/fixtures/seed.sql` load into it, and
 * `/query` executes whatever arrives and answers in the worker's own
 * `{cols, rows, n}` shape. A query that is wrong is wrong here too.
 */
internal class FakeWorker private constructor(
    private val db: SQLiteConnection,
    private val server: MockWebServer,
) : Closeable {

    /** What to hand `MtgApi` as its base. */
    val base: String get() = server.url("/").toString().trimEnd('/')

    /** Every statement the app has sent, in order, for assertions about load. */
    val statements = mutableListOf<String>()

    /** The password `/admin` accepts. Anything else is a 401. */
    var password: String = "open-sesame"

    /** The token it hands back. */
    var token: String = "e2e-token"

    override fun close() {
        runCatching { server.shutdown() }
        runCatching { db.close() }
    }

    /** A read straight against the harness's database, to check a write landed. */
    fun rows(sql: String): List<List<String?>> = db.prepare(sql).use { s ->
        buildList {
            while (s.step()) {
                add((0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) })
            }
        }
    }

    private fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val body = request.body.readUtf8()
        return when (path) {
            "/query" -> query(body)
            "/admin" -> admin(body)
            // Not served, and loudly rather than quietly: a journey
            // that reaches one of these is a journey this harness
            // cannot honestly run yet, and a silent empty answer
            // would look like the app failing.
            else -> fail(501, "the fake worker does not serve $path")
        }
    }

    private fun admin(body: String): MockResponse {
        val said = runCatching { JSONObject(body).optString("password") }.getOrDefault("")
        if (said != password) return fail(401, "wrong password")
        return ok(JSONObject().put("token", token))
    }

    private fun query(body: String): MockResponse {
        val request = runCatching { JSONObject(body) }.getOrNull()
            ?: return fail(400, "not JSON")
        val sql = request.optString("sql")
        if (sql.isBlank()) return fail(400, "no sql")
        synchronized(statements) { statements.add(sql) }
        val params = request.optJSONArray("params") ?: JSONArray()
        return try {
            synchronized(db) {
                db.prepare(sql).use { statement ->
                    for (i in 0 until params.length()) {
                        // One-based, the way SQLite numbers them.
                        val at = i + 1
                        when (val v = if (params.isNull(i)) null else params.get(i)) {
                            null -> statement.bindNull(at)
                            is Int -> statement.bindLong(at, v.toLong())
                            is Long -> statement.bindLong(at, v)
                            is Double -> statement.bindDouble(at, v)
                            is Boolean -> statement.bindLong(at, if (v) 1 else 0)
                            else -> statement.bindText(at, v.toString())
                        }
                    }
                    ok(encode(statement))
                }
            }
        } catch (e: Exception) {
            // The real worker answers a bad statement with 400 and
            // SQLite's own message, and the app shows it. Matching
            // that matters: a 500 here would exercise a path the
            // real server never takes.
            fail(400, e.message ?: "sql error")
        }
    }

    /**
     * `{cols, rows, n}` — columns once, rows as arrays, the way D1
     * answers and the way `QueryResult` in `:core-net` parses.
     *
     * A statement that returns nothing — an INSERT, an UPDATE —
     * steps once to run and reports no columns, which lands here as
     * the empty result the worker also sends. So writes need no
     * separate branch, and more to the point there is no guess about
     * whether a given statement is a read.
     */
    private fun encode(s: SQLiteStatement): JSONObject {
        val rows = JSONArray()
        var names: List<String>? = null
        while (s.step()) {
            if (names == null) names = (0 until s.getColumnCount()).map { s.getColumnName(it) }
            val row = JSONArray()
            for (i in names.indices) {
                when {
                    s.isNull(i) -> row.put(JSONObject.NULL)
                    else -> when (s.getColumnType(i)) {
                        INTEGER -> row.put(s.getLong(i))
                        FLOAT -> row.put(s.getDouble(i))
                        else -> row.put(s.getText(i))
                    }
                }
            }
            rows.put(row)
        }
        if (names == null) {
            names = runCatching {
                (0 until s.getColumnCount()).map { s.getColumnName(it) }
            }.getOrDefault(emptyList())
        }
        val cols = JSONArray().also { a -> names.forEach { a.put(it) } }
        return JSONObject().put("cols", cols).put("rows", rows).put("n", rows.length())
    }

    private fun ok(body: JSONObject) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body.toString())

    private fun fail(code: Int, said: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", said).toString())

    companion object {

        /**
         * A worker with the repository's schema and fixture in it.
         *
         * Both files are copied into the test APK's assets by the
         * `e2eAssets` task in `build.gradle.kts`, so they are the
         * same bytes the worker's own vitest suite runs against
         * rather than a second copy that can drift.
         */
        /** `SQLITE_INTEGER` and `SQLITE_FLOAT`, which the driver reports as ints. */
        private const val INTEGER = 1
        private const val FLOAT = 2

        fun start(): FakeWorker {
            val db = BundledSQLiteDriver().open(":memory:")
            load(db, "schema.sql")
            load(db, "seed.sql")
            val server = MockWebServer()
            val worker = FakeWorker(db, server)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = worker.dispatch(request)
            }
            server.start()
            return worker
        }

        private fun load(db: SQLiteConnection, asset: String) {
            val text = InstrumentationRegistry.getInstrumentation().context.assets
                .open(asset).bufferedReader().use { it.readText() }
            splitStatements(text).forEach { statement ->
                try {
                    db.execSQL(statement)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "$asset: ${e.message}\n  in: ${statement.take(200)}",
                        e,
                    )
                }
            }
        }
    }
}
