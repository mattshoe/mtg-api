package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every single-line box has an x on its right that empties it.
 *
 * Matt: "i want all single line text entry fields to have an x on the
 * right to clear the text". Through the real shell, in a frame of a
 * phone's size and of a tablet's. The phone's `ClearTheBoxTest` is the
 * same claim.
 */
class ClearTheBoxTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    /** Type, find the x inside the box at its right end, press it, and the box is empty. */
    private suspend fun clears(route: String, box: String, words: String, results: String, size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(AppState().navigate(Route.parse(route))) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"
        val input = all("input.$box").singleOrNull()?.unsafeCast<HTMLInputElement>() ?: error("$on there is no $box")
        val xs = { all("[aria-label=Clear]") }
        assertTrue(xs().isEmpty(), "$on an empty $box already shows an x")

        input.value = words
        input.dispatchEvent(Event("input"))
        settle(s.window)
        assertTrue(all(".$results").isNotEmpty(), "$on typing \"$words\" in $box showed no $results")

        val x = xs().singleOrNull() ?: error("$on $box holds \"$words\" and has ${xs().size} x's to clear it")
        val field = input.getBoundingClientRect()
        val mark = x.getBoundingClientRect()
        assertTrue(
            mark.left >= field.left && mark.right <= field.right && mark.top >= field.top && mark.bottom <= field.bottom,
            "$on the x is not inside $box: ${mark.left}..${mark.right} by ${mark.top}..${mark.bottom}, " +
                "the box is ${field.left}..${field.right} by ${field.top}..${field.bottom}",
        )
        assertTrue(field.right - mark.right <= 16.0, "$on the x is not at the right of $box: ${field.right - mark.right}px short of it")
        assertTrue(mark.left - field.left >= field.width / 2, "$on the x sits in the left half of $box")
        val middle = (field.top + field.bottom) / 2 - (mark.top + mark.bottom) / 2
        assertTrue(abs(middle) <= 3.0, "$on the x is ${middle}px off the middle of $box")
        assertTrue(mark.width >= 24.0 && mark.height >= 24.0, "$on the x is ${mark.width} by ${mark.height}px, too small to hit")
        val room = s.window.getComputedStyle(input).paddingRight.removeSuffix("px").toDouble()
        assertTrue(room >= mark.width, "$on the typing runs under the x: ${room}px of room for a ${mark.width}px x")

        x.click()
        settle(s.window)
        assertEquals("", input.value, "$on pressing the x left \"${input.value}\" in $box")
        assertTrue(all(".$results").isEmpty(), "$on $results are still showing after the x")
        assertTrue(xs().isEmpty(), "$on the x is still there over an empty $box")
        assertTrue(doc.activeElement == input, "$on the x took the focus away from $box")
    }

    @Test
    fun theGuideSearchClearsOnAPhone() = runTest { clears("#/guide", "guide-search", "Sylex", "guide-hits", PHONE) }

    @Test
    fun theGuideSearchClearsOnAFoldingPhoneOpenedOut() =
        runTest { clears("#/guide", "guide-search", "Sylex", "guide-hits", Tablet.UNFOLDED) }

    @Test
    fun theGuideSearchClearsOnATabletHeldSideways() =
        runTest { clears("#/guide", "guide-search", "Sylex", "guide-hits", Tablet.LANDSCAPE) }

    @Test
    fun theGameChangersSearchClearsOnAPhone() =
        runTest { clears("#/guide/${Guide.GAME_CHANGERS}", "gc-search", "Rhystic", "gc-search-count", PHONE) }

    @Test
    fun theGameChangersSearchClearsOnAFoldingPhoneOpenedOut() =
        runTest { clears("#/guide/${Guide.GAME_CHANGERS}", "gc-search", "Rhystic", "gc-search-count", Tablet.UNFOLDED) }

    private companion object {
        val PHONE = 400 to 800
    }
}
