package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardZoom
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinch zoom on the carousel's card.
 *
 * Matt: "pinch zoom should zoom in and stay at the specified zoom
 * tapping back should fully zoom out (as well as pinching to zoom all
 * the way out) swiping to the next card should only work while fully
 * zoomed out you should be able to drag the image around to move the
 * visible part of the image with 1 finger while zoomed in"
 *
 * Fingers on the real pager inside the real `AppShell`, over a
 * `ComponentActivity` so Back is the dispatcher `MainActivity` uses.
 * The pager is the thing that would otherwise eat every one of these
 * gestures, so a carousel mounted without it would prove nothing.
 */
@RunWith(AndroidJUnit4::class)
class CarouselPinchZoomTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    /**
     * A test in this class that stalls writes every thread's stack
     * into `build/test-order.log`, which CI uploads.
     *
     * The first two CI runs of this class hung the screens task until
     * its 12-minute timeout, and a hung test leaves no XML and no
     * trace of where it stopped.
     */
    private var watchdog: java.util.Timer? = null

    @org.junit.Before
    fun watch() {
        val name = javaClass.simpleName
        watchdog = java.util.Timer(true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        val dump = StringBuilder("STALLED $name, every thread:\n")
                        Thread.getAllStackTraces().forEach { (t, frames) ->
                            if ("Main Thread" !in t.name) return@forEach
                            dump.append("  thread ").append(t.name).append('\n')
                            frames.filterNot { "java.lang.invoke" in it.className }
                                .take(400)
                                .forEach { dump.append("    at ").append(it).append('\n') }
                        }
                        runCatching { java.io.File("build/test-order.log").appendText(dump.toString()) }
                        println(dump)
                    }
                },
                90_000L,
            )
        }
    }

    @org.junit.After
    fun stopWatching() {
        watchdog?.cancel()
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

    private fun onADeck(): AppState =
        AppState(admin = Admin().signIn(Account("matt"), "t"))
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                        .opened("alela", listOf(card("Counterspell"), card("Cultivate"), card("Sol Ring"))),
                )
            }

    private fun peeking() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        // Opened through the state rather than a tap on
                        // the row: the tap is `DeckCardCarouselParityTest`'s
                        // claim, and scrolling the deck to the row hung
                        // this class on CI in a scroll animation that
                        // never settled, before any finger touched the
                        // card.
                        androidx.compose.runtime.mutableStateOf(onADeck().peekAt(1))
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals("Cultivate", held.value.peeked?.title, "the carousel did not open on Cultivate")
    }

    private val zoom: CardZoom get() = held.value.peek.zoom

    private fun spread() {
        rule.onNodeWithTag("carousel-pager").performTouchInput {
            // Inside the card, as fractions of it: Robolectric's
            // screen is narrow, and a finger off the window is not a
            // finger on the card.
            pinch(
                start0 = center + Offset(-width * 0.05f, 0f),
                end0 = center + Offset(-width * 0.3f, 0f),
                start1 = center + Offset(width * 0.05f, 0f),
                end1 = center + Offset(width * 0.3f, 0f),
            )
        }
        rule.waitForIdle()
    }

    private fun squeeze() {
        rule.onNodeWithTag("carousel-pager").performTouchInput {
            pinch(
                start0 = center + Offset(-width * 0.35f, 0f),
                end0 = center + Offset(-2f, 0f),
                start1 = center + Offset(width * 0.35f, 0f),
                end1 = center + Offset(2f, 0f),
            )
        }
        rule.waitForIdle()
    }

    private fun swipeToTheNext() {
        rule.onNodeWithTag("carousel-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    @Test
    fun spreadingTwoFingersZoomsInAndStays() {
        peeking()
        spread()
        assertTrue(zoom.scale > 1.5f, "two fingers spread on the card and it is at ${zoom.scale}x")
    }

    @Test
    fun squeezingAllTheWayOutZoomsFullyOut() {
        peeking()
        spread()
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about squeezing")
        squeeze()
        assertEquals(CardZoom(), zoom, "squeezed all the way and the card is still zoomed")
    }

    @Test
    fun aSwipeDoesNotChangeCardWhileZoomedIn() {
        peeking()
        spread()
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about swiping")
        swipeToTheNext()
        assertEquals("Cultivate", held.value.peeked?.title, "swiped to another card while zoomed in")
    }

    @Test
    fun aSwipeStillChangesCardZoomedOut() {
        peeking()
        swipeToTheNext()
        assertEquals("Sol Ring", held.value.peeked?.title, "the swipe stopped working with no zoom at all")
    }

    @Test
    fun oneFingerMovesTheArtWhileZoomedIn() {
        peeking()
        spread()
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about dragging")
        val before = zoom
        rule.onNodeWithTag("carousel-pager").performTouchInput {
            swipe(center, center + Offset(-80f, 60f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue(zoom.x < before.x - 20f, "a drag left did not move the art left: ${before.x} -> ${zoom.x}")
        assertTrue(zoom.y > before.y + 20f, "a drag down did not move the art down: ${before.y} -> ${zoom.y}")
        assertEquals("Cultivate", held.value.peeked?.title, "the drag changed card")
    }

    @Test
    fun backZoomsOutAndLeavesTheCarouselUp() {
        peeking()
        spread()
        assertTrue(zoom.zoomed, "the spread did not zoom, so this proves nothing about back")
        pressBack()
        assertEquals(CardZoom(), zoom, "back did not zoom fully out")
        assertTrue(Overlay.CARD_PEEK in held.value.overlays, "back closed the carousel instead of zooming out")
        pressBack()
        assertTrue(Overlay.CARD_PEEK !in held.value.overlays, "the second back did not close the carousel")
    }
}
