package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rank, and the way out to EDHREC.
 *
 * Every slug below was checked against the live site on 2026-10-07
 * (`json.edhrec.com/pages/cards/<slug>.json`, the data behind
 * `edhrec.com/cards/<slug>`) before it was written down here. What
 * that showed: a split or aftermath card keeps both halves
 * (`commit-memory`, `cut-ribbons`), every other two-named card is its
 * front face (`aetherblade-agent`, `brazen-borrower`, `hagra-mauling`),
 * accents fold to plain letters (`jotun-grunt`, `seance`,
 * `aether-vial`) and apostrophes vanish rather than becoming hyphens
 * (`lim-duls-vault`, `nymris-oonas-trickster`).
 */
class EdhrecTest {

    @Test
    fun aPlainName() = assertEquals("sol-ring", Edhrec.slug("Sol Ring", "normal"))

    @Test
    fun anApostropheAndAHyphenAndAnAccent() =
        assertEquals("lim-duls-vault", Edhrec.slug("Lim-Dûl's Vault", "normal"))

    @Test
    fun accentsFoldToPlainLetters() {
        assertEquals("jotun-grunt", Edhrec.slug("Jötun Grunt", "normal"))
        assertEquals("seance", Edhrec.slug("Séance", "normal"))
        assertEquals("aether-vial", Edhrec.slug("Æther Vial", "normal"))
    }

    @Test
    fun aCommaAndAnApostrophe() =
        assertEquals("nymris-oonas-trickster", Edhrec.slug("Nymris, Oona's Trickster", "normal"))

    @Test
    fun aDoubleFacedCardIsItsFrontFace() {
        assertEquals("aetherblade-agent", Edhrec.slug("Aetherblade Agent // Gitaxian Mindstinger", "transform"))
        assertEquals("hagra-mauling", Edhrec.slug("Hagra Mauling // Hagra Broodpit", "modal_dfc"))
        assertEquals("brazen-borrower", Edhrec.slug("Brazen Borrower // Petty Theft", "adventure"))
    }

    @Test
    fun aSplitCardKeepsBothHalves() {
        assertEquals("commit-memory", Edhrec.slug("Commit // Memory", "split"))
        assertEquals("cut-ribbons", Edhrec.slug("Cut // Ribbons", "aftermath"))
    }

    @Test
    fun theLinkIsTheCardsPage() =
        assertEquals("https://edhrec.com/cards/aetherblade-agent", Edhrec.url("Aetherblade Agent // Gitaxian Mindstinger", "transform"))

    @Test
    fun theRankReadsWithItsThousands() {
        assertEquals("EDHREC #3", Edhrec.rankText(3))
        assertEquals("EDHREC #20,135", Edhrec.rankText(20135))
        assertEquals("EDHREC #1,234,567", Edhrec.rankText(1234567))
    }

    @Test
    fun anUnrankedCardSaysNothing() = assertNull(Edhrec.rankText(null))

    @Test
    fun theCardPageLinksTheCardItShows() {
        val (cols, rows) = kotlinx.serialization.json.Json.parseToJsonElement(CardFactsTest.AETHERBLADE_AGENT)
            .let { it as kotlinx.serialization.json.JsonObject }
            .let { it.keys.toList() to listOf(kotlinx.serialization.json.JsonArray(it.values.toList())) }
        val card = CardDetail(
            name = "Aetherblade Agent // Gitaxian Mindstinger",
            nameNorm = "aetherblade agent // gitaxian mindstinger",
            facts = CardFacts.decode(cols, rows),
        )
        assertEquals("https://edhrec.com/cards/aetherblade-agent", card.edhrecUrl)
    }

    @Test
    fun aCardNotYetLoadedHasNoLink() = assertNull(CardDetail(name = "", nameNorm = "").edhrecUrl)
}
