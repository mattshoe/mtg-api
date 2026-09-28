package org.mattshoe.mtg.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Pulling a card list out of whatever Android hands over.
 *
 * This is the part the web app could never do. A share arrives as a
 * `content://` URI belonging to another app, and reading it needs the read
 * grant that comes with the intent. A native activity holds that grant
 * directly. Chrome, handling the same share on behalf of an installed web
 * app, does not — which is why every one of those shares arrived as an
 * empty body.
 */
object SharedList {

    /** Every file in a share is capped; a collection export, not a database. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    data class Source(val name: String, val type: String, val bytes: Int, val chars: Int)

    data class Result(
        val list: String,
        val sources: List<Source>,
        /** Populated when nothing usable came through, in plain words. */
        val problem: String?,
    ) {
        val isEmpty get() = list.isBlank()
    }

    /**
     * Read whatever the intent carries.
     *
     * Deliberately indifferent to the declared MIME type. ManaBox labels
     * its export whatever it likes and so does every other app, and
     * guessing from the label is how the web version threw away real
     * decklists. Read the bytes and see whether they are text.
     */
    fun from(context: Context, intent: Intent?): Result {
        if (intent == null) return Result("", emptyList(), "there was no share to read.")

        val uris = buildList {
            intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)?.let { add(it) }
            intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)?.let { addAll(it) }
            // Open with, rather than share.
            if (intent.action == Intent.ACTION_VIEW) intent.data?.let { add(it) }
        }.distinct()

        val parts = mutableListOf<String>()
        val sources = mutableListOf<Source>()
        val trouble = mutableListOf<String>()

        for (uri in uris) {
            try {
                val meta = describe(context.contentResolver, uri)
                val bytes = context.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) {
                        trouble += "$meta could not be opened"
                        return@use null
                    }
                    stream.readAtMost(MAX_BYTES)
                } ?: continue

                val text = String(bytes, Charsets.UTF_8)
                sources += Source(meta, mimeOf(context.contentResolver, uri), bytes.size, text.length)

                if (!looksTextual(text)) {
                    trouble += "$meta is not text"
                    continue
                }
                parts += text
            } catch (e: SecurityException) {
                trouble += "no permission to read the shared file (${e.message})"
            } catch (e: Exception) {
                trouble += "could not read the shared file (${e.message})"
            }
        }

        // A plain text share, or a fallback when the stream gave nothing.
        val extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (!extra.isNullOrEmpty() && !extra.isHttpLink()) parts += extra

        val list = parts.joinToString("\n").trim()
        val problem = when {
            list.isNotEmpty() -> null
            trouble.isNotEmpty() -> trouble.joinToString("; ")
            uris.isEmpty() -> "the share arrived with no file and no text in it."
            else -> "nothing in the share read as text."
        }
        return Result(list, sources, problem)
    }

    /** How many cards a list represents, however it is shaped. */
    fun countCards(text: String): Int {
        if (text.isBlank()) return 0
        if (looksLikeCsv(text)) return text.lines().count { it.isNotBlank() } - 1
        return text.lines().count {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("//")
        }
    }

    fun looksLikeCsv(text: String): Boolean {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        if (!first.contains(',')) return false
        val cols = first.lowercase().replace("\"", "").split(",").map { it.filter(Char::isLetter) }
        return cols.contains("name") || cols.contains("cardname")
    }

    /**
     * Bytes that are not UTF-8 decode to replacement characters, so a few
     * is a file with an odd character in it and a great many is a JPEG. A
     * NUL settles it alone — no text file has one.
     */
    fun looksTextual(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains('\u0000')) return false
        val head = s.take(4096)
        return head.count { it == '\uFFFD' }.toDouble() / head.length < 0.02
    }

    private fun String.isHttpLink() = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(this)

    private fun describe(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
            // A provider that will not answer questions can still be read.
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun mimeOf(resolver: ContentResolver, uri: Uri) = resolver.getType(uri) ?: "unknown"

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java)
        else getParcelableExtra(key) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableArrayListExtraCompat(key: String): List<Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java)
        else getParcelableArrayListExtra<Uri>(key)
}
