package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a screen opens.
 *
 * The app is one document at one address, so the browser has no
 * navigation to restore a scroll offset for and leaves the page where
 * the last screen left it. Opening a deck from halfway down the list
 * opened the deck halfway down, which is what this is here to stop
 * coming back.
 */
class ScrollTest {

    private val tall = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        tall.forEach { it.remove() }
        tall.clear()
        window.scrollTo(0.0, 0.0)
    }

    /** A page with somewhere to scroll to. */
    private fun aLongPage(px: Int = 4000): HTMLElement {
        val filler = document.createElement("div") as HTMLElement
        filler.style.height = "${px}px"
        document.body!!.appendChild(filler)
        tall += filler
        return filler
    }

    private suspend fun frames(n: Int = 6) = repeat(n) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    @Test
    fun somewhereNewStartsAtTheTop() = runTest {
        aLongPage()
        window.scrollTo(0.0, 1400.0)
        assertTrue(window.scrollY > 1000, "the page did not scroll at all")
        Scroll.top()
        assertEquals(0.0, window.scrollY)
    }

    @Test
    fun comingBackGoesBackToWhereYouWere() = runTest {
        aLongPage()
        window.scrollTo(0.0, 900.0)
        Scroll.remember("#/library")
        Scroll.top()
        assertEquals(0.0, window.scrollY)
        Scroll.restore("#/library")
        frames()
        assertTrue(window.scrollY > 880, "came back to ${window.scrollY}, not 900")
    }

    @Test
    fun aScreenNeverVisitedStartsAtTheTop() = runTest {
        aLongPage()
        window.scrollTo(0.0, 1200.0)
        Scroll.restore("#/decks/never-opened")
        frames()
        assertEquals(0.0, window.scrollY)
    }

    @Test
    fun theOffsetIsChasedUntilTheRowsArrive() = runTest {
        // The list is a network round trip behind the route, so a
        // single scrollTo lands in an empty page and does nothing.
        val filler = aLongPage()
        window.scrollTo(0.0, 1500.0)
        Scroll.remember("#/library")
        filler.style.height = "0px"
        Scroll.top()
        Scroll.restore("#/library")
        frames(2)
        assertTrue(window.scrollY < 10, "there was nowhere to scroll to yet")
        filler.style.height = "4000px"
        frames(10)
        assertTrue(window.scrollY > 1400, "the rows arrived and the offset was forgotten: ${window.scrollY}")
    }

    @Test
    fun aSecondRequestWinsOverTheOneStillChasing() = runTest {
        val filler = aLongPage()
        window.scrollTo(0.0, 1000.0)
        Scroll.remember("#/library")
        filler.style.height = "0px"
        // One chase running for an offset that cannot be reached yet,
        // and then a navigation somewhere new. The new screen wins.
        Scroll.restore("#/library")
        Scroll.top()
        filler.style.height = "4000px"
        frames(10)
        assertEquals(0.0, window.scrollY, "an old chase dragged the new screen down")
    }
}
