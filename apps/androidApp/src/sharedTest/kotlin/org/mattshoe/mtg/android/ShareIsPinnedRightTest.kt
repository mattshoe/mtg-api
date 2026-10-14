package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "the share button in all pages at the top must always be
 * pinned to the right side, and on the deck details it should be
 * overlayed on the banner image".
 *
 * Through the real `AppShell`. Sibling of the web's
 * `ShareIsPinnedRightTest`.
 */
@RunWith(AndroidJUnit4::class)
class ShareIsPinnedRightTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
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

    private fun bounds(tag: String) = rule.onNode(hasTestTag(tag)).getUnclippedBoundsInRoot()

    private fun share(label: String) = rule.onNode(hasContentDescription(label))

    private val admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")

    private val withCommander = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    /** A 60-card list: no commander, so no banner to sit on. */
    private val sixty = Deck("alela", "Sixty", "matt", null, "UW", null, null)

    private fun onThe(deck: Deck, unlocked: Boolean) = AppState(
        route = Route(View.DECKS, "alela"),
        admin = if (unlocked) admin else Admin(),
        decks = DecksState().loaded(listOf(deck)).opened(
            "alela",
            listOf(DeckCard("Sol Ring", 1, null, 1, nameNorm = "sol ring", typeLine = "Artifact")),
        ),
    )

    // --------------------------------------------------- the deck banner

    private fun shareSitsOnTheBanner(unlocked: Boolean) {
        shell(onThe(withCommander, unlocked))
        assertTrue(
            rule.onAllNodes(hasTestTag("deck-hero")).fetchSemanticsNodes().isNotEmpty(),
            "the deck has no banner",
        )
        val hero = bounds("deck-hero")
        val s = share("Share this deck").getUnclippedBoundsInRoot()
        assertTrue(
            s.top >= hero.top && s.bottom <= hero.bottom && s.left >= hero.left && s.right <= hero.right,
            "the share is not on the banner: the share is ${s.left},${s.top} to ${s.right},${s.bottom}, " +
                "the banner ${hero.left},${hero.top} to ${hero.right},${hero.bottom}",
        )
        assertTrue(
            (hero.right - s.right).value <= 16f,
            "the share is not pinned to the banner's right: ${hero.right - s.right} short of it",
        )
        assertTrue(
            (s.top - hero.top).value <= 16f,
            "the share is not at the top of the banner: ${s.top - hero.top} down it",
        )
    }

    @Test
    fun anOpenDecksShareSitsOnTheBannerInItsTopRightCorner() = shareSitsOnTheBanner(unlocked = false)

    @Test
    fun anUnlockedDecksShareSitsOnTheBannerToo() = shareSitsOnTheBanner(unlocked = true)

    @Test
    fun theShareOnTheBannerStillOpensItsMenu() {
        shell(onThe(withCommander, unlocked = true))
        share("Share this deck").performClick()
        rule.waitForIdle()
        assertEquals(
            2,
            rule.onAllNodes(hasText("Download")).fetchSemanticsNodes().size,
            "pressing the share on the banner did not open the four ways to share",
        )
    }

    // ------------------------------------------------ a deck with no banner

    @Test
    fun aDeckWithNoBannerKeepsItsShareAtTheRightOfTheTop() {
        shell(onThe(sixty, unlocked = true))
        val page = bounds("deck-detail")
        val s = share("Share this deck").getUnclippedBoundsInRoot()
        assertTrue(
            (page.right - s.right).value <= 24f,
            "the share is not pinned right: ${page.right - s.right} short of the page's right edge",
        )
        assertTrue((s.top - page.top).value <= 24f, "the share is not at the top of the page")
    }

    // ---------------------------------------------------------- a card

    private fun cardShareIsPinnedRight(name: String) {
        shell(
            onThe(withCommander, unlocked = false)
                .openCard(CardRef(nameNorm = name.lowercase()), name)
                .copy(card = CardDetail(name = name, nameNorm = name.lowercase())),
        )
        val page = bounds("card-sheet")
        val s = share("Share this card").getUnclippedBoundsInRoot()
        assertTrue(
            (page.right - s.right).value <= 24f,
            "with '$name' the share is ${page.right - s.right} short of the right edge: " +
                "the share runs ${s.left} to ${s.right}, the page ${page.left} to ${page.right}",
        )
    }

    @Test
    fun aCardsShareIsPinnedToTheRight() = cardShareIsPinnedRight("Sol Ring")

    @Test
    fun aLongNamedCardsShareIsPinnedToTheRight() =
        cardShareIsPinnedRight("Asmoranomardicadaistinaculdacar, the Long-Named")
}
