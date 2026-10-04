package org.mattshoe.mtg.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * What came of asking for a file to be downloaded.
 *
 * Three answers rather than a `Boolean`, because "no" is not one
 * thing: a phone too old for scoped storage and a write that failed
 * for some other reason deserve different toasts, and a `Boolean`
 * cannot carry that.
 */
enum class DownloadResult {
    /** The file now exists somewhere the person can find it afterward. */
    SAVED,

    /** This OS version cannot take this route — see [MediaStoreDownloads]. */
    UNSUPPORTED_OS,

    /** New enough, and it still did not work. */
    FAILED,
}

/**
 * Where "Download" actually puts a file.
 *
 * The seam a test reaches through, the same way `MainActivity`'s
 * `useForTesting` swaps the network for one. `MediaStore.Downloads` is
 * not something Robolectric meaningfully shadows, so nothing on the
 * JVM can check that a byte landed in a real Downloads folder — a
 * test swaps this for a fake and checks what `MainActivity` did with
 * the result instead.
 */
interface Downloads {
    fun save(name: String, text: String): DownloadResult
}

/**
 * `MediaStore.Downloads`, the scoped-storage way to put a file where
 * the person can find it afterward without any storage permission —
 * available from API 29.
 *
 * `minSdk` is 26. Below 29, reaching the public Downloads folder needs
 * `WRITE_EXTERNAL_STORAGE`, a runtime permission, for the sake of
 * three OS versions on a sideloaded app one person uses. That is a
 * worse trade than admitting the file route is not there yet, so this
 * refuses outright — [DownloadResult.UNSUPPORTED_OS] — rather than
 * requesting a permission or silently doing nothing.
 */
class MediaStoreDownloads(private val context: Context) : Downloads {
    override fun save(name: String, text: String): DownloadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DownloadResult.UNSUPPORTED_OS
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return DownloadResult.FAILED
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return DownloadResult.FAILED
            DownloadResult.SAVED
        } catch (e: Exception) {
            DownloadResult.FAILED
        }
    }
}
