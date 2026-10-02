package org.mattshoe.mtg.core

/** What is being done to one card in a deck. */
enum class Tweak(val title: String, val verb: String) {
    ADD("Add a card", "Add"),
    REMOVE("Remove from the deck", "Remove"),
    SWAP("Swap this card out", "Swap"),
    QUANTITY("How many", "Set"),
}

/**
 * One change to a deck, made on the deck's own page.
 *
 * The server has no per-card endpoint and does not need one.
 * `/decks/list` takes the whole list, works out what was added,
 * removed and changed, and says what every copy would cost before
 * writing anything. So a swap is that call with one line different,
 * and a swap is checked, sourced and recorded by exactly the code
 * that checks a full rewrite — rather than by a second, thinner path
 * that is free to disagree with it.
 *
 * The flow is the same three beats as every other write here: say
 * what you want, see what it would do, then let it happen.
 */
data class DeckTweak(
    val slug: String = "",
    val deckName: String = "",
    /** Kept so the edit does not quietly drop the deck's commander. */
    val commander: String = "",
    /**
     * What is being done. Null while the sheet is still asking —
     * three marks on every row of a hundred-card list left no room
     * for the card's name, so the row has one button and the choice
     * is made inside.
     */
    val kind: Tweak? = null,
    /** The row being acted on. Null when adding. */
    val subject: DeckCard? = null,
    val term: String = "",
    val found: List<Found> = emptyList(),
    val searching: Boolean = false,
    /** The card coming in, for an add or a swap. */
    val pick: Found? = null,
    val qty: Int = 1,
    val plan: DeckPlan? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val errors: List<String> = emptyList(),
    val saved: Boolean = false,
) {
    /** A card has to be named before there is anything to plan. */
    val needsACard: Boolean get() = kind == Tweak.ADD || kind == Tweak.SWAP

    /** Still asking what to do with the card. */
    val choosing: Boolean get() = kind == null

    val ready: Boolean
        get() = !busy && !saved && qty >= 0 && when (kind) {
            null -> false
            Tweak.ADD, Tweak.SWAP -> pick != null && qty > 0
            Tweak.REMOVE -> subject != null
            Tweak.QUANTITY -> subject != null && qty != subject.qty
        }

    val canApply: Boolean get() = !busy && !saved && plan != null

    // ---------------------------------------------------- how many of it

    /**
     * The fewest this change can be about.
     *
     * Taking a card out is a real answer, so a count can go to nought
     * and the list drops the line. Adding nought copies of something,
     * or swapping a card in nought times, is not a change at all —
     * so the number stops at one and the minus goes off, rather than
     * sitting at nought under a Preview button that will not press.
     */
    val floor: Int get() = if (kind == Tweak.QUANTITY) 0 else 1

    /**
     * And the most.
     *
     * The box takes typing, so a thumb on the wrong key turns one
     * Sol Ring into nine thousand, and the plan that comes back is a
     * nine-thousand-card purchase. No deck wants a hundredth copy of
     * anything.
     */
    val ceiling: Int get() = MAX_QTY

    fun clamped(n: Int) = n.coerceIn(floor, ceiling)

    val canTakeOne: Boolean get() = qty > floor
    val canAddOne: Boolean get() = qty < ceiling

    /** What the deck would be called afterwards, in one line. */
    val summary: String
        get() = when (kind) {
            null -> subject?.shown.orEmpty()
            Tweak.ADD -> pick?.let { "Add ${qty}× ${it.name}" } ?: "Add a card"
            Tweak.REMOVE -> subject?.let { "Remove ${it.qty}× ${it.shown}" } ?: "Remove a card"
            Tweak.SWAP -> if (subject != null && pick != null) {
                "${subject.shown} → ${pick.name}"
            } else {
                subject?.let { "Swap out ${it.shown}" } ?: "Swap a card"
            }
            Tweak.QUANTITY -> subject?.let { "${it.shown}: ${it.qty} → $qty" } ?: "Change how many"
        }

    /**
     * The whole deck list with this one change in it.
     *
     * The commander is not in here — it travels in its own field, the
     * same as the bulk editor, because the stored value carries prose
     * after the name that a list line cannot hold.
     */
    fun listAfter(cards: List<DeckCard>): String {
        val rest = cards.filterNot { it.role == "commander" }
        val lines = rest.map { it.qty to it.shown }.toMutableList()

        fun indexOf(name: String) = lines.indexOfFirst { it.second.equals(name, ignoreCase = true) }

        when (kind) {
            null -> Unit

            Tweak.ADD -> {
                val name = pick?.name ?: return lines.join()
                val at = indexOf(name)
                // Adding one the deck already holds is one more of it,
                // not a second row that the server would fold anyway.
                if (at >= 0) lines[at] = (lines[at].first + qty) to lines[at].second
                else lines += qty to name
            }

            Tweak.REMOVE -> subject?.let { s -> indexOf(s.shown).takeIf { it >= 0 }?.let(lines::removeAt) }

            Tweak.QUANTITY -> subject?.let { s ->
                val at = indexOf(s.shown)
                if (at >= 0) {
                    if (qty <= 0) lines.removeAt(at) else lines[at] = qty to lines[at].second
                }
            }

            Tweak.SWAP -> {
                val s = subject ?: return lines.join()
                val name = pick?.name ?: return lines.join()
                val at = indexOf(s.shown)
                // However many were coming out, that many go in, unless
                // a number was given.
                val take = if (qty > 0) qty else s.qty
                if (at >= 0) lines.removeAt(at)
                val already = indexOf(name)
                if (already >= 0) lines[already] = (lines[already].first + take) to lines[already].second
                else lines.add(at.coerceAtLeast(0).coerceAtMost(lines.size), take to name)
            }
        }
        return lines.join()
    }

    private fun List<Pair<Int, String>>.join() = joinToString("\n") { "${it.first} ${it.second}" }

    // ----------------------------------------------------------- moves

    /**
     * A keystroke in the finder.
     *
     * Hits for "lig" are not answers to "l", so going back under the
     * minimum drops them rather than leaving a list up that the box
     * no longer names — tapping one of those puts in a card nobody
     * asked for. A term long enough to search counts as searching
     * until the answer lands, so the finder can tell "nothing by that
     * name" apart from "not back yet".
     */
    fun typed(text: String): DeckTweak {
        val worthAsking = text.trim().length >= MIN_TERM
        return copy(
            term = text,
            error = null,
            pick = null,
            plan = null,
            found = if (worthAsking) found else emptyList(),
            searching = worthAsking,
        )
    }
    /**
     * What the collection turned up, and then anything else by name.
     *
     * A deck can want a card nobody owns yet — that is what the plan
     * calls "to buy" — so a finder that only lists owned cards
     * cannot be used to add one. Owned first, because that is nearly
     * always the answer, and the rest marked as not owned.
     */
    fun searched(results: List<Found>, alsoNamed: List<String> = emptyList()): DeckTweak {
        // The collection groups by owner, so two people holding one
        // card is two hits and the owner is what tells them apart.
        // The same card from the same person twice is one hit shown
        // twice, and the two rows read identically.
        val mine = results.distinctBy { it.name.lowercase() to it.owner }
        val have = mine.map { it.name.lowercase() }.toSet()
        val rest = alsoNamed
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .filterNot { it.lowercase() in have }
            .map { Found(0, it, null, null, 0, "") }
        return copy(found = mine + rest, searching = false)
    }
    fun looking() = copy(searching = true)

    /** Picking a card clears any plan: it was about a different change. */
    fun picked(f: Found) =
        copy(pick = f, term = f.name, found = emptyList(), searching = false, plan = null, error = null)

    /**
     * How many, kept inside the ends, and the plan dropped if it moved.
     *
     * A plan is about one number. Changing the number and keeping the
     * plan would let the Apply button write a different change from
     * the one that was shown. But a number that did not actually move
     * — a plus at the ceiling, a 999 clamped back to 99 — must keep
     * it: throwing the plan away there makes the button go dead for
     * no reason a person can see.
     */
    fun count(n: Int): DeckTweak {
        val next = clamped(n)
        if (next == qty) return this
        return copy(qty = next, plan = null, error = null)
    }

    /** Answering "what do you want to do with this one". */
    fun doing(k: Tweak) =
        copy(kind = k, plan = null, error = null, term = "", found = emptyList(), searching = false, pick = null)

    fun working() = copy(busy = true, error = null, errors = emptyList())
    fun planned(p: DeckPlan) = copy(plan = p, busy = false, error = null)
    fun failed(message: String, lines: List<String> = emptyList()) =
        copy(busy = false, error = message, errors = lines, plan = null)

    fun finished() = copy(busy = false, saved = true, error = null)

    companion object {
        /** Worth asking the server about. One letter matches everything. */
        const val MIN_TERM = 2

        /** The most copies of one card a single change can be about. */
        const val MAX_QTY = 99

        fun add(deck: Deck, commander: String) =
            DeckTweak(deck.slug, deck.name, commander, Tweak.ADD, qty = 1)

        /** Opened on a card, with the choice of what to do still to make. */
        fun on(deck: Deck, commander: String, card: DeckCard, kind: Tweak? = null) = DeckTweak(
            slug = deck.slug,
            deckName = deck.name,
            commander = commander,
            kind = kind,
            subject = card,
            // A swap defaults to like for like, a count starts where
            // it is: either way the number begins as what is there.
            qty = card.qty,
        )
    }
}
