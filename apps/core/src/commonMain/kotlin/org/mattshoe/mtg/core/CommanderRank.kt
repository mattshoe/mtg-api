package org.mattshoe.mtg.core

/**
 * A commander's place among commanders, as EDHREC's commander page
 * says it. Null rank: EDHREC has no page for it.
 *
 * Not the same number as `edhrec_rank`, which every card carries and
 * which is Scryfall's copy of the card's place among every card there
 * is. Teysa Karlov is 1,330th of all cards and 58th of commanders, and
 * the app used to say only the first, as if it were the second.
 */
data class CommanderRank(val rank: Long?, val decks: Long?)

/** What EDHREC has said, by card, and what is still on its way. */
data class CommanderRanks(
    val known: Map<String, CommanderRank> = emptyMap(),
    val asking: Set<String> = emptySet(),
) {
    operator fun get(name: String): CommanderRank? = known[key(name)]

    /** Neither known nor on its way. A card EDHREC had no page for is known. */
    fun needs(name: String): Boolean = key(name).let { it !in known && it !in asking }

    fun ask(name: String) = copy(asking = asking + key(name))

    fun found(name: String, rank: CommanderRank) =
        copy(known = known + (key(name) to rank), asking = asking - key(name))

    companion object {
        /** A card is known by its front face, as EDHREC addresses it. */
        fun key(name: String): String = name.substringBefore(" // ").trim().lowercase()

        /**
         * Whether a card can lead a deck, and so has a commander page to
         * ask for: a legendary creature, or anything that says it can be
         * your commander.
         */
        fun leads(typeLine: String?, oracleText: String? = null): Boolean {
            val type = typeLine.orEmpty()
            return ("Legendary" in type && "Creature" in type) ||
                oracleText.orEmpty().contains("can be your commander", ignoreCase = true)
        }
    }
}

/** The card page, with the commander rank already known put on it. */
fun AppState.showing(card: CardDetail): AppState = copy(card = card.ranked(commanderRanks))

private fun CardDetail.ranked(ranks: CommanderRanks): CardDetail =
    copy(facts = facts.copy(commander = ranks[face?.name ?: name]?.rank))

/** The card page's card, if it can lead a deck. */
private val AppState.cardCommander: String?
    get() {
        if (view != View.CARD) return null
        val face = card?.face ?: return null
        return face.name.takeIf { CommanderRanks.leads(face.typeLine, face.oracleText) }
    }

/** The carousel's card, if it can lead a deck. A deck's own commander always can. */
private val AppState.peekedCommander: String?
    get() {
        val p = peeked ?: return null
        val leads = p.inDeck?.isCommander == true ||
            CommanderRanks.leads(p.typeLine, p.inDeck?.oracleText)
        return p.title.takeIf { leads }
    }

/**
 * The next commander rank this screen needs and nobody has asked for.
 *
 * Both shells read this after every write and ask the Worker for it,
 * so neither decides for itself which cards have a commander page.
 */
val AppState.commanderRankWanted: String?
    get() = listOfNotNull(cardCommander, peekedCommander).firstOrNull { commanderRanks.needs(it) }

fun AppState.askingCommanderRank(name: String): AppState =
    copy(commanderRanks = commanderRanks.ask(name))

/** EDHREC's answer, kept, and put on the card page if that is the card. */
fun AppState.commanderRankFound(name: String, rank: CommanderRank): AppState {
    val ranks = commanderRanks.found(name, rank)
    return copy(commanderRanks = ranks, card = card?.ranked(ranks))
}
