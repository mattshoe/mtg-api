package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "tapping the row (outside checkbox) should show the carousel,
 * and the bottom part should have the checkbox as well so i can swipe
 * through and check them off as i swipe. careful to keep stats in sync
 * between carousel and other page", and "i also want the ability to
 * remove a card from the fetch list. make sure doing so actually edits
 * the deck list text as well".
 *
 * The real `AppShell` on the list page, on a phone and on both tablet
 * shapes. The phone's `FetchListCarouselTest` is the same claims.
 */
class FetchListCarouselTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus"

    private class Page(val doc: Document, val held: MutableState<AppState>, val w: org.w3c.dom.Window, val size: Pair<Int, Int>) {
        val on = "on a ${size.first} by ${size.second} screen"
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        fun one(css: String, why: String) = all(css).singleOrNull() ?: error("$on $why")
        fun card(name: String) = all("[data-fetch-card]").singleOrNull { it.getAttribute("data-fetch-card") == name }
            ?: error("$on $name is not on the list")
        fun box(name: String) = one("[data-fetch-tick=\"$name\"]", "$name has no box").unsafeCast<HTMLInputElement>()
        val progress get() = one("[data-fetch-progress]", "the page has no count").textContent?.trim()
    }

    private suspend fun open(size: Pair<Int, Int>): Page {
        val start = AppState(
            admin = Admin().signIn(Account(key = "e7de0cb1"), "t"),
            route = Route(View.FETCH, FetchListState.PAGE),
            fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
        )
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(start)
        renderComposable(root = s.body) {
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
        return Page(s.frame.contentDocument!!, held, s.window, size)
    }

    private suspend fun tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(size: Pair<Int, Int>) {
        val p = open(size)
        p.card("Sol Ring").querySelector(".fetch-name")!!.unsafeCast<HTMLElement>().click()
        settle(p.w)
        assertTrue(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} tapping Sol Ring's row opened no carousel")
        assertEquals(PeekOf.FETCH, p.held.value.peek.of)
        assertFalse(p.held.value.fetch.isTicked("Sol Ring"), "${p.on} tapping the row ticked the box as well")
        assertEquals("Sol Ring", p.one(".peek-sheet .peek-name", "the carousel is not drawn").textContent)
        // The list here has no printing, as one kept from before does.
        assertTrue(p.all(".peek-blank").isEmpty(), "${p.on} the carousel draws Sol Ring's name and no art")
        val img = p.all(".peek-card img").firstOrNull { it.getAttribute("alt") == "Sol Ring" }
            ?: error("${p.on} the carousel has no picture of Sol Ring")
        assertTrue("Sol%20Ring" in img.getAttribute("src").orEmpty(), "${p.on} the picture is not Sol Ring: ${img.getAttribute("src")}")

        val box = p.one("[data-peek-tick]", "the carousel's sheet has no Found box").unsafeCast<HTMLInputElement>()
        val r = box.getBoundingClientRect()
        assertTrue(r.width >= 16.0 && r.height >= 16.0, "${p.on} the carousel's box is not drawn: ${r.width} by ${r.height}")
        assertTrue(r.bottom <= size.second + 0.5 && r.right <= size.first + 0.5, "${p.on} the carousel's box is off screen: $r")
        assertFalse(box.checked, "${p.on} the carousel's box starts ticked")

        box.click()
        settle(p.w)
        assertTrue(p.held.value.fetch.isTicked("Sol Ring"), "${p.on} the carousel's box did not tick Sol Ring")
        assertEquals("1 of 2 found", p.one("[data-peek-progress]", "the carousel has no count").textContent?.trim(), p.on)
        assertEquals("1 of 2 found", p.progress, "${p.on} the page's count did not follow the carousel")
        assertTrue(p.box("Sol Ring").checked, "${p.on} the page's box did not follow the carousel")

        // Swipe back to Counterspell with the arrow and tick it too.
        p.one("[aria-label=\"Previous card\"]", "no previous arrow").click()
        settle(p.w)
        assertEquals("Counterspell", p.one(".peek-sheet .peek-name", "the carousel is gone").textContent)
        p.one("[data-peek-tick]", "the box went with the swipe").click()
        settle(p.w)
        assertEquals("2 of 2 found", p.progress, "${p.on} the page did not count the second tick")
    }

    private suspend fun theBoxStillTicksWithoutOpeningTheCarousel(size: Pair<Int, Int>) {
        val p = open(size)
        p.box("Counterspell").click()
        settle(p.w)
        assertTrue(p.held.value.fetch.isTicked("Counterspell"), "${p.on} the page's box no longer ticks")
        assertFalse(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} ticking the box opened the carousel")
        assertEquals("1 of 2 found", p.progress)
    }

    private suspend fun removeTakesTheCardOutOfTheListText(size: Pair<Int, Int>) {
        val p = open(size)
        val remove = p.one("[data-fetch-remove=\"Counterspell\"]", "Counterspell has no Remove")
        val r = remove.getBoundingClientRect()
        assertTrue(r.width > 8.0 && r.right <= size.first + 0.5, "${p.on} Remove is not drawn on screen: $r")
        // An x, like a normal app, and not the word.
        assertEquals("×", remove.textContent?.trim(), "${p.on} Remove is the word, not an x")
        assertTrue(r.width <= 44.0, "${p.on} the x is ${r.width} wide, the width of a word")
        remove.click()
        settle(p.w)
        assertFalse(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} Remove opened the carousel")
        assertTrue(p.all("[data-fetch-card=\"Counterspell\"]").isEmpty(), "${p.on} Counterspell is still on the page")
        assertEquals("0 of 1 found", p.progress, "${p.on} the page still counts Counterspell")

        p.one("[data-fetch-remove=\"Black Lotus\"]", "Black Lotus, not owned, has no Remove").click()
        settle(p.w)
        assertTrue(p.all("[data-fetch-missing]").isEmpty(), "${p.on} Black Lotus is still listed as missing")

        p.one("[data-fetch-edit]", "no Edit").click()
        settle(p.w)
        val text = p.doc.getElementById("fetch-edit")?.unsafeCast<HTMLTextAreaElement>()?.value
        assertEquals("1 Sol Ring", text, "${p.on} the list text Edit opens still has the removed cards")
        assertNull(p.held.value.fetch.list.lines().firstOrNull { "Counterspell" in it })
    }

    @Test
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePageOnAPhone() = runTest {
        tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(390 to 844)
    }

    @Test
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePageOnAFoldingPhoneOpenedOut() = runTest {
        tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(Tablet.UNFOLDED)
    }

    @Test
    fun tappingARowOpensTheCarouselAndItsBoxTicksThePageOnATabletHeldSideways() = runTest {
        tappingTheRowOpensTheCarouselAndItsBoxTicksThePage(Tablet.LANDSCAPE)
    }

    @Test
    fun theBoxOnTheRowStillTicksWithoutOpeningTheCarousel() = runTest { theBoxStillTicksWithoutOpeningTheCarousel(390 to 844) }

    @Test
    fun removeTakesTheCardOutOfTheListTextOnAPhone() = runTest { removeTakesTheCardOutOfTheListText(390 to 844) }

    @Test
    fun removeTakesTheCardOutOfTheListTextOnAFoldingPhoneOpenedOut() = runTest {
        removeTakesTheCardOutOfTheListText(Tablet.UNFOLDED)
    }

    @Test
    fun removeTakesTheCardOutOfTheListTextOnATabletHeldSideways() = runTest {
        removeTakesTheCardOutOfTheListText(Tablet.LANDSCAPE)
    }
}
