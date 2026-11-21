package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlin.test.assertTrue

/**
 * Somebody else's deck, opened from the link they shared, on a phone
 * and on a tablet.
 *
 * Matt shared his Liliana deck with Kayla. Opened on her phone it is
 * his deck on her screen while her own collection is the one in view,
 * and the edit actions were decided by the collection in view — so she
 * was offered Edit list, Rename and Disassemble on a deck the server
 * will never let her change. Through the real `AppShell`. Her own deck,
 * opened the same way, still has them, so the test cannot pass by the
 * actions having gone missing everywhere. The website's
 * `aLinkToSomebodyElsesDeckOpensThatDeck` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class SharedDeckScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val kayla = Admin().signIn(Account("k4yy0003"), "t")
    private val kaylas = Deck("k4yd3ck0", "Valgavoth", "k4yy0003", "Valgavoth", "BR", 3, null, "Kayla M")
    private val matts = Deck("x5aqbxtw", "Faultless Victory", "e7de0cb1", "Liliana the Faultless", "B", 3, null, "Matt")
    private val ring = DeckCard(name = "Sol Ring", qty = 1, role = null, owned = 1, nameNorm = "sol ring", typeLine = "Artifact")

    private fun on(deck: Deck): AppState = AppState(admin = kayla)
        .navigate(Route(View.DECKS, deck.key))
        .let { it.copy(decks = it.decks.loaded(listOf(kaylas)).opened(deck.key, listOf(ring), deck)) }

    private fun shell(start: AppState, size: DpSize?) {
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        AppShell(
                            state = start,
                            onState = {},
                            onSearch = {},
                            onOpenDeck = {},
                            onPreviewEntry = {},
                            onApplyEntry = {},
                        )
                    }
                }
            }
            if (size == null) shell() else Tablet.Sized(size) { shell() }
        }
        rule.waitForIdle()
    }

    private fun offered(): List<String> = listOf("Edit list", "Rename", "Disassemble").filter { label ->
        rule.onAllNodes(hasContentDescription(label) or hasText(label)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun mattsDeckOffersKaylaNoEdits(size: DpSize?) {
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        shell(on(matts), size)
        assertTrue(
            rule.onAllNodes(hasText("Sol Ring", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "$on Matt's shared deck did not open for Kayla",
        )
        assertTrue(
            offered().isEmpty(),
            "$on Kayla is offered ${offered()} on Matt's deck, which the server will refuse",
        )
    }

    private fun herOwnDeckStillOffersThem(size: DpSize?) {
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        shell(on(kaylas), size)
        assertTrue(
            offered().containsAll(listOf("Edit list", "Rename", "Disassemble")),
            "$on Kayla's own deck offers only ${offered()}, so the test proves nothing about Matt's",
        )
    }

    @Test
    fun mattsSharedDeckOffersKaylaNoEditsOnAPhone() = mattsDeckOffersKaylaNoEdits(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun mattsSharedDeckOffersKaylaNoEditsOnAFoldingPhoneOpenedOut() = mattsDeckOffersKaylaNoEdits(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun mattsSharedDeckOffersKaylaNoEditsOnATabletHeldSideways() = mattsDeckOffersKaylaNoEdits(Tablet.LANDSCAPE)

    @Test
    fun herOwnDeckStillOffersTheEditsOnAPhone() = herOwnDeckStillOffersThem(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun herOwnDeckStillOffersTheEditsOnAFoldingPhoneOpenedOut() = herOwnDeckStillOffersThem(Tablet.UNFOLDED)
}
