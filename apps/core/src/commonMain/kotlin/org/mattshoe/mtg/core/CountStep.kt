package org.mattshoe.mtg.core

/**
 * "- 3 +" under a card.
 *
 * Matt: "i need a way to toggle the amount of a given card on the
 * carousel and on the card details [...] make sure the carousel on
 * the deck details toggles the number in the deck, not the number in
 * the collection (tho if increasing being the amount in the
 * collection, then you'd have to add it to the collection)".
 *
 * [count] is whichever of those two numbers this place is about, and
 * [unit] says which, so the number never has to be guessed at.
 */
data class Stepper(val count: Int, val unit: String = "", val busy: Boolean = false) {
    /** Nought is a real answer — out of the deck, or out of the collection — and below it is not. */
    val canTakeOne: Boolean get() = !busy && count > 0
    val canAddOne: Boolean get() = !busy && count < DeckTweak.MAX_QTY
}

/**
 * What pressing plus or minus sends.
 *
 * Two shapes, because there are two numbers. A deck's count goes
 * through `/decks/list` with the whole list one line different —
 * the same path as every other deck edit, which already takes a
 * copy out of bulk or buys one into the collection when the deck
 * wants more than is spare. The collection's count goes through
 * `/cards/add` or `/cards/remove`, one copy of one printing.
 */
sealed interface CountWrite {
    /** What the toast says once it has landed. */
    val summary: String

    data class DeckList(
        /** The deck's key. */
        val deck: String,
        val commander: String,
        val list: String,
        override val summary: String,
    ) : CountWrite

    data class Cards(
        val direction: Direction,
        /** Whose, as the collection's public key. The server decides whether you may. */
        val collection: String,
        /** One decklist line: "1 Sol Ring (M3C) 409 foil". */
        val line: String,
        override val summary: String,
    ) : CountWrite
}

/** "1 Sol Ring (M3C) 409 foil", the way `/cards/add` reads a printing. */
internal fun oneOf(name: String, setCode: String?, number: String?, finish: String?): String {
    val set = setCode?.trim()?.takeIf { it.isNotEmpty() } ?: return "1 $name"
    val parts = mutableListOf("1 $name (${set.uppercase()})")
    number?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    finish?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "nonfoil" }?.let { parts += it }
    return parts.joinToString(" ")
}

/** Whether a write is in flight. Both buttons go off until it lands. */
fun AppState.countingStarted(): AppState = copy(counting = true)
fun AppState.countingDone(): AppState = copy(counting = false)

// ------------------------------------------------------- the carousel

/** The open deck's commander, as the deck editors send it. */
private fun AppState.commanderLine(deck: Deck): String =
    decks.cards.filter { it.role == "commander" }
        .joinToString(" // ") { it.name }
        .ifEmpty { deck.commanderName.orEmpty() }

/** Whether the stepper counts this row of the open deck: yours, and not its commander. */
internal fun AppState.stepsDeckRow(row: DeckCard): Boolean =
    // A commander's count is not a number anybody steps.
    !row.isCommander && decks.open?.let { admin.account?.owns(it.owner) } == true

/** Whether the stepper counts this Library row: one in a collection you may change. */
internal fun AppState.stepsLibraryRow(row: CardRow): Boolean = admin.account?.owns(row.owner) == true

/** The deck row on show, if it is one you may change the count of. */
private fun AppState.deckRowOnShow(): Pair<Deck, DeckCard>? {
    if (peek.of != PeekOf.DECK) return null
    val row = peeked?.inDeck ?: return null
    val deck = decks.open ?: return null
    return (deck to row).takeIf { stepsDeckRow(row) }
}

/** The Library row on show, if it is in a collection you may change. */
private fun AppState.libraryRowOnShow(): CardRow? {
    if (peek.of != PeekOf.LIBRARY || !peek.open) return null
    val rows = library.rows
    val row = rows.getOrNull(peek.at.coerceAtMost(rows.lastIndex)) ?: return null
    return row.takeIf { stepsLibraryRow(it) }
}

/** "- 2 +" under the carousel: the deck's count over a deck, the collection's in the Library. */
val AppState.peekStepper: Stepper?
    get() {
        deckRowOnShow()?.let { (_, row) -> return Stepper(row.qty, "in deck", counting) }
        libraryRowOnShow()?.let { return Stepper(it.qty, "owned", counting) }
        return null
    }

/** What plus ([by] = 1) or minus ([by] = -1) under the carousel sends. Null when it sends nothing. */
fun AppState.peekStep(by: Int): CountWrite? {
    val stepper = peekStepper ?: return null
    if (by > 0 && !stepper.canAddOne || by < 0 && !stepper.canTakeOne || by == 0) return null

    deckRowOnShow()?.let { (deck, row) ->
        val commander = commanderLine(deck)
        val next = row.qty + by
        val tweak = DeckTweak.on(deck, commander, row, Tweak.QUANTITY).count(next)
        return CountWrite.DeckList(
            deck = deck.key,
            commander = commander,
            list = tweak.listAfter(decks.cards),
            summary = "${row.shown}: ${row.qty} → $next in ${deck.name}",
        )
    }

    val row = libraryRowOnShow() ?: return null
    return CountWrite.Cards(
        direction = if (by > 0) Direction.ADD else Direction.REMOVE,
        collection = row.owner,
        line = oneOf(row.name, row.setCode, row.collectorNumber, row.finish),
        summary = "${row.fullName}: ${row.qty} → ${row.qty + by} owned",
    )
}

/**
 * The deck changes the sheet under the carousel offers, in order.
 *
 * On the deck row rather than on `admin`: being admin in the Library
 * still leaves nothing to count, swap or remove, because there is no
 * deck.
 *
 * Swap alone where the stepper counts the card. Matt: "why is there
 * still s count button?!?! DON'T YOU THINK THAT'S A LITTLE FUCKING
 * REDUNDANT?! same with remove?!" Minus to nought is the remove.
 */
fun PeekCard.tweaks(admin: Boolean): List<Tweak> = when {
    inDeck == null || !admin -> emptyList()
    stepped -> listOf(Tweak.SWAP)
    else -> listOf(Tweak.QUANTITY, Tweak.SWAP, Tweak.REMOVE)
}

// ------------------------------------------------------- the card page

/** The collection the card page is being read in, if it is yours to change. */
private fun AppState.cardCollection(): String? {
    if (view != View.CARD) return null
    if (card == null || card.busy || card.error != null) return null
    return viewing.takeIf { canEdit }
}

/** "- 3 +" on the card page: how many the collection being read holds. */
val AppState.cardStepper: Stepper?
    get() {
        val who = cardCollection() ?: return null
        val held = card!!.printings.filter { it.owner == who }.sumOf { it.qty }
        return Stepper(held, "owned", counting)
    }

/**
 * What plus or minus on the card page sends.
 *
 * One copy of the printing this collection holds most of — the one a
 * person most likely means — and when it holds none, plus asks for
 * the card by name and lets the server pick the printing, the same
 * as typing the name into Add cards.
 */
fun AppState.cardStep(by: Int): CountWrite? {
    val stepper = cardStepper ?: return null
    if (by > 0 && !stepper.canAddOne || by < 0 && !stepper.canTakeOne || by == 0) return null
    val who = viewing
    val c = card!!
    val mine = c.printings.filter { it.owner == who && it.qty > 0 }.maxByOrNull { it.qty }
    val name = mine?.cardName?.takeIf { it.isNotBlank() } ?: c.name
    val line = if (mine == null) "1 $name" else oneOf(name, mine.setCode, mine.collectorNumber, mine.finish)
    return CountWrite.Cards(
        direction = if (by > 0) Direction.ADD else Direction.REMOVE,
        collection = who,
        line = line,
        summary = "${c.name}: ${stepper.count} → ${stepper.count + by} owned",
    )
}
