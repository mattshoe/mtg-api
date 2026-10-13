package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.HTMLLinkElement
import kotlin.js.Promise

/**
 * A tablet-sized screen, for any page test.
 *
 * Matt: "you need to test every single feature from this moment
 * forward in tablet mode". The test browser's window is whatever size
 * it is, and a test cannot resize it, so this is a frame of exactly a
 * tablet's size with the real stylesheet in it. `position: fixed` and
 * `vh` resolve against the frame, which is what makes it a screen
 * rather than a box: a page mounted in a plain `div` would still be
 * laid out against the test window. Sizes match Android's `Tablet`.
 */
object Tablet {
    /** A folding phone opened out: nearly square. */
    val UNFOLDED = 750 to 830

    /** A ten-inch tablet held sideways: far wider than tall. */
    val LANDSCAPE = 1280 to 800

    /**
     * A screen, styled and empty. Mount into [body]; remove [frame] after.
     * Read anything inside it with `unsafeCast`, never `as`: see [styled].
     */
    class Screen(val frame: HTMLIFrameElement, val body: HTMLElement) {
        val window: org.w3c.dom.Window get() = frame.contentWindow!!
    }

    suspend fun open(size: Pair<Int, Int>): Screen {
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.style.apply {
            width = "${size.first}px"
            height = "${size.second}px"
            border = "0"
            position = "absolute"
            left = "0"
            top = "0"
        }
        document.body!!.appendChild(frame)
        try {
            return styled(frame)
        } catch (e: Throwable) {
            // Left behind, a frame this size sits over the top of the
            // test page and breaks every layout test after it.
            frame.remove()
            throw e
        }
    }

    private suspend fun styled(frame: HTMLIFrameElement): Screen {
        val inner = frame.contentDocument!!
        // `unsafeCast`, never `as`, for anything inside the frame: it
        // belongs to the frame's window, so it is not an instance of
        // this window's `HTMLElement` and `as` throws.
        val link = inner.createElement("link").unsafeCast<HTMLLinkElement>()
        link.rel = "stylesheet"
        link.href = window.location.origin + "/base/kotlin/app.css"
        val loaded = Promise<Unit> { ok, fail ->
            link.onload = { ok(Unit) }
            link.onerror = { _, _, _, _, _ -> fail(Error("app.css did not load in the tablet frame")) }
        }
        inner.head!!.appendChild(link)
        loaded.await()
        val body = inner.body!!.unsafeCast<HTMLElement>()
        body.style.margin = "0"
        return Screen(frame, body)
    }
}
