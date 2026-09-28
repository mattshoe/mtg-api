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

/** Where a copy is going to come from. */
enum class Source(val slug: String, val label: String) {
    BULK("bulk", "From bulk"),
    TRANSFER("transfer", "From the other collection"),
    BUY("buy", "Buy it"),
}

data class NewDeck(
    val step: DeckStep = DeckStep.FORMAT,
    val format: Format? = null,
    val owner: Owner? = null,
    val name: String = "",
    val commander: String = "",
    val list: String = "",
    val checked: Validation? = null,
    val sources: Map<String, Source> = emptyMap(),
    val busy: String? = null,
    val error: String? = null,
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
        get() = canLeaveCheck && !created && sourcesDecided

    val sourcesDecided: Boolean
        get() = DeckList.cardLines(list).all { line -> sources.containsKey(lineKey(line)) }

    val undecided: List<String>
        get() = DeckList.cardLines(list).filterNot { sources.containsKey(lineKey(it)) }

    val buying: List<String>
        get() = sources.filterValues { it == Source.BUY }.keys.sorted()

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
    fun setCommander(c: String) = copy(commander = c, error = null)

    /** Editing the list invalidates the check and every sourcing choice. */
    fun type(text: String) = copy(list = text, checked = null, sources = emptyMap(), error = null)

    fun source(line: String, from: Source) = copy(sources = sources + (lineKey(line) to from))

    fun goTo(target: DeckStep): NewDeck {
        val landing = steps.lastOrNull { reachable(it) && steps.indexOf(it) <= steps.indexOf(target) }
            ?: DeckStep.FORMAT
        return copy(step = if (reachable(target)) target else landing, error = null)
    }

    fun working(what: String) = copy(busy = what, error = null)
    fun failed(message: String) = copy(busy = null, error = message)
    fun validated(v: Validation) = copy(checked = v, busy = null, error = null)
    fun finished() = copy(created = true, busy = null, step = DeckStep.DONE)

    /** The slug the API will give this deck. */
    val slug: String get() = slugify(name)

    private fun lineKey(line: String) = line.trim().lowercase()

    companion object {
        /** Lowercase, words joined by hyphens, nothing else. */
        fun slugify(name: String): String = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split("-").filter { it.isNotEmpty() }
            .joinToString("-")
    }
}
