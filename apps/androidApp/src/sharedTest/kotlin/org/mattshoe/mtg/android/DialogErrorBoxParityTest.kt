package org.mattshoe.mtg.android

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.Format
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.RenameState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `.err`, on the phone: the three deck dialogs' error text in a box.
 *
 * `app.css` gives `.err` a tinted face, an edge a shade stronger than
 * that face, `10px 13px` of air, `var(--radius-sm)` and `var(--mono)`
 * at 12.5px, and the new deck wizard, Rename and Disassemble all carry
 * that one class. Android drew the same message as a bare
 * `Text(it, fontSize = 13.sp)` — body size, in the dialog's own prose
 * colour, with nothing round it. In a wizard step already carrying
 * eight lines of prose, the one line saying what went wrong was the
 * hardest line in the dialog to find.
 *
 * Every check here mounts a real `AppShell` over real `AppState` and
 * opens the overlay the activity opens, rather than the dialog
 * composable on its own. Four separate bugs in this project survived a
 * green suite because a component was hosted in isolation — the share
 * menu in an absolute frame, Library rows under a lazy grid's fold, the
 * tweak sheet in a bare `Box`, the filter panel fed a hand-built
 * `Facets` — and a dialog reached only by the test is the same mistake
 * in a new place.
 *
 * What is asserted is the resolved fact and not the source: the text
 * style read back through the `getTextLayoutResult` semantics action,
 * and the box's measured geometry against the text it holds. The two
 * checks that need to see a fill and a ring at all read pixels, so
 * they are `needsRealRendering` and run on a device.
 */
@RunWith(AndroidJUnit4::class)
class DialogErrorBoxParityTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------------------------------------------------------- fixtures

    private fun deck() =
        Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String) = DeckCard(
        name, qty = 1, role = null, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun onDeck() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin(token = "t"),
        decks = DecksState().loaded(listOf(deck())).opened("alela", listOf(card("Sol Ring"))),
    )

    /** Short on purpose, so the box is wider than the words in it. */
    private val said = "Deck not found"

    private fun renameFailed() = onDeck()
        .copy(rename = RenameState(slug = "alela", was = "Alela", error = said))
        .opening(Overlay.RENAME)

    private fun disassembleFailed() = onDeck()
        .copy(
            disassemble = DisassembleState(
                slug = "alela", deckName = "Alela", owner = "matt", error = said,
            ),
        )
        .opening(Overlay.DISASSEMBLE)

    private fun newDeckFailed() = AppState(
        newDeck = NewDeck(format = Format.COMMANDER, owner = Owner.MATT, error = said),
    ).opening(Overlay.NEW_DECK)

    /**
     * A real shell, wired the way `MainActivity` wires it, under the
     * app's own theme rather than Material's defaults — the tint and
     * the edge are then read against the surface the dialog really has.
     */
    private fun shell(start: AppState) {
        rule.setContent {
            MtgTheme {
                val held = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(start)
                }
                AppShell(
                    state = held.value,
                    onState = { held.value = it },
                    onUnlock = {},
                    onSearch = {},
                    onOpenDeck = {},
                    onRunSql = {},
                    onPreviewEntry = {},
                    onApplyEntry = {},
                )
            }
        }
        rule.waitForIdle()
    }

    // ----------------------------------------------------------- reading

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.firstOrNull()
    }

    private fun box() = rule.onNodeWithTag(ERR_TAG, useUnmergedTree = true)

    private fun words() = rule.onNodeWithTag(ERR_TEXT_TAG, useUnmergedTree = true)

    private fun style() = words().textLayout()?.layoutInput?.style

    private fun said() = words().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    /**
     * The whole of `.err`, asserted in one place.
     *
     * The web states it once as a class, so there is one statement of
     * it here and each dialog is held to all of it rather than to
     * whichever part that dialog's test happened to look at.
     */
    private fun assertTheWebsErrBox(where: String) {
        box().assertExists()
        assertEquals(said, said(), "$where: the message is not the text inside the box")
        // `.err { color: var(--bad) }`. Android's was whatever
        // `AlertDialog` hands its text slot, which is the same colour
        // as the ordinary prose directly above it.
        assertEquals(Bad, style()?.color, "$where: the error is the dialog's prose colour")
        // `font-family: var(--mono); font-size: 12.5px` — which
        // `Theme.kt` already carried as `monoSmall`.
        assertEquals(
            FontFamily.Monospace,
            style()?.fontFamily,
            "$where: the error is not in the fixed-width family the web uses",
        )
        assertEquals(
            monoSmall.fontSize,
            style()?.fontSize,
            "$where: the error is not at the size the web sets",
        )
    }

    /**
     * How light a patch of a capture is, 0 to 1.
     *
     * The person who owns this collection cannot separate two hues, so
     * a tint and an edge have to read as differences in lightness.
     * Measuring them is the only way to assert that; a colour constant
     * in the source says nothing about what reached the screen.
     */
    private fun luminance(bmp: Bitmap, rect: Rect): Double {
        var total = 0.0
        var n = 0
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                val p = bmp.getPixel(x, y)
                val r = ((p shr 16) and 0xFF) / 255.0
                val g = ((p shr 8) and 0xFF) / 255.0
                val b = (p and 0xFF) / 255.0
                total += 0.2126 * r + 0.7152 * g + 0.0722 * b
                n++
            }
        }
        return if (n == 0) 0.0 else total / n
    }

    // ============================================== one test per dialog

    @Test
    fun theNewDeckWizardReadsItsErrorInTheWebsErrBox() {
        shell(newDeckFailed())
        rule.onNodeWithText("New deck · ${NewDeck().step.label}").assertExists()
        assertTheWebsErrBox("the new deck wizard")
    }

    @Test
    fun theRenameDialogReadsItsErrorInTheWebsErrBox() {
        shell(renameFailed())
        rule.onNodeWithText("Rename deck").assertExists()
        assertTheWebsErrBox("Rename")
    }

    @Test
    fun theDisassembleDialogReadsItsErrorInTheWebsErrBox() {
        shell(disassembleFailed())
        rule.onNodeWithText("Disassemble this deck?").assertExists()
        assertTheWebsErrBox("Disassemble")
    }

    // ===================================================== the geometry

    @Test
    fun theBoxHoldsItsTextOffTheEdgeTheWayTheWebDoes() {
        shell(renameFailed())
        val outer = box().getUnclippedBoundsInRoot()
        val inner = words().getUnclippedBoundsInRoot()

        // `.err { padding: 10px 13px }`. The words used to sit flush
        // against the sentence above and the buttons below, with
        // nothing but the 8dp column gap between them.
        val left = inner.left.value - outer.left.value
        val top = inner.top.value - outer.top.value
        val bottom = outer.bottom.value - inner.bottom.value
        val right = outer.right.value - inner.right.value

        assertTrue(
            abs(left - ERR_PAD_X) < 1f,
            "the box gives its text ${left}dp on the left, not ${ERR_PAD_X}dp",
        )
        assertTrue(
            abs(top - ERR_PAD_Y) < 1f,
            "the box gives its text ${top}dp above, not ${ERR_PAD_Y}dp",
        )
        assertTrue(
            abs(bottom - ERR_PAD_Y) < 1f,
            "the box gives its text ${bottom}dp below, not ${ERR_PAD_Y}dp",
        )
        // A short line in a box that spans the column: the right-hand
        // gap is the padding plus whatever the line does not use, so
        // at least the padding.
        assertTrue(
            right >= ERR_PAD_X - 1f,
            "the box gives its text ${right}dp on the right, under ${ERR_PAD_X}dp",
        )
    }

    @Test
    fun theBoxSpansTheDialogRatherThanHuggingTheWords() {
        shell(disassembleFailed())
        val outer = box().getUnclippedBoundsInRoot()
        val inner = words().getUnclippedBoundsInRoot()
        // `.err` is a block, so the band reads as a region of the
        // dialog and not as a label stuck to the message.
        assertTrue(
            (outer.right.value - outer.left.value) >
                (inner.right.value - inner.left.value) + ERR_PAD_X,
            "the box is only as wide as the words in it",
        )
    }

    // ======================================================= the pixels

    @Test
    fun theBoxHasAFaceLighterThanTheDialogBehindIt() {
        Parity.needsRealRendering()
        shell(renameFailed())
        val d = rule.density.density
        val outer = box().getUnclippedBoundsInRoot()
        val root = rule.onRoot().captureToImage().asAndroidBitmap()

        fun px(v: Float) = (v * d).toInt()
        // Inside the band, clear of the ring and clear of the glyphs:
        // its right-hand end, which a short message never reaches.
        val face = Rect(
            px(outer.right.value) - px(ERR_PAD_X.toFloat()) - 6,
            px(outer.top.value) + 4,
            px(outer.right.value) - 4,
            px(outer.bottom.value) - 4,
        )
        // Plain surface, just under the band, inside the dialog's own
        // bottom padding.
        val behind = Rect(
            px(outer.left.value) + 4,
            px(outer.bottom.value) + 3,
            px(outer.right.value) - 4,
            px(outer.bottom.value) + 9,
        )

        val faceL = luminance(root, face)
        val behindL = luminance(root, behind)
        assertTrue(
            faceL - behindL > 0.01,
            "the error band is no lighter than the dialog behind it " +
                "(face $faceL, surface $behindL) — a tint only a hue separates " +
                "is no tint at all to the person who reads this",
        )
        with(Parity) { box().shoot("err-box-face") }
    }

    @Test
    fun theBoxHasAnEdgeLighterStillThanItsFace() {
        Parity.needsRealRendering()
        shell(renameFailed())
        val bmp = box().captureToImage().asAndroidBitmap()
        // The edge is the bad tone at 42% over a face of it at 12%, so
        // the ring is the lighter of the two — which is the whole
        // reason there is a ring and not just a fill.
        val ring = luminance(bmp, Rect(bmp.width / 3, 0, 2 * bmp.width / 3, 1))
        val face = luminance(bmp, Rect(bmp.width / 3, 6, 2 * bmp.width / 3, bmp.height - 6))
        assertTrue(
            ring - face > 0.01,
            "the error box has no edge standing off its own face (ring $ring, face $face)",
        )
        with(Parity) { box().shoot("err-box-edge") }
    }
}
