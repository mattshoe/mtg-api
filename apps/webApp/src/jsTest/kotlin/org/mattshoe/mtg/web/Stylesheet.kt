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

    /**
     * Take it away again.
     *
     * A suite that measures scroll positions against an unstyled page
     * gets a different page height once this is loaded, so a class
     * that pulls the sheet in for one test has to put it back.
     */
    fun unload() {
        document.querySelector("link[data-mtg]")?.remove()
    }

    /**
     * The rules at the top level of it, as text.
     *
     * `cssRules` nests, so a rule inside a media query is not in
     * this list. That is the point: it answers "what applies with no
     * conditions on it", which is how "nothing hovers on a touch
     * screen" gets checked at all rather than one button at a time.
     */
    fun topLevelRules(): List<String> {
        val sheets = document.styleSheets
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) ?: continue
            val href = sheet.href ?: continue
            if (!href.endsWith("app.css")) continue
            val rules = try { sheet.asDynamic().cssRules } catch (e: Throwable) { null } ?: continue
            return (0 until (rules.length as Int)).map { n -> rules[n].cssText as String }
        }
        return emptyList()
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
