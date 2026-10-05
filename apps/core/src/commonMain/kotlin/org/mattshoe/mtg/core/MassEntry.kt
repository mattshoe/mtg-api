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
    /**
     * The first question's third answer: not cards in or cards out,
     * a new deck.
     *
     * Held here rather than in a screen so that both apps agree
     * about what was picked, whether Continue is pressable and
     * where Continue goes. Matt: "On the entry screen, we need a
     * new option 'new deck' that launches the new deck flow."
     */
    val startingADeck: Boolean = false,
) {

    val cardCount: Int get() = DeckList.countCards(list)
    val isCsv: Boolean get() = DeckList.looksLikeCsv(list)

    /**
     * What is in the box: how many cards, and how many different
     * ones. A line count is what the request size is limited by and
     * is not the number anybody pasting a deck is looking for.
     */
    val tally: DeckList.Tally get() = DeckList.tally(list)

    /**
     * Work the server has not been told about.
     *
     * A list in the box, or a dry run taken against it, and no result
     * back. Moving to another screen keeps all of it — the state
     * lives above the wizard — but closing the tab does not, and
     * that is worth a word before it happens.
     */
    val unsaved: Boolean get() = result == null && (list.isNotBlank() || preview != null)
    val overLimit: Boolean get() = cardCount > MAX_CARDS

    // ---------------------------------------------------------- the gates
    //
    // One place, asked by both platforms, never re-derived by either.

    /**
     * A direction has to be chosen before there is anything to do.
     *
     * Deliberately unmoved by [startingADeck]. This gates the list
     * path, and the list path needs a direction — the step after it
     * asks `direction`'s own question and the one after that writes
     * to the collection. Picking the deck wizard answers the first
     * question without answering this one; see [canContinue].
     */
    val canLeaveWhich: Boolean get() = direction != null

    /**
     * Anything at all picked on the first question.
     *
     * What the Continue button is enabled by. Where it goes is the
     * other half: [startingADeck] means the deck wizard, a direction
     * means the list.
     */
    val canContinue: Boolean get() = canLeaveWhich || startingADeck

    /** A list has to be a list, and not an enormous one. */
    val canLeaveList: Boolean get() = canLeaveWhich && cardCount > 0 && !overLimit

    /** Whose it is, said out loud. */
    val canPreview: Boolean get() = canLeaveList && owner != null && busy == null

    /**
     * The one that matters. A write is offered only when a dry run has
     * come back from the server describing what it would do, and only
     * when it would actually do something — and not while one is
     * already on its way.
     *
     * `busy` belongs in here rather than only in what the screen draws.
     * Every call carries its own idempotency key, so two presses are
     * two separate writes and a double-tapped "Add 248 printings" adds
     * them twice. The flag existed and nothing ever set it or asked.
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

    fun choose(d: Direction) = copy(direction = d, startingADeck = false, error = null)

    /** The third answer. Exclusive with a direction, the way a radio is. */
    fun startADeck() = copy(direction = null, startingADeck = true, error = null)

    /** Editing the list invalidates any dry run taken against the old one. */
    fun type(text: String) = copy(list = text, preview = null, error = null)

    fun assign(o: Owner) = copy(owner = o, preview = null, error = null)

    /**
     * Where Back goes from here, or null when it should leave the
     * wizard rather than step inside it.
     *
     * Null on the first question, because there is nothing before
     * it, and null on the receipt, because DONE is an ending: going
     * "back" into Review from a receipt would offer to apply a list
     * that has already been applied.
     */
    val previousStep: Step?
        get() = when (step) {
            Step.WHICH, Step.DONE -> null
            else -> Step.wizard.getOrNull(Step.wizard.indexOf(step) - 1)
        }

    /**
     * Go to a step, or to the earliest one still unanswered.
     *
     * Asking for somewhere **ahead** that is unreachable is not an
     * error and not a no-op — it lands on whatever is actually
     * missing, so a stale link or a double tap cannot strand anyone
     * on a half-filled screen.
     *
     * A step **back** is never clamped, and that is the fix for what
     * Matt called back being "really wonky and doing weird things a
     * lot". The clamp was applied to every move, so going back asked
     * "what is still unanswered?" and answered with the step you were
     * standing on or one in front of it: from the List step with an
     * empty box, "← Back" landed on List, because the box was empty;
     * from Who with a full box it landed on Who, because nobody had
     * been named. Deleting what you had typed was enough to pin you
     * where you were.
     *
     * Everything behind you has been answered by definition — that
     * is how you got past it — so there is nothing to clamp to.
     */
    fun goTo(target: Step): MassEntry {
        val landing = when {
            target.ordinal < step.ordinal -> target
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

    /**
     * Back to the first question with nothing filled in. What "enter
     * more" means: still the wizard, and nothing of the last run left
     * in it — not the list, not the dry run, not the receipt.
     */
    fun again() = MassEntry(list = "")

    companion object {
        /** The API's own cap on one call. */
        const val MAX_CARDS = 1000

        /** A share arrives as a list nobody has said anything about yet. */
        fun fromShare(text: String) = MassEntry(list = text)
    }
}
