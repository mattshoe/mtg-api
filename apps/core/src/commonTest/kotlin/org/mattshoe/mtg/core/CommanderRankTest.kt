package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A commander's rank among commanders, beside its rank among all cards.
 *
 * Matt: "the edhrec ranks all look like they're wrong dude wtf. I'm
 * literally looking at teysa karlov here and she's 53 ... but you have
 * her WAY THE FUCK OFF IN THE THOUSANDS".
 *
 * Both numbers are EDHREC's. The one every card carries is Scryfall's
 * `edhrec_rank`, Teysa's place among every card there is: 1,330th.
 * EDHREC's commander page says her place among commanders, which was 53
 * when Matt looked and 58 when this was written. The app only had the
 * first and said it as if it were the second.
 */
class CommanderRankTest {

    private val teysa = Face(
        name = "Teysa Karlov",
        typeLine = "Legendary Creature — Human Advisor",
        oracleText = "If a creature dying causes a triggered ability ...",
    )

    private fun teysaPage(rank: Long? = 1330) = CardDetail(
        name = "Teysa Karlov",
        nameNorm = "teysa karlov",
        faces = listOf(teysa),
        facts = CardFacts(mapOf("edhrec_rank" to (rank?.toString() ?: ""))),
    )

    private fun onTeysasPage() = AppState()
        .openCard(CardRef("teysa karlov"), "Teysa Karlov")
        .showing(teysaPage())

    private fun said(s: AppState, label: String) =
        s.card?.facts?.lines?.firstOrNull { it.label == label }?.value

    @Test
    fun aLegendaryCreaturesPageAsksForItsCommanderRank() =
        assertEquals("Teysa Karlov", onTeysasPage().commanderRankWanted, "the page never asked EDHREC")

    @Test
    fun itAsksOnceNotOnEveryWrite() {
        val asking = onTeysasPage().askingCommanderRank("Teysa Karlov")
        assertNull(asking.commanderRankWanted, "a rank already on its way was asked for again")
        val found = asking.commanderRankFound("Teysa Karlov", CommanderRank(58, 21728))
        assertNull(found.commanderRankWanted, "a rank already known was asked for again")
    }

    @Test
    fun aCardThatCannotLeadADeckIsNeverAsked() {
        val bolt = AppState().openCard(CardRef("lightning bolt"), "Lightning Bolt").showing(
            CardDetail(name = "Lightning Bolt", nameNorm = "lightning bolt", faces = listOf(Face("Lightning Bolt", typeLine = "Instant"))),
        )
        assertNull(bolt.commanderRankWanted, "an instant has no commander page to ask for")
    }

    @Test
    fun aPlaneswalkerThatSaysItCanLeadIsAsked() {
        val walker = AppState().openCard(CardRef("aminatou"), "Aminatou").showing(
            CardDetail(
                name = "Aminatou, the Fateshifter", nameNorm = "aminatou",
                faces = listOf(Face("Aminatou, the Fateshifter", typeLine = "Legendary Planeswalker — Aminatou",
                    oracleText = "Aminatou, the Fateshifter can be your commander.")),
            ),
        )
        assertEquals("Aminatou, the Fateshifter", walker.commanderRankWanted)
    }

    @Test
    fun thePageSaysHerCommanderRankAsEdhrecDoes() {
        val s = onTeysasPage().commanderRankFound("Teysa Karlov", CommanderRank(58, 21728))
        assertEquals("#58 on EDHREC", said(s, "Commander rank"), "the page never said the commander rank")
        assertEquals("Commander rank", s.card?.facts?.lines?.first()?.label, "the commander rank is not the first thing said")
    }

    @Test
    fun theOtherRankSaysWhatItIsARankOf() =
        assertEquals("#1,330 of all cards", said(onTeysasPage(), "EDHREC rank"), "the all-cards rank reads like a commander rank")

    @Test
    fun aRankFoundBeforeThePageLoadedIsStillOnIt() {
        // The carousel asked first; then the card page opened.
        val s = AppState().commanderRankFound("Teysa Karlov", CommanderRank(58, 21728))
            .openCard(CardRef("teysa karlov"), "Teysa Karlov")
            .showing(teysaPage())
        assertEquals("#58 on EDHREC", said(s, "Commander rank"))
    }

    @Test
    fun aCommanderEdhrecHasNoPageForSaysNothingRatherThanALie() {
        val s = onTeysasPage().commanderRankFound("Teysa Karlov", CommanderRank(null, null))
        assertNull(said(s, "Commander rank"))
        assertNull(s.commanderRankWanted, "a card with no page was asked for again")
    }

    private fun overADeck(vararg cards: DeckCard) = AppState()
        .navigate(Route(View.DECKS, "orzhov"))
        .let {
            it.copy(
                decks = it.decks.loaded(listOf(Deck("orzhov", "Teysa", "matt", null, "WB", 2, null)))
                    .opened("orzhov", cards.toList()),
            )
        }

    private val teysaInDeck = DeckCard(
        name = "Teysa Karlov", qty = 1, role = "commander", owned = 1, nameNorm = "teysa karlov",
        typeLine = "Legendary Creature — Human Advisor", edhrecRank = 1330,
    )

    private fun tags(s: AppState) = assertNotNull(s.peeked, "nothing is peeked").tags.map { it.text }

    @Test
    fun theCarouselAsksForTheCommanderItIsShowing() =
        assertEquals("Teysa Karlov", overADeck(teysaInDeck).peekCard(teysaInDeck).commanderRankWanted)

    @Test
    fun theCarouselSaysBothRanksAndWhichIsWhich() {
        val s = overADeck(teysaInDeck).peekCard(teysaInDeck)
            .commanderRankFound("Teysa Karlov", CommanderRank(58, 21728))
        assertTrue("Commander #58 on EDHREC" in tags(s), "the carousel never said the commander rank: ${tags(s)}")
        assertTrue("EDHREC #1,330 of all cards" in tags(s), "the all-cards rank reads like a commander rank: ${tags(s)}")
    }

    @Test
    fun aDoubleFacedCommanderIsKnownByItsFront() {
        val s = AppState().commanderRankFound("Esika, God of the Tree // The Prismatic Bridge", CommanderRank(400, 5000))
        assertEquals(CommanderRank(400, 5000), s.commanderRanks["Esika, God of the Tree"])
    }
}
