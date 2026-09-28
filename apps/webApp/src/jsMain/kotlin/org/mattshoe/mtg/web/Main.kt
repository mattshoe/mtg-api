package org.mattshoe.mtg.web

import androidx.compose.runtime.Composition
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.MtgApi
import org.w3c.dom.HTMLElement

/**
 * The bridge the hand-written app mounts this through.
 *
 * Deliberately not a page of its own. The site is eighteen screens and
 * this is one of them, so it loads on the entry route and nowhere else —
 * the other seventeen never pay for the Compose runtime, and the router,
 * the nav and the rest of the app carry on exactly as they were.
 *
 * `window.mtgEntry.mount(el, list, token)` renders into an element the
 * caller owns; `unmount()` disposes it on the way out. Leaving a
 * composition attached to a node the router is about to replace is how
 * you get two of them.
 */
@JsExport
object MtgEntry {

    private var composition: Composition? = null

    fun mount(root: HTMLElement, sharedList: String?, token: String) {
        unmount()
        composition = renderComposable(root = root) {
            MassEntryPage(
                api = MtgApi(),
                token = token,
                scope = CoroutineScope(Dispatchers.Main),
                initial = if (sharedList.isNullOrBlank()) MassEntry() else MassEntry.fromShare(sharedList),
            )
        }
    }

    fun unmount() {
        composition?.dispose()
        composition = null
    }
}

fun main() {
    // Hang it off window so plain JS can reach it without a module loader.
    window.asDynamic().mtgEntry = MtgEntry
}
