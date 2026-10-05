package org.mattshoe.mtg.core

/**
 * The new deck wizard, as a state machine.
 *
 * Same shape and the same reasoning as `MassEntry`: the steps and their
 * gates live here so two UIs cannot disagree about when a deck may be
 * created. Creating one moves real cards — it pulls from bulk and buys
 * what bulk cannot cover — so every gate on the way is load-bearing.
 */

/** The formats the wizard offers, and whether they want a commander. */
enum class Format(val slug: String, val label: String, val wantsCommander: Boolean, val size: Int) {
    COMMANDER("commander", "Commander", true, 100),
    BRAWL("brawl", "Brawl", true, 60),
    OATHBREAKER("oathbreaker", "Oathbreaker", true, 60),
    STANDARD("standard", "Standard", false, 60),
    MODERN("modern", "Modern", false, 60),
    PIONEER("pioneer", "Pioneer", false, 60),
    LEGACY("legacy", "Legacy", false, 60),
    VINTAGE("vintage", "Vintage", false, 60),
    PAUPER("pauper", "Pauper", false, 60),
    LIMITED("limited", "Limited", false, 40),
    CASUAL("casual", "Casual", false, 0),
    ;

    companion object {
        fun of(slug: String) = entries.firstOrNull { it.slug == slug }
    }
}

enum class DeckStep(val label: String) {
    FORMAT("Format"),
    OWNER("Whose"),
    NAME("Name"),
    COMMANDER("Commander"),
    CARDS("Cards"),
    CHECK("Check"),
    REVIEW("Review"),
    DONE("Done"),
}

/**
 * Where a copy comes from.
 *
 * Not a question any more. A card the collection already holds comes
 * out of bulk; one it does not hold gets added to bulk on the way in.
 * The wizard used to ask, line by line, and then not send the answer
 * — `createDeck` has never had a `sources` field — so it was a screen
 * of choices that did nothing. And "Buy it" was never true: nothing
 * here spends money, it writes a row.
 */
enum class Source(val slug: String, val label: String) {
    BULK("bulk", "From bulk"),
    ADD("buy", "Add to bulk"),
}

data class NewDeck(
    val step: DeckStep = DeckStep.FORMAT,
    val format: Format? = null,
    val owner: Owner? = null,
    val name: String = "",
    val commander: String = "",
    val list: String = "",
    val checked: Validation? = null,
    val busy: String? = null,
    val error: String? = null,
    /**
     * Name suggestions for the commander box.
     *
     * Its own, not the Library's: typing a commander must not quietly
     * rewrite the search filter on another screen.
     */
    val hint: Completion = Completion(),
    val created: Boolean = false,
) {

    val cardCount: Int get() = DeckList.countCards(list)

    /** Commander formats want one, and it is not optional there. */
    val needsCommander: Boolean get() = format?.wantsCommander == true

    /** The steps this format actually has. Commander is skipped for Standard. */
    val steps: List<DeckStep>
        get() = DeckStep.entries.filter { it != DeckStep.DONE }
            .filterNot { it == DeckStep.COMMANDER && !needsCommander }

    // ------------------------------------------------------------ gates

    val canLeaveFormat: Boolean get() = format != null
    val canLeaveOwner: Boolean get() = canLeaveFormat && owner != null
    val canLeaveName: Boolean get() = canLeaveOwner && name.isNotBlank()
    val canLeaveCommander: Boolean
        get() = canLeaveName && (!needsCommander || commander.isNotBlank())
    val canLeaveCards: Boolean get() = canLeaveCommander && cardCount > 0

    /** Every name has to be a real card before anything is sourced. */
    val namesChecked: Boolean get() = checked?.ok == true
    val canLeaveCheck: Boolean get() = canLeaveCards && namesChecked

    /**
     * The one that matters: a deck is created only once every card has
     * somewhere to come from. A slot with no source would silently be
     * conjured, which is precisely what this wizard exists to prevent.
     */
    val canCreate: Boolean
        get() = canLeaveCheck && !created && busy == null

    /**
     * One line per card, and what will happen to it.
     *
     * Derived, not chosen. The check has already told us which names
     * the collection holds, and that is the whole of the decision.
     */
    data class Line(val qty: Int, val name: String, val from: Source) {
        val owned: Boolean get() = from == Source.BULK
    }

    /** What the collection was found to already hold, by normalised name. */
    private val held: Set<String>
        get() = checked?.cards.orEmpty()
            .filter { it.ok && it.source == "collection" }
            .map { it.nameNorm }
            .toSet()

    val plan: List<Line>
        get() {
            val owned = held
            return DeckList.entries(list).map { e ->
                Line(e.qty, e.name, if (e.key in owned) Source.BULK else Source.ADD)
            }
        }

    /** The cards the collection does not hold yet. */
    val adding: List<Line> get() = plan.filterNot { it.owned }

    fun reachable(target: DeckStep): Boolean = when (target) {
        DeckStep.FORMAT -> true
        DeckStep.OWNER -> canLeaveFormat
        DeckStep.NAME -> canLeaveOwner
        DeckStep.COMMANDER -> canLeaveName
        DeckStep.CARDS -> canLeaveCommander
        DeckStep.CHECK -> canLeaveCards
        DeckStep.REVIEW -> canLeaveCheck
        DeckStep.DONE -> created
    }

    // ------------------------------------------------------------ moves

    fun pick(f: Format) = copy(format = f, error = null)
    fun assign(o: Owner) = copy(owner = o, error = null)
    fun rename(n: String) = copy(name = n, error = null)
    fun setCommander(c: String) =
        copy(commander = c, checked = null, error = null, hint = hint.typed(c))

    /** The suggestion list moved; the box shows whatever it holds. */
    fun hinting(c: Completion) = copy(commander = c.term, checked = null, error = null, hint = c)

    /** Editing the list invalidates the check taken against the old one. */
    fun type(text: String) = copy(list = text, checked = null, error = null)

    /**
     * Take a suggested spelling, wherever the wrong one is.
     *
     * The commander is its own field rather than a line in the list,
     * so a correction that only rewrote the list did nothing at all
     * for a misspelt commander — the suggestion was right there and
     * pressing it changed nothing, which reads as a broken button
     * rather than as "that one is kept somewhere else".
     */
    fun correct(wrong: String, right: String): NewDeck {
        val fixedCommander = if (commander.trim().equals(wrong.trim(), ignoreCase = true)) {
            right
        } else {
            commander
        }
        val fixedList = list.lines().joinToString("\n") { line ->
            if (sameCard(line, wrong)) line.replace(wrong, right, ignoreCase = true) else line
        }
        return copy(
            commander = fixedCommander,
            list = fixedList,
            // The check was about the old spelling, and the sourcing
            // choices were about cards one of which has just changed.
            checked = null,
            error = null,
        )
    }

    /** Is this list line that card, quantity and all? */
    private fun sameCard(line: String, name: String): Boolean {
        val body = line.trim().removePrefix("#").trim()
            .replace(Regex("""^\d+\s*[xX]?\s+"""), "")
            .trim()
        return body.equals(name.trim(), ignoreCase = true)
    }

    /**
     * Take the first card of the list as the commander.
     *
     * Every decklist export puts it first, so asking somebody to cut
     * it out by hand and retype it into another box is work the list
     * has already done.
     */
    fun commanderFromList(): NewDeck {
        val first = DeckList.firstCard(list) ?: return this
        return copy(
            commander = first.name,
            list = DeckList.withoutFirstCard(list),
            checked = null,
            error = null,
            hint = Completion(term = first.name),
        )
    }

    /**
     * Where Back goes from here, or null when it should close the
     * wizard rather than step inside it.
     *
     * Walks `steps` and not `DeckStep.entries`, so a sixty-card
     * deck steps over the commander question the way the stepper
     * already does. Null on the first step and on DONE, which is a
     * receipt and not a step.
     */
    val previousStep: DeckStep?
        get() = when (step) {
            DeckStep.DONE -> null
            else -> steps.getOrNull(steps.indexOf(step) - 1)
        }

    /**
     * Go to a step, or to the last one that is actually reachable.
     *
     * A step **back** is never clamped. The same fault the entry
     * wizard had: the clamp asks what is answered *now*, so
     * emptying the card box pinned you to the box. Everything
     * behind you was answered on the way past it.
     */
    fun goTo(target: DeckStep): NewDeck {
        val back = target != DeckStep.DONE &&
            steps.indexOf(target) in 0 until maxOf(steps.indexOf(step), 0)
        val landing = steps.lastOrNull { reachable(it) && steps.indexOf(it) <= steps.indexOf(target) }
            ?: DeckStep.FORMAT
        return copy(step = if (back || reachable(target)) target else landing, error = null)
    }

    fun working(what: String) = copy(busy = what, error = null)
    fun failed(message: String) = copy(busy = null, error = message)
    fun validated(v: Validation) = copy(checked = v, busy = null, error = null)
    fun finished() = copy(created = true, busy = null, step = DeckStep.DONE)

    /** The slug the API will give this deck. */
    val slug: String get() = slugify(name)


    companion object {
        /** Lowercase, words joined by hyphens, nothing else. */
        fun slugify(name: String): String = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split("-").filter { it.isNotEmpty() }
            .joinToString("-")
    }
}
