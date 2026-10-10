package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardZoom
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pinch zoom on the carousel's card, the website's half.
 *
 * Matt: "pinch zoom should zoom in and stay at the specified zoom
 * tapping back should fully zoom out (as well as pinching to zoom all
 * the way out) swiping to the next card should only work while fully
 * zoomed out you should be able to drag the image around to move the
 * visible part of the image with 1 finger while zoomed in"
 *
 * Pointer events on the real carousel inside the real `AppShell`, and
 * the assertions are what the browser resolved: the image's computed
 * transform and the rail's computed overflow. The phone's
 * `CarouselPinchZoomTest` is the same claims with real fingers.
 */
class CarouselPinchZoomTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(name: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 3,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
    )

    private fun onADeck() = AppState(admin = Admin().signIn(Account("matt"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring"))),
            )
        }

    private suspend fun peeking() {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(onADeck()) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onTweak = { _, _ -> },
            )
        }
        settle()
        document.querySelectorAll(".deck-line").let { l -> (0 until l.length).map { l[it] as HTMLElement } }
            .first { it.textContent?.contains("Cultivate") == true }
            .click()
        settle()
        assertEquals("Cultivate", held.value.peeked?.title, "the carousel did not open on Cultivate")
    }

    private val zoom: CardZoom get() = held.value.peek.zoom

    /** The card on show: the rail's second cell, Cultivate. */
    private fun shown(): HTMLElement =
        assertNotNull(document.querySelectorAll(".peek-card")[1] as? HTMLElement, "no second card in the rail")

    private fun rail(): HTMLElement = assertNotNull(document.querySelector(".peek-rail") as? HTMLElement)

    private fun centre(): Pair<Double, Double> {
        val r = shown().getBoundingClientRect()
        return (r.left + r.width / 2) to (r.top + r.height / 2)
    }

    private fun finger(type: String, id: Int, x: Double, y: Double) {
        shown().dispatchEvent(
            PointerEvent(
                type,
                PointerEventInit(
                    pointerId = id,
                    pointerType = "touch",
                    isPrimary = id == 1,
                    clientX = x.toInt(),
                    clientY = y.toInt(),
                    bubbles = true,
                    cancelable = true,
                ),
            ),
        )
    }

    /** Two fingers from [from] apart to [to] apart, about the centre. */
    private suspend fun pinch(from: Double, to: Double) {
        val (cx, cy) = centre()
        finger("pointerdown", 1, cx - from, cy)
        finger("pointerdown", 2, cx + from, cy)
        val steps = 6
        for (i in 1..steps) {
            val d = from + (to - from) * i / steps
            finger("pointermove", 1, cx - d, cy)
            finger("pointermove", 2, cx + d, cy)
        }
        finger("pointerup", 1, cx - to, cy)
        finger("pointerup", 2, cx + to, cy)
        settle()
    }

    private fun scaleOnScreen(): Double {
        val face = assertNotNull(shown().firstElementChild as? HTMLElement, "the card cell is empty")
        val t = window.getComputedStyle(face).transform
        if (t == "none") return 1.0
        return t.substringAfter("(").substringBefore(",").trim().toDouble()
    }

    @Test
    fun spreadingTwoFingersZoomsTheImageAndItStays() = runTest {
        peeking()
        pinch(20.0, 120.0)
        assertTrue(zoom.scale > 1.5f, "two fingers spread on the card and the app holds ${zoom.scale}x")
        assertTrue(scaleOnScreen() > 1.5, "the image is drawn at ${scaleOnScreen()}x")
    }

    @Test
    fun squeezingAllTheWayOutZoomsFullyOut() = runTest {
        peeking()
        pinch(20.0, 120.0)
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about squeezing")
        pinch(150.0, 5.0)
        assertEquals(CardZoom(), zoom, "squeezed all the way and the card is still zoomed")
        assertEquals(1.0, scaleOnScreen(), "the image is still drawn zoomed")
    }

    @Test
    fun theRailStopsScrollingWhileZoomedIn() = runTest {
        peeking()
        assertEquals("auto", window.getComputedStyle(rail()).overflowX, "the rail did not scroll to begin with")
        pinch(20.0, 120.0)
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about swiping")
        assertEquals("hidden", window.getComputedStyle(rail()).overflowX, "the rail still swipes while zoomed in")
        assertEquals("none", window.getComputedStyle(shown()).getPropertyValue("touch-action"))
    }

    @Test
    fun oneFingerMovesTheArtWhileZoomedIn() = runTest {
        peeking()
        pinch(20.0, 120.0)
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about dragging")
        val before = zoom
        val (cx, cy) = centre()
        finger("pointerdown", 3, cx, cy)
        for (i in 1..6) finger("pointermove", 3, cx - 12.0 * i, cy + 9.0 * i)
        finger("pointerup", 3, cx - 72.0, cy + 54.0)
        settle()
        assertTrue(zoom.x < before.x - 20f, "a drag left did not move the art left: ${before.x} -> ${zoom.x}")
        assertTrue(zoom.y > before.y + 20f, "a drag down did not move the art down: ${before.y} -> ${zoom.y}")
        assertEquals("Cultivate", held.value.peeked?.title, "the drag changed card")
    }
}
