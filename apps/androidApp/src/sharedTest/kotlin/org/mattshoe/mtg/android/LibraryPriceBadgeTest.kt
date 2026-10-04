package org.mattshoe.mtg.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Library
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The Library price badge, measured against the art it hangs off.
 *
 * Matt asked for the price centred along the bottom of the card once
 * already and only the web got it — `.price-badge` there is
 * `left: 50%; transform: translateX(-50%)`. Android drew it in the
 * bottom-left corner, where the set symbol and rarity dot already
 * live. A node's reported position is the fact; a screenshot read by
 * eye is not, so this measures the badge's and the art's bounds and
 * asserts their centres line up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryPriceBadgeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(name: String, price: Double) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 1, printings = 1, free = 1, price = price, value = price,
    )

    @Test
    fun thePriceBadgeIsCentredUnderTheArtNotInACorner() {
        val row = card("Sol Ring", 2.5)
        rule.setContent {
            MtgTheme {
                LibraryScreen(
                    state = Library().loaded(listOf(row), 1),
                    onState = {},
                    onSearch = {},
                    onOpen = {},
                    facets = Facets(),
                )
            }
        }
        rule.waitForIdle()
        // The grid is lazy. A row not scrolled to is not composed at
        // all, and measuring it then would measure nothing.
        rule.onNodeWithTag("library").performScrollToKey("matt:sol ring")
        rule.waitForIdle()

        val art = rule.onNodeWithContentDescription("Sol Ring", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val badge = rule.onNodeWithText("$2.50", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        val artCenter = (art.left + art.right) / 2f
        val badgeCenter = (badge.left + badge.right) / 2f
        assertTrue(
            abs(artCenter - badgeCenter) < 2f,
            "the price badge is not centred under the art: art centre $artCenter, badge centre $badgeCenter",
        )
    }
}
