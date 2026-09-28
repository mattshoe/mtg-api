package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLLinkElement

/**
 * The site's real stylesheet, in the test browser.
 *
 * Karma serves `frontend/css/app.css` out of the test package's
 * resources, so anything measuring layout is measuring the cascade
 * that ships. Shared because a suite that forgot to load it measured
 * an unstyled DOM and reported 0px gaps that were really 7px — a
 * false failure that looks exactly like a true one.
 */
object Stylesheet {

    fun load() {
        if (document.querySelector("link[data-mtg]") != null) return
        val link = document.createElement("link") as HTMLLinkElement
        link.rel = "stylesheet"
        link.href = "/base/kotlin/app.css"
        link.setAttribute("data-mtg", "")
        document.head!!.appendChild(link)
    }

    /** True once it has actually applied, so a probe can say so rather than pass blindly. */
    fun applied(): Boolean {
        val probe = document.createElement("div") as HTMLElement
        probe.className = "btn"
        document.body!!.appendChild(probe)
        val padded = window.getComputedStyle(probe).paddingLeft
        probe.remove()
        return padded != "0px" && padded != ""
    }
}
