package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.MtgApi

/**
 * Entry point for the Compose HTML build of the wizard.
 *
 * It mounts into an element the existing page already has, so this can go
 * live behind a flag beside the hand-written version rather than
 * replacing it — one route renders the old page, one renders this, and
 * the two can be compared side by side on the same stylesheet.
 */
fun main() {
    val token = runCatching {
        window.localStorage.getItem("mtg.admin")?.let { raw ->
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        }
    }.getOrNull().orEmpty()

    // A list shared in arrives in the fragment, exactly as the service
    // worker already delivers it to the hand-written page.
    val shared = Regex("[?&]share=([^&]*)").find(window.location.hash)
        ?.groupValues?.get(1)
        ?.let { runCatching { decodeURIComponent(it) }.getOrNull() }
        ?.let { runCatching { JSON.parse<dynamic>(it).list as? String }.getOrNull() }
        .orEmpty()

    val mount = document.getElementById("view") ?: document.body!!
    renderComposable(root = mount) {
        MassEntryPage(
            api = MtgApi(),
            token = token,
            scope = CoroutineScope(Dispatchers.Main),
            initial = if (shared.isNotEmpty()) MassEntry.fromShare(shared) else MassEntry(),
        )
    }
}

private external fun decodeURIComponent(s: String): String
