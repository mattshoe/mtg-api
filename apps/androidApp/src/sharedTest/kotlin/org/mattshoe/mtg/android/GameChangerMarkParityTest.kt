package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game changer is marked on its deck row and in the carousel.
 *
 * Matt: "i want a clear indicator in carousels and deck lists for any
 * game changer cards". The website's `GameChangerMarkTest` makes the
 * same claims. The real `AppShell`, on a phone and on two tablets.
 */
@RunWith(AndroidJUnit4::class)
class GameChangerMarkParityTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun card(name: String, type: String) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 1,
        nameNorm = name.lowercase(),
        typeLine = type,
        scryfallId = "abcdef12-3456",
    )

    private val start = AppState(admin = Admin().signIn(Account("matt"), "t"))
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UB", 3, null)))
                    .opened("alela", listOf(card("Cultivate", "Sorcery"), card("Rhystic Study", "Enchantment"))),
            )
        }

    private fun shell(size: DpSize?) {
        rule.setContent {
            val body = @androidx.compose.runtime.Composable {
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
                        )
                    }
                }
            }
            if (size == null) body() else Tablet.Sized(size) { body() }
        }
        rule.waitForIdle()
    }

    private fun marksIn(tag: String) = rule.onAllNodes(hasTestTag(MARK_TAG) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun bounds(n: SemanticsNodeInteraction): Rect = n.getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun named(name: String) = rule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("deck-detail")))

    private fun theRowIsMarked() {
        named("Rhystic Study").performScrollTo()
        rule.waitForIdle()
        val marks = marksIn("deck-detail").fetchSemanticsNodes()
        assertEquals(1, marks.size, "the deck list has ${marks.size} game changer marks for one game changer")
        val mark = bounds(marksIn("deck-detail")[0])
        val label = marks.single().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        assertEquals(listOf("Game Changer"), label, "a screen reader is not told the mark means a game changer")
        assertTrue(mark.width > 0f && mark.height > 0f, "the mark has no size: $mark")
        val rhystic = bounds(named("Rhystic Study"))
        val cultivate = bounds(named("Cultivate"))
        assertTrue(
            abs(mark.center.y - rhystic.center.y) < abs(mark.center.y - cultivate.center.y),
            "the mark (y ${mark.center.y}) is on Cultivate's row (y ${cultivate.center.y}), not Rhystic Study's (y ${rhystic.center.y})",
        )
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(mark.right <= screen.right.value + 0.5f, "the mark runs off the right of the screen: $mark")
    }

    private fun theCarouselIsMarkedForAGameChangerOnly() {
        named("Cultivate").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("Cultivate", held.value.peeked?.title, "the carousel did not open on Cultivate")
        assertEquals(0, marksIn("carousel-sheet").fetchSemanticsNodes().size, "Cultivate is marked as a game changer")

        held.value = held.value.peekAt(held.value.peekRun.indexOfFirst { it.title == "Rhystic Study" })
        rule.waitForIdle()
        val marks = marksIn("carousel-sheet")
        assertEquals(1, marks.fetchSemanticsNodes().size, "Rhystic Study's carousel sheet has no game changer mark")
        marks[0].assertTextEqualsGameChanger()
        val mark = bounds(marks[0])
        val sheet = bounds(rule.onNode(hasTestTag("carousel-sheet")))
        assertTrue(mark.width > 0f && sheet.contains(mark.center), "the mark is not on the sheet: $mark in $sheet")
    }

    private fun SemanticsNodeInteraction.assertTextEqualsGameChanger() {
        val text = fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        assertEquals("Game Changer", text, "the carousel's mark does not say Game Changer")
    }

    @Test
    fun aGameChangersRowIsMarkedOnAPhone() { shell(null); theRowIsMarked() }

    @Test
    fun theCarouselMarksAGameChangerOnAPhone() { shell(null); theCarouselIsMarkedForAGameChangerOnly() }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aGameChangersRowIsMarkedOnAFoldingPhoneOpenedOut() { shell(Tablet.UNFOLDED); theRowIsMarked() }

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theCarouselMarksAGameChangerOnAFoldingPhoneOpenedOut() { shell(Tablet.UNFOLDED); theCarouselIsMarkedForAGameChangerOnly() }

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aGameChangersRowIsMarkedOnATabletHeldSideways() { shell(Tablet.LANDSCAPE); theRowIsMarked() }

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theCarouselMarksAGameChangerOnATabletHeldSideways() { shell(Tablet.LANDSCAPE); theCarouselIsMarkedForAGameChangerOnly() }

    private companion object {
        const val MARK_TAG = "game-changer-mark"
    }
}
