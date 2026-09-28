package org.mattshoe.mtg.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Reading a file another app has shared with us.
 *
 * Android-only by nature — it is `ContentResolver` and an intent — but
 * what counts as usable text is `DeckList.looksTextual` from the shared
 * core, so the web and Android agree about that much.
 *
 * This is the capability the installed web app never had. Chrome, reading
 * a `content://` URI on a web app's behalf, handed over an empty body
 * every time. A native activity holds the grant itself.
 */
object SharedFile {

    private const val MAX_BYTES = 8 * 1024 * 1024

    data class Source(val name: String, val type: String, val bytes: Int)

    data class Result(val list: String, val sources: List<Source>, val problem: String?)

    fun read(context: Context, intent: Intent?): Result {
        if (intent == null) return Result("", emptyList(), "there was no share to read.")

        @Suppress("DEPRECATION")
        val uris = buildList {
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { add(it) }
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            if (intent.action == Intent.ACTION_VIEW) intent.data?.let { add(it) }
        }.distinct()

        val parts = mutableListOf<String>()
        val sources = mutableListOf<Source>()
        val trouble = mutableListOf<String>()

        for (uri in uris) {
            val name = displayName(context, uri)
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().take(MAX_BYTES).toByteArray()
                }
                if (bytes == null) {
                    trouble += "$name could not be opened"
                    continue
                }

                val text = String(bytes, Charsets.UTF_8)
                sources += Source(name, context.contentResolver.getType(uri) ?: "unknown", bytes.size)

                // Deliberately indifferent to the declared MIME type.
                // Guessing from the label is what threw away real
                // decklists; whether the bytes are text is the only test.
                if (!DeckList.looksTextual(text)) {
                    trouble += "$name is not text"
                    continue
                }
                parts += text
            } catch (e: SecurityException) {
                trouble += "no permission to read $name (${e.message})"
            } catch (e: Exception) {
                trouble += "could not read $name (${e.message})"
            }
        }

        val typed = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty() && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(typed)) {
            parts += typed
        }

        val list = parts.joinToString("\n").trim()
        val problem = when {
            list.isNotEmpty() -> null
            trouble.isNotEmpty() -> trouble.joinToString("; ")
            uris.isEmpty() -> "the share arrived with no file and no text in it."
            else -> "nothing in the share read as text."
        }
        return Result(list, sources, problem)
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getString(0) }
        } catch (_: Exception) {
            // A provider that will not answer questions can still be read.
        }
        return uri.lastPathSegment ?: uri.toString()
    }
}
