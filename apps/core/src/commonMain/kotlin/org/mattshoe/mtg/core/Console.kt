package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The query console, and the server-side log viewer.
 *
 * Both are the same shape — run something, get a table back — so they
 * share a result type. The console is read-only by the server's own
 * rule, not by anything checked here: the Worker runs EXPLAIN and
 * refuses any statement whose VDBE program opens a write cursor, which
 * is a real check rather than a keyword blocklist a `replace()` can
 * fool.
 */
data class Table(
    val cols: List<String> = emptyList(),
    val rows: List<List<String?>> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        fun of(cols: List<String>, rows: List<JsonArray>) = Table(
            cols,
            rows.map { row ->
                cols.indices.map { i ->
                    row.getOrNull(i)?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.content }
                }
            },
        )
    }
}

data class ConsoleState(
    val sql: String = "",
    val result: Table? = null,
    val took: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canRun: Boolean get() = sql.isNotBlank() && !busy

    fun type(text: String) = copy(sql = text, error = null)
    fun running() = copy(busy = true, error = null, result = null)
    fun ran(t: Table, n: Int) = copy(result = t, took = n, busy = false, error = null)
    fun failed(message: String) = copy(busy = false, error = message, result = null)
}

/** One line of the server's request log. */
data class LogLine(
    val ts: String,
    val level: String,
    val event: String?,
    val method: String?,
    val path: String?,
    val status: Int?,
    val ms: Int?,
    val message: String?,
) {
    val failed: Boolean get() = (status ?: 0) >= 400 || level == "error"
    val slow: Boolean get() = (ms ?: 0) > 1000
}

data class LogsState(
    val lines: List<LogLine> = emptyList(),
    val minLevel: String = "info",
    val onlyErrors: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val shown: List<LogLine> get() = if (onlyErrors) lines.filter { it.failed } else lines

    val errorCount: Int get() = lines.count { it.failed }

    fun loading() = copy(busy = true, error = null)
    fun loaded(l: List<LogLine>) = copy(lines = l, busy = false, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun toggleErrors() = copy(onlyErrors = !onlyErrors)
}

object LogQueries {
    fun decode(rows: List<Map<String, String?>>): List<LogLine> = rows.map {
        LogLine(
            ts = it["ts"].orEmpty(),
            level = it["level"] ?: "info",
            event = it["event"],
            method = it["method"],
            path = it["path"],
            status = it["status"]?.toIntOrNull(),
            ms = it["ms"]?.toIntOrNull(),
            message = it["message"],
        )
    }
}
