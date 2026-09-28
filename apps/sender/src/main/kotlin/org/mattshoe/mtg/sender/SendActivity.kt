package org.mattshoe.mtg.sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Share a file the way a real app does.
 *
 * Started from adb with the text to send, the filename and the MIME type
 * to claim. It writes the file into its own cache, then fires ACTION_SEND
 * with a grant — the faithful version of what ManaBox does, and the thing
 * `am start` on its own cannot produce.
 */
class SendActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val name = intent.getStringExtra("name") ?: "export.csv"
        val mime = intent.getStringExtra("mime") ?: "text/csv"
        // The body arrives base64. An --es argument is re-parsed by the
        // device shell, so spaces, newlines and pipes in it are all lost;
        // base64 has none of those characters and survives intact.
        val body = intent.getStringExtra("b64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
            ?: intent.getStringExtra("body")
            ?: "Name,Quantity\nSol Ring,1\n"
        val target = intent.getStringExtra("target")

        val file = File(cacheDir, name).apply { writeText(body) }
        val uri = Uri.parse("content://org.mattshoe.mtg.sender.files/${file.name}")

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (target != null) setPackage(target)
        }
        startActivity(send)
        finish()
    }
}
