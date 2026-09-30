package org.mattshoe.mtg.core

/**
 * The mass entry wizard, as a state machine.
 *
 * This is the whole point of the shared core. Two hand-written UIs can
 * disagree about padding and survive it. What they must never disagree
 * about is whether Apply is reachable without a dry run, or whether a
 * list can move without somebody having said whose it is — and neither of
 * them gets a vote, because neither of them decides.
 *
 * Nothing here talks to a screen or a network. It is given events and
 * results, and it answers with what is true; the platforms render that
 * and call the API on its behalf.
 */

enum class Step(val label: String) {
    WHICH("Which"),
    LIST("List"),
    WHO("Who"),
    REVIEW("Review"),
    DONE("Done"),
    ;

    companion object {
        /** The four that appear in the stepper. DONE is an ending, not a step. */
        val wizard = listOf(WHICH, LIST, WHO, REVIEW)
    }
}

data class MassEntry(
    val step: Step = Step.WHICH,
    val direction: Direction? = null,
    val list: String = "",
    val owner: Owner? = null,
    val preview: Applied? = null,
    val result: Applied? = null,
    val busy: String? = null,
    val error: String? = null,
) {

    val cardCount: Int get() = DeckList.countCards(list)
    val isCsv: Boolean get() = DeckList.looksLikeCsv(list)

    /**
     * What is in the box: how many cards, and how many different
     * ones. A line count is what the request size is limited by and
     * is not the number anybody pasting a deck is looking for.
     */
    val tally: DeckList.Tally get() = DeckList.tally(list)
    val overLimit: Boolean get() = cardCount > MAX_CARDS

    // ---------------------------------------------------------- the gates
    //
    // One place, asked by both platforms, never re-derived by either.

    /** A direction has to be chosen before there is anything to do. */
    val canLeaveWhich: Boolean get() = direction != null

    /** A list has to be a list, and not an enormous one. */
    val canLeaveList: Boolean get() = canLeaveWhich && cardCount > 0 && !overLimit

    /** Whose it is, said out loud. */
    val canPreview: Boolean get() = canLeaveList && owner != null

    /**
     * The one that matters. A write is offered only when a dry run has
     * come back from the server describing what it would do, and only
     * when it would actually do something.
     */
    val canApply: Boolean
        get() = canPreview && preview != null && preview.changes.isNotEmpty() && result == null

    /** Which steps the stepper may jump back to: the ones already passed. */
    fun reachable(target: Step): Boolean = when (target) {
        Step.WHICH -> true
        Step.LIST -> canLeaveWhich
        Step.WHO -> canLeaveList
        Step.REVIEW -> canPreview
        Step.DONE -> result != null
    }

    // ------------------------------------------------------------ moves

    fun choose(d: Direction) = copy(direction = d, error = null)

    /** Editing the list invalidates any dry run taken against the old one. */
    fun type(text: String) = copy(list = text, preview = null, error = null)

    fun assign(o: Owner) = copy(owner = o, preview = null, error = null)

    /**
     * Go to a step, or to the earliest one still unanswered.
     *
     * Asking for somewhere unreachable is not an error and not a no-op —
     * it lands on whatever is actually missing, so a stale link or a
     * double tap cannot strand anyone on a half-filled screen.
     */
    fun goTo(target: Step): MassEntry {
        val landing = when {
            !canLeaveWhich -> Step.WHICH
            target == Step.LIST -> Step.LIST
            !canLeaveList -> Step.LIST
            target == Step.WHO -> Step.WHO
            !canPreview -> Step.WHO
            target == Step.DONE && result == null -> Step.REVIEW
            else -> target
        }
        // Stepping back throws away the dry run: the next one has to be
        // taken against whatever the list and owner have become.
        val keepsPreview = landing == Step.REVIEW || landing == Step.DONE
        return copy(
            step = landing,
            preview = if (keepsPreview) preview else null,
            error = null,
        )
    }

    fun working(what: String) = copy(busy = what, error = null)

    fun failed(message: String) = copy(busy = null, error = message)

    fun previewed(a: Applied) = copy(busy = null, error = null, preview = a, step = Step.REVIEW)

    fun finished(a: Applied) = copy(busy = null, error = null, result = a, step = Step.DONE)

    /** Same direction, empty everything else. What "enter more" means. */
    fun again() = MassEntry(list = "")

    companion object {
        /** The API's own cap on one call. */
        const val MAX_CARDS = 1000

        /** A share arrives as a list nobody has said anything about yet. */
        fun fromShare(text: String) = MassEntry(list = text)
    }
}
