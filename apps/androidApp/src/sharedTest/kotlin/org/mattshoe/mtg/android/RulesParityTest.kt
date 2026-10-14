package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Rules
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's rules page, through the real shell and its bottom bar.
 *
 * Matt: "the page it navigates to should have a search bar at the top
 * ... searching trample should show me all rules that mention trample
 * ... show each rule result in a nice stylish card view so it's clear
 * which rules are which ... i want each rule to be collapsible, but
 * expanded my default". Sibling of the web's `RulesPageTest`;
 * `RulesLoadTest` drives the fetch through the real `MainActivity`.
 */
@RunWith(AndroidJUnit4::class)
class RulesParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private var retries = 0

    private fun shell(start: AppState = AppState()) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onLoadRules = { retries++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        }

    private fun count(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun onRulesWith(rules: Rules) =
        AppState(rules = rules).navigate(Route.parse("#/guide/rules"))

    private fun search(words: String) {
        rule.onNodeWithTag("rules-search").performTextInput(words)
        rule.waitForIdle()
    }

    @Test
    fun theGuideHasARulesTileThatOpensTheRulesPage() {
        shell()
        rule.onNodeWithContentDescription("Guide").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("guide-tile-rules").assertExists("the Guide has no rules tile")
        rule.onNodeWithTag("guide-tile-rules").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("#/guide/rules", held.value.route.toHash())
        rule.onNodeWithTag("rules-search").assertExists("the rules page has no search bar")
    }

    @Test
    fun searchingTrampleShowsACardForEveryRuleThatMentionsIt() {
        shell(onRulesWith(Rules().loaded(TEXT)))
        search("trample")
        assertEquals(listOf("702.19", "702.19a", "702.19b", "702.19c"), texts("rule-number"))
        val bar = rule.onNodeWithTag("rules-search").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag("rule-702.19.", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= first.top, "the search bar is not above the results: ${bar.bottom} vs ${first.top}")
    }

    @Test
    fun everyRuleStartsExpandedAndATapOnItsNumberFoldsItAway() {
        shell(onRulesWith(Rules().loaded(TEXT)))
        search("trample")
        assertEquals(4, count("rule-body"), "a rule started collapsed")
        assertTrue(texts("rule-example").any { it.startsWith("A 6/6 green creature") }, "702.19b's examples are not shown")

        rule.onAllNodes(hasTestTag("rule-head"), useUnmergedTree = true)[2].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(3, count("rule-body"), "tapping 702.19b did not fold it")
        assertEquals(4, texts("rule-number").size, "a folded rule left the list rather than folding")
        assertTrue(texts("rule-example").none { it.startsWith("A 6/6 green creature") }, "702.19b's examples are still shown")

        rule.onAllNodes(hasTestTag("rule-head"), useUnmergedTree = true)[2].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(4, count("rule-body"), "tapping again did not open it")
    }

    @Test
    fun aCardIsASurfaceOfItsOwnWithTheNumberAsItsHeading() {
        shell(onRulesWith(Rules().loaded(TEXT)))
        search("trample")
        val cards = rule.onAllNodes(hasTestTag("rule-702.19a"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, cards.size)
        val a = rule.onNodeWithTag("rule-702.19a", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("rule-702.19b", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(b.top > a.bottom, "the cards touch, so it is one block of text rather than a card per rule")
        val number = rule.onAllNodes(hasTestTag("rule-number"), useUnmergedTree = true)[1].fetchSemanticsNode()
        val style = mutableListOf<TextLayoutResult>().also {
            number.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it)
        }.single().layoutInput.style
        val body = rule.onAllNodes(hasTestTag("rule-text"), useUnmergedTree = true)[1].fetchSemanticsNode()
        val bodyStyle: TextStyle = mutableListOf<TextLayoutResult>().also {
            body.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it)
        }.single().layoutInput.style
        assertTrue(
            (style.fontWeight?.weight ?: 400) > (bodyStyle.fontWeight?.weight ?: 400),
            "the number is no heavier than the text, so nothing marks where a rule begins",
        )
    }

    @Test
    fun aLoadThatFailedLeavesNoSpinnerSaysWhyAndOffersTryAgain() {
        shell(onRulesWith(Rules().loading().failed("Wizards answered 404 for the rules text")))
        assertEquals(0, count("rules-busy"), "the spinner is still up after the load failed")
        assertEquals(listOf("Could not load the rules: Wizards answered 404 for the rules text"), texts("rules-err"))
        rule.onNodeWithTag("rules-retry").performClick()
        rule.waitForIdle()
        assertEquals(1, retries, "Try again did not ask for the rules again")
    }

    @Test
    fun aSearchWithNoMatchSaysSo() {
        shell(onRulesWith(Rules().loaded(TEXT)))
        search("zzzz")
        assertEquals(0, count("rule-number"))
        assertEquals(listOf("No rule mentions \"zzzz\"."), texts("rules-none"))
    }

    private companion object {
        val TEXT = """
            |Magic: The Gathering Comprehensive Rules
            |
            |These rules are effective as of September 25, 2026.
            |
            |Credits
            |
            |7. Additional Rules
            |
            |702. Keyword Abilities
            |
            |702.19. Trample
            |
            |702.19a Trample is a static ability that modifies the rules for assigning an attacking creature’s combat damage.
            |
            |702.19b The controller of an attacking creature with trample first assigns damage to the creature(s) blocking it.
            |Example: A 2/2 creature that can block an additional creature blocks two attackers: a 1/1 with no abilities and a 3/3 with trample.
            |Example: A 6/6 green creature with trample is blocked by a 2/2 creature with protection from green.
            |
            |702.19c Trample over planeswalkers is a variant of trample that modifies the rules for assigning combat damage to planeswalkers.
            |
            |702.20. Vigilance
            |
            |702.20a Vigilance is a static ability that modifies the rules for the declare attackers step.
            |
            |Glossary
        """.trimMargin()
    }
}
