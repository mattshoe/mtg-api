package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tweak sheet is a modal, pressed inside the real shell.
 *
 * It was not one. `DeckTweakSheet` was a bare `Column` appended to the
 * end of `AppShell`, which gave it none of the three things a modal
 * is: nothing dimmed the deck behind it, nothing outside it dismissed
 * it, and nothing capped its height — a swap with ten hits in the
 * finder simply kept drawing until the Preview button was somewhere
 * past the bottom edge of the phone. Every other overlay in this app
 * is an `AlertDialog` and the website has always drawn this one inside
 * `.palette-scrim`.
 *
 * `TweakSheetParityTest` has seventy tests about this sheet and not one
 * of them could see any of it, because it hosted the sheet in a `Box`
 * of its own: a box has no screen to run off, nothing behind it to dim,
 * and no outside to tap. That mount is fixed too, and this file is the
 * other half — the sheet reached the way a person reaches it, through
 * `AppShell`, over a deck, on a screen with edges.
 *
 * The fourth time an isolated mount hid a real bug in this project: the
 * share menu in an absolute frame, Library rows below a lazy grid's
 * fold, this sheet in a `Box`, and the filter panel fed a hand-built
 * `Facets`.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TweakSheetIsADialogTest {

    @get:Rule
    val rule = createComposeRule()

    private var previewAsked = 0

    /** The screen the sheet has to fit on, in dp. */
    private val tall: Int
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenHeightDp

    // --------------------------------------------------------- fixtures

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun found(name: String, qty: Int, owner: String, id: Long) =
        Found(id, name, "sf-$id", "Instant", qty, owner)

    /** A deck, open, read the way the app reads one. */
    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin(token = "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(card("Alela, Artful Provocateur", "commander"), card("Sol Ring")),
        ),
    )

    /** The sheet as it opens on a tapped card: one choice to make. */
    private fun short() = DeckTweak.on(
        deck(), "Alela, Artful Provocateur", card("Sol Ring"), Tweak.SWAP,
    )

    /**
     * The sheet at its tallest, which is the shape that exposed this.
     *
     * Ten hits at 44dp each, plus the subject, the box, the counter,
     * the summary and the foot. On any phone that is more than a
     * screenful, so it is the state that says whether the height is
     * capped or merely unbounded.
     */
    private fun long() = DeckTweak.add(deck(), "Alela, Artful Provocateur")
        .typed("lightning")
        .searched(
            listOf(
                found("Lightning Bolt", 4, "matt", 1),
                found("Lightning Greaves", 2, "matt", 2),
            ),
            listOf(
                "Lightning Helix", "Lightning Strike", "Lightning Axe",
                "Lightning Mauler", "Lightning Runner", "Lightning Coils",
                "Lightning Crafter", "Lightning Diadem",
            ),
        )

    /**
     * A real `AppShell` over mutable state, the way `MainActivity`
     * mounts it, with the tweak overlay already open.
     *
     * Not `DeckTweakSheet` on its own. The whole defect lives in how
     * the shell puts the sheet on the screen, so a test that mounts
     * the sheet by itself is testing the one thing that was never
     * wrong.
     */
    private fun shell(tweak: DeckTweak): () -> AppState {
        val start = opened().copy(deckTweak = tweak).opening(Overlay.DECK_TWEAK)
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    val live = held.value
                    state = live
                    AppShell(
                        state = live,
                        onState = { held.value = it; state = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onRunSql = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onTweakPreview = { previewAsked++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    // ---------------------------------------------------------- reading

    private fun present(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun box(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun height(tag: String) = box(tag).let { it.bottom.value - it.top.value }

    /** How light a region of a node is, 0 to 1, from its real pixels. */
    private fun lightness(tag: String, fromY: Float, toY: Float): Double {
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val top = (bmp.height * fromY).toInt().coerceIn(0, bmp.height - 1)
        val bottom = (bmp.height * toY).toInt().coerceIn(top + 1, bmp.height)
        var total = 0.0
        var n = 0
        for (y in top until bottom step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                total += 0.2126 * (((p shr 16) and 0xFF) / 255.0) +
                    0.7152 * (((p shr 8) and 0xFF) / 255.0) +
                    0.0722 * ((p and 0xFF) / 255.0)
                n++
            }
        }
        return if (n == 0) 0.0 else total / n
    }

    private fun relativeLightness(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    // ============================================= it is a real dialog

    @Test
    fun theSheetIsADialogAndNotTheNextThingAfterTheDeck() {
        shell(short())
        // A dialog is its own window, which is what back, the scrim and
        // the height cap all hang off. Appended to the shell's own
        // layout it was none of those things.
        rule.onNode(isDialog()).assertExists()
        assertTrue(present("tweak-sheet"), "the sheet is not on screen at all")
    }

    // ===================================================== 1. the scrim

    @Test
    fun thereIsAScrimBetweenTheSheetAndTheDeck() {
        shell(short())

        assertTrue(
            present("tweak-scrim"),
            "there is nothing between the sheet and the deck underneath it",
        )
        val scrim = box("tweak-scrim")
        val sheet = box("tweak-sheet")

        // It has to cover the screen, or the deck is still half in
        // reach and the sheet does not read as modal.
        assertTrue(
            scrim.bottom.value - scrim.top.value >= tall * 0.9f,
            "the scrim is ${scrim.bottom.value - scrim.top.value}dp tall on a ${tall}dp screen",
        )
        // And it has to be visible, which means the sheet cannot fill
        // it. 12vh above, 14px below, the same as `.palette-scrim`.
        assertTrue(
            sheet.top.value > scrim.top.value,
            "the sheet starts at the top of the scrim, so none of the dim shows above it",
        )
        assertTrue(
            sheet.bottom.value < scrim.bottom.value,
            "the sheet runs to the bottom of the scrim, so none of the dim shows below it",
        )
        assertTrue(
            sheet.left.value > scrim.left.value && sheet.right.value < scrim.right.value,
            "the sheet runs edge to edge, so the dim shows at neither side",
        )
    }

    @Test
    fun andTheScrimIsDarkerThanTheScreenItCovers() {
        // A colour constant in the source proves nothing about what
        // reached the screen, and the person who owns this collection
        // reads "out of reach" as darker rather than as a hue.
        Parity.needsRealRendering()
        shell(short())

        // The top strip of the scrim, which is above the sheet, so it
        // is the deck seen through the dim and nothing else.
        val dimmed = lightness("tweak-scrim", 0f, 0.08f)
        assertTrue(
            dimmed < relativeLightness(Design.BG),
            "the area around the sheet is $dimmed, no darker than the page behind it",
        )
    }

    // ============================================ 2. tapping outside it

    @Test
    fun aTapOutsideTheSheetClosesIt() {
        val read = shell(short())

        // Four pixels from the top of the scrim: above the sheet, which
        // starts 12vh down. There was nowhere to aim this before — the
        // sheet was the only thing drawn.
        rule.onNodeWithTag("tweak-scrim").performTouchInput { click(Offset(width / 2f, 4f)) }
        rule.waitForIdle()

        assertTrue(
            Overlay.DECK_TWEAK !in read().overlays,
            "a tap outside the sheet left it open",
        )
        assertTrue(!present("tweak-sheet"), "the overlay closed but the sheet is still drawn")
    }

    @Test
    fun butATapOnTheSheetItselfDoesNot() {
        // The web's `stopPropagation()`. Without it the scrim's own
        // handler sits under every gap in the sheet, so reaching for
        // the gap between two rows shuts the thing you were reading.
        val read = shell(short())

        val head = rule.onNodeWithTag("tweak-head").getUnclippedBoundsInRoot()
        rule.onNodeWithTag("tweak-head").performTouchInput {
            click(Offset(width / 2f, height / 2f))
        }
        rule.waitForIdle()

        assertTrue(
            Overlay.DECK_TWEAK in read().overlays,
            "a tap on the sheet's own head at ${head.left}..${head.right} closed it",
        )
        assertTrue(present("tweak-sheet"), "the sheet went away when it was tapped")
    }

    // ============================================== 3. the height cap

    @Test
    fun aLongSheetIsCappedRatherThanRunningOffTheBottom() {
        shell(long())

        // Ten hits, so the content is taller than the screen. Without
        // a cap the sheet is as tall as the screen and sits against
        // both edges of it, under the clock at the top and the
        // gestures at the bottom.
        val sheet = box("tweak-sheet")
        assertTrue(
            height("tweak-sheet") <= tall * 0.82f + 1f,
            "the sheet is ${height("tweak-sheet")}dp on a ${tall}dp screen, " +
                "past the ${tall * 0.82f}dp cap the website keeps",
        )
        assertTrue(
            sheet.bottom.value < tall,
            "the sheet's last pixel is at ${sheet.bottom.value}dp on a ${tall}dp screen",
        )
    }

    @Test
    fun andWhatIsPastTheCapScrollsIntoReach() {
        val read = shell(long())

        // The last hit starts below the sheet's own bottom edge, which
        // is the whole point of capping it: the sheet has to be
        // scrollable, not merely shorter.
        assertTrue(present("tweak-hit-9"), "the ten hits are not all on screen")
        assertTrue(
            box("tweak-hit-9").top.value > box("tweak-sheet").bottom.value,
            "the tenth hit already fits, so this proves nothing about scrolling",
        )

        rule.onNodeWithTag("tweak-hit-9").performScrollTo()
        rule.waitForIdle()
        val sheet = box("tweak-sheet")
        val hit = box("tweak-hit-9")
        assertTrue(
            hit.top.value >= sheet.top.value - 1f && hit.bottom.value <= sheet.bottom.value + 1f,
            "the tenth hit did not scroll inside the sheet: $hit against $sheet",
        )

        // And it is a live control once it is there, not a picture of
        // one that scrolled into view.
        rule.onNodeWithTag("tweak-hit-9").performClick()
        rule.waitForIdle()
        assertEquals(
            "Lightning Diadem",
            read().deckTweak?.pick?.name,
            "the hit past the fold could be reached but not pressed",
        )
    }

    @Test
    fun andTheFootOfALongSheetCanBeReachedAndPressed() {
        val read = shell(long())

        // Pick a card first, so Preview is live rather than refused,
        // then the foot is the thing at the end of a tall sheet.
        rule.onNodeWithTag("tweak-hit-0").performScrollTo().performClick()
        rule.waitForIdle()
        assertNotNull(read().deckTweak?.pick, "the card was not picked")

        rule.onNodeWithTag("tweak-foot").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithTag("tweak-foot").assertExists()
        rule.onNodeWithText("Preview →").performClick()
        rule.waitForIdle()
        assertEquals(1, previewAsked, "Preview at the end of a long sheet never reached the shell")
    }
}
