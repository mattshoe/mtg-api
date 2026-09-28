package org.mattshoe.mtg.sender

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * A provider that hands out a file from this app's own cache.
 *
 * Its own, written by hand rather than pulled from the support library, so
 * the test harness keeps the same zero-dependency build as the app. What
 * matters is that the file belongs to a different app and reaching it
 * needs the grant that rides on the intent — which is exactly the step
 * Chrome could never complete for the installed web app.
 */
class ExportProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri) = File(context!!.cacheDir, uri.lastPathSegment!!)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun getType(uri: Uri): String = "text/csv"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
