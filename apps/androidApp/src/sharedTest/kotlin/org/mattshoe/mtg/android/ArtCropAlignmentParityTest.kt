package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState

/**
 * The deck tile, hero, card row and token thumbnail all crop toward
 * the top of the art rather than the centre — `object-position:
 * center 38%` / `34%` / `32%` on the web, because that is where a
 * creature's face sits.
 *
 * This is not independently checkable on the JVM. `AsyncImage`'s
 * `alignment` only has anything to bite on once a real image has
 * decoded, and Coil never reaches the network under Robolectric —
 * there is no painter, so there is no crop to measure, with or
 * without the fix. `Modifier.border` and a CSS grid's gap-over-a-line
 * background have the same problem for 4.4's thumbnail border and
 * figure seam: both are draw-only and move no layout bounds. Per
 * `Parity.needsRealRendering`, these are gated to the device suite —
 * skipped here, not quietly passed. The production fix (each
 * `AsyncImage` call in `DecksScreen.kt` now passes a `BiasAlignment`
 * matching the web's percentage) is verified by code review and by
 * the screenshots this test pulls off a real device.
 */
@RunWith(AndroidJUnit4::class)
class ArtCropAlignmentParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun deck(key: String, owner: String, name: String = key) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, "abcdef12-3456")

    private fun card(name: String, type: String?, role: String? = null) =
        DeckCard(
            name, qty = 1, role = role, owned = 1,
            nameNorm = name.lowercase(), typeLine = type, scryfallId = "abcdef12-3456",
        )

    private fun opened() = DecksState()
        .loaded(listOf(deck("a", "matt", "Alela")))
        .opened("a", listOf(card("Alela, Artful Provocateur", "Legendary Creature — Faerie", "commander")))

    @Test
    fun theDeckTileIsPhotographedForACropReview() {
        Parity.needsRealRendering()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    DecksScreen(
                        DecksState().loaded(listOf(deck("a", "matt", "Alela"))),
                        {},
                        {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().shoot("crop-deck-tile")
    }

    @Test
    fun theHeroAndTheCardRowArePhotographedForACropReview() {
        Parity.needsRealRendering()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Surface { DecksScreen(opened(), {}, {}) } }
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().shoot("crop-hero-and-card-row")
    }
}
