package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertTrue

/**
 * The share control is the share mark, at a size you can see.
 *
 * It was `Line("⤴", …)` at `Design.H2` — a 16sp text arrow, drawn
 * with whatever glyph the system font happened to have for U+2934,
 * inside a 44dp box. Matt: "what the fuck is this symbol for sharing
 * on the deck page in Android?!?! It's fucking TINY and why aren't
 * you using the share symbol like i asked?!?!?!?!?"
 *
 * Both halves of that are fair. The website draws the actual share
 * mark — three nodes and two links between them, `circle` at (18,5),
 * (6,12) and (18,19) joined by two paths, straight out of
 * `.icon-share` in `app.css` — and Android drew a font arrow at two
 * thirds the size of the icon it was standing in for.
 *
 * So: the same geometry as the web, drawn rather than typed, 20dp of
 * mark inside a 48dp target.
 */
@RunWith(AndroidJUnit4::class)
class ShareMarkParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun deck() = Deck(
        "alela", "Faerie Swarm", "matt",
        "Alela, Artful Provocateur (ELD) 324", "UW", 3, null,
    )

    private fun card(name: String, role: String? = null) = DeckCard(
        name, qty = 1, role = role, owned = 1,
        nameNorm = name.lowercase(), typeLine = "Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin().signIn(Account(slug = "matt", role = "admin"), "t"),
        decks = DecksState().loaded(listOf(deck())).opened(
            "alela",
            listOf(card("Alela, Artful Provocateur", "commander"), card("Sol Ring")),
        ),
    )

    private fun shell() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(opened())
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
    }

    /**
     * The mark's own node.
     *
     * `useUnmergedTree`, because `Modifier.clickable` on the button
     * sets `shouldMergeDescendantSemantics` and folds everything
     * underneath it into the one node — so the tag is there and the
     * merged tree cannot see it. The same trap that briefly blinded
     * seventy tests when a scrim used `clickable` in item 4.7.
     */
    private fun mark() = rule.onNodeWithTag(SHARE_MARK_TAG, useUnmergedTree = true)

    private fun marks() =
        rule.onAllNodesWithTag(SHARE_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun theMarkIsDrawnRatherThanTypedAsAFontGlyph() {
        shell()
        // U+2934 is not a share symbol, it is an arrow pointing up and
        // right, and what it looks like depends entirely on which font
        // the device has. There is no reason for a share button's
        // appearance to vary by handset.
        assertTrue(
            rule.onAllNodes(hasText("⤴", substring = true)).fetchSemanticsNodes().isEmpty(),
            "the share control is still a text arrow",
        )
        assertTrue(
            marks().isNotEmpty(),
            "nothing drew a share mark",
        )
    }

    @Test
    fun theMarkIsBigEnoughToRecognise() {
        shell()
        val mark = mark().getUnclippedBoundsInRoot()
        val w = mark.right.value - mark.left.value
        val h = mark.bottom.value - mark.top.value
        // The web's `.btn.icon-only` is a 30px box with the mark
        // filling it. A 16sp glyph in a 44dp box was about 11dp of
        // actual ink, which is what "fucking TINY" was describing.
        assertTrue(w >= 19f && h >= 19f, "the share mark is only ${w}x${h}dp")
    }

    @Test
    fun andStillHasAThumbSizedTargetRoundIt() {
        shell()
        // Item 4.8's floor, which the mark growing must not quietly
        // break: the thing you press stays at least 48dp even though
        // the thing you see is 20.
        val target = rule.onNodeWithContentDescription("Share this deck")
            .getUnclippedBoundsInRoot()
        val w = target.right.value - target.left.value
        val h = target.bottom.value - target.top.value
        assertTrue(w >= 47.5f && h >= 47.5f, "the share target is ${w}x${h}dp, under 48")
    }

    @Test
    fun theControlStillSaysWhatItIs() {
        shell()
        // Drawn, so there is no text for a screen reader to read and
        // the description is the only thing it has.
        rule.onNodeWithContentDescription("Share this deck").assertExists()
    }

    @Test
    fun theMarkLooksLikeTheWebs() {
        // Three nodes and two links is a shape, and a shape needs
        // pixels. Device only, and unproven until `test:android` runs.
        Parity.needsRealRendering()
        shell()
        mark().shoot("share-mark")
    }
}
