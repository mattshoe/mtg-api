package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hairlines and borders the web draws and Android left out: a rule
 * under an owner heading (`.owner-head { border-bottom }`), a shaded
 * band with a rule under a deck-list group heading (`.panel-head`),
 * and a divider between card rows — but never before the first one
 * (`.deck-line + .deck-line { border-top }`).
 *
 * The thumbnail border and the figure-cell seam are drawing-only —
 * `Modifier.border` changes no layout bounds and exposes no
 * semantics — so they are not independently checkable here; they are
 * production fixes with device screenshots as evidence, not a JVM
 * assertion. See `DecksParityTest` / `DeckStatsParityTest` screenshot
 * tests for the device-side evidence convention.
 */
@RunWith(AndroidJUnit4::class)
class DeckListHairlinesParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun deck(slug: String, owner: String, name: String = slug) =
        Deck(slug, name, owner, "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun twoOwners() = DecksState().loaded(
        listOf(
            deck("a", "kayla", "Bello"), deck("b", "kayla", "Chulane"),
            deck("c", "matt", "Alela"), deck("d", "matt", "Dihada"),
        ),
    )

    private fun card(name: String, type: String?, role: String? = null, owned: Int = 1) =
        DeckCard(
            name, qty = 1, role = role, owned = owned,
            nameNorm = name.lowercase(), typeLine = type, scryfallId = "abcdef12-3456",
        )

    /** The web suite's own fixture: two creatures land in one "Creatures" section. */
    private fun opened() = DecksState()
        .loaded(listOf(deck("a", "matt", "Alela")))
        .opened(
            "a",
            listOf(
                card("Alela, Artful Provocateur", "Legendary Creature — Faerie", "commander"),
                card("Zulaport Cutthroat", "Creature — Human Rogue"),
                card("Birds of Paradise", "Creature — Bird"),
            ),
        )

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { body() } } }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun exists(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bottomOf(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().bottom.value
    private fun topOf(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top.value
    private fun topOfTag(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
    private fun bottomOfTag(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom.value

    // ----------------------------------------------------- the owner heading

    @Test
    fun everyOwnerHeadingHasARuleUnderIt() {
        content { DecksScreen(twoOwners(), {}, {}) }
        assertTrue(exists("group-rule-Kayla"), "no rule under Kayla's heading")
        assertTrue(exists("group-rule-Matt"), "no rule under Matt's heading")
    }

    @Test
    fun theRuleSitsBetweenTheHeadingAndItsOwnDecksNotAboveIt() {
        content { DecksScreen(twoOwners(), {}, {}) }
        val headBottom = bottomOf("Kayla")
        val ruleTop = topOfTag("group-rule-Kayla")
        val tileTop = topOf("Bello")
        assertTrue(ruleTop >= headBottom - 1f, "the rule sits above Kayla's own name")
        assertTrue(tileTop >= bottomOfTag("group-rule-Kayla") - 1f, "the rule sits below Kayla's own tiles")
    }

    // ------------------------------------------------ the type-group heading

    @Test
    fun theTypeGroupHeadingHasAShadedBandWithARuleUnderIt() {
        content { DecksScreen(opened(), {}, {}) }
        assertTrue(exists("group-rule-Commander"), "no rule under the Commander section")
        assertTrue(exists("group-rule-Creatures"), "no rule under the Creatures section")
    }

    @Test
    fun theBandedHeadingIndentsItsTextWhereThePlainOwnerHeadingDoesNot() {
        // The band is `.panel-head`'s own horizontal padding; a plain
        // `.owner-head` carries none of it. Both headings sit in the
        // same screen padding, so if the band's padding is really
        // there the banded title starts further right than the plain
        // one's — a left edge is a robust, density-independent tell,
        // unlike a height that a different font size can also move.
        // Both mount in the same composition — `setContent` runs once
        // per test.
        content {
            androidx.compose.foundation.layout.Column {
                DecksScreen(twoOwners(), {}, {})
                DecksScreen(opened(), {}, {})
            }
        }
        val ownerHeadingLeft = rule.onNodeWithText("Kayla").getUnclippedBoundsInRoot().left.value
        val bandedHeadingLeft = rule.onNodeWithText("COMMANDER").getUnclippedBoundsInRoot().left.value

        assertTrue(
            bandedHeadingLeft > ownerHeadingLeft + 4f,
            "the banded section heading starts at ${bandedHeadingLeft}dp, barely past the plain " +
                "owner heading at ${ownerHeadingLeft}dp — there is no shaded band's padding to speak of",
        )
    }

    // ---------------------------------------------------- the card row rule

    @Test
    fun thereIsNoDividerBeforeTheFirstCardRowInASection() {
        content { DecksScreen(opened(), {}, {}) }
        assertFalse(
            exists("deck-line-rule-Creatures-0"),
            "a rule was drawn above the first card in its own section",
        )
    }

    @Test
    fun aDividerSitsBetweenTwoCardRowsInTheSameSection() {
        content { DecksScreen(opened(), {}, {}) }
        // Alphabetical inside the section: Birds of Paradise, then
        // Zulaport Cutthroat.
        assertTrue(exists("deck-line-rule-Creatures-1"), "no rule between the two creatures")
        val ruleTop = topOfTag("deck-line-rule-Creatures-1")
        assertTrue(
            ruleTop >= bottomOf("Birds of Paradise") - 1f,
            "the divider sits above the row it is supposed to follow",
        )
        assertTrue(
            topOf("Zulaport Cutthroat") >= bottomOfTag("deck-line-rule-Creatures-1") - 1f,
            "the divider sits below the row it is supposed to come before",
        )
    }
}
