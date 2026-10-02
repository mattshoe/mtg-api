package org.mattshoe.mtg.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Editing a deck, and taking one apart.
 *
 * Both write, both move real cards, and both show the server's own dry
 * run before they are allowed to. The wire shapes are positional arrays
 * — `["Sol Ring", 2]` — which are cheap to send and unreadable in code,
 * so they become something named exactly once, here.
 */

/** `["Sol Ring", 2]`: a card and how many. */
@Serializable(with = TallySerializer::class)
data class Tally(val name: String, val qty: Int)

/** `["Sol Ring", 1, 3]`: a card, what it was, what it becomes. */
@Serializable(with = ShiftSerializer::class)
data class Shift(val name: String, val before: Int, val after: Int)

@Serializable
data class DeckRef(val slug: String = "", val name: String = "", val owner: String = "")

/**
 * What saving this list would do.
 *
 * `acquired` is the one worth reading twice: bulk has no spare copy of
 * those, so saving records them as bought. Nothing else on this screen
 * changes what the collection contains.
 */
@Serializable
data class DeckPlan(
    val deck: DeckRef = DeckRef(),
    val commander: String? = null,
    @SerialName("commander_changed") val commanderChanged: Boolean = false,
    val rows: Int = 0,
    @SerialName("card_count") val cardCount: Int = 0,
    @SerialName("owned_count") val ownedCount: Int = 0,
    val added: List<Tally> = emptyList(),
    val removed: List<Tally> = emptyList(),
    val changed: List<Shift> = emptyList(),
    @SerialName("newly_missing") val newlyMissing: List<String> = emptyList(),
    val acquired: List<Tally> = emptyList(),
    val returned: List<Tally> = emptyList(),
    val applied: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false,
    val created: Boolean = false,
    val slug: String? = null,
    val errors: List<String> = emptyList(),
) {
    val nothingChanges: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !commanderChanged

    /** Copies this edit would buy. The number people want before saving. */
    val buying: Int get() = acquired.sumOf { it.qty }
}

/** What disassembling would free. */
@Serializable
data class Disassembly(
    val deck: DeckRef = DeckRef(),
    val freed: Int = 0,
    val cards: List<FreedCard> = emptyList(),
    val applied: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class FreedCard(val name: String = "", val qty: Int = 0)

/**
 * The edit dialog's state.
 *
 * Two presses, always: the first asks the server what would happen, the
 * second commits what it said. `canSave` is false until a plan is in
 * hand and goes false again the moment the text changes, so what is
 * approved is what is written.
 */
data class DeckEditState(
    val slug: String = "",
    val deckName: String = "",
    val commander: String = "",
    val list: String = "",
    val plan: DeckPlan? = null,
    /** The exact text the plan was taken against. */
    val reviewed: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val errors: List<String> = emptyList(),
    val saved: Boolean = false,
) {
    val lineCount: Int get() = DeckList.countCards(list)

    /** Edited since the dry run, so the plan on screen is about old text. */
    val stale: Boolean get() = reviewed != null && reviewed != signature

    private val signature: String get() = commander.trim() + "\n--\n" + list.trim()

    val canReview: Boolean get() = !busy && list.isNotBlank()
    val canSave: Boolean get() = !busy && plan != null && !stale && !saved

    fun typeList(text: String) = copy(list = text, error = null, errors = emptyList())
    fun typeCommander(text: String) = copy(commander = text, error = null, errors = emptyList())

    fun working() = copy(busy = true, error = null, errors = emptyList())
    fun planned(p: DeckPlan) = copy(plan = p, reviewed = signature, busy = false, error = null)
    fun failed(message: String, lines: List<String> = emptyList()) =
        copy(busy = false, error = message, errors = lines, plan = null, reviewed = null)

    fun finished(p: DeckPlan) = copy(plan = p, busy = false, saved = true, error = null)

    companion object {
        /**
         * The deck as text, with the commander pulled out into its own
         * field so the box below is the 99 and nothing else.
         */
        fun of(deck: Deck, cards: List<DeckCard>) = DeckEditState(
            slug = deck.slug,
            deckName = deck.name,
            commander = cards.filter { it.role == "commander" }
                .joinToString(" // ") { it.name }
                .ifEmpty { deck.commanderName.orEmpty() },
            list = cards.filterNot { it.role == "commander" }
                .joinToString("\n") { "${it.qty} ${it.name}" },
        )
    }
}

/** The confirmation in front of a disassemble. Nothing else guards it. */
data class DisassembleState(
    val slug: String = "",
    val deckName: String = "",
    val owner: String = "",
    val plan: Disassembly? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val canGo: Boolean get() = plan != null && !busy && !done

    /** Said in full, because there is no undo behind it. */
    val warning: String
        get() = "$deckName is deleted, along with its list and notes. The " +
            "${plan?.freed ?: 0} card${if (plan?.freed == 1) "" else "s"} it is holding go " +
            "back to $owner's bulk — nothing leaves the collection. This cannot be undone."

    fun planned(d: Disassembly) = copy(plan = d, busy = false, error = null)
    fun working() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun finished() = copy(done = true, busy = false)
}

// ----------------------------------------------------------- wire shapes

internal object TallySerializer : KSerializer<Tally> {
    override val descriptor = buildClassSerialDescriptor("Tally")

    override fun deserialize(decoder: Decoder): Tally {
        val row = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("expected [name, qty]")
        return Tally(
            name = row.getOrNull(0)?.jsonPrimitive?.content.orEmpty(),
            qty = row.getOrNull(1)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    override fun serialize(encoder: Encoder, value: Tally) =
        throw SerializationException("plans are only ever read")
}

internal object ShiftSerializer : KSerializer<Shift> {
    override val descriptor = buildClassSerialDescriptor("Shift")

    override fun deserialize(decoder: Decoder): Shift {
        val row = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("expected [name, before, after]")
        return Shift(
            name = row.getOrNull(0)?.jsonPrimitive?.content.orEmpty(),
            before = row.getOrNull(1)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            after = row.getOrNull(2)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    override fun serialize(encoder: Encoder, value: Shift) =
        throw SerializationException("plans are only ever read")
}


/**
 * Renaming a deck.
 *
 * The slug travels with the name, because the slug is the address and
 * a deck called one thing living at the address of another is a link
 * that lies. The new address is worked out here so the box can show
 * it before anything is written.
 */
data class RenameState(
    val slug: String,
    val was: String,
    val name: String = was,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    /** Where it will live, by the same rule the server uses. */
    val nextSlug: String get() = NewDeck.slugify(name)

    val changed: Boolean get() = name.trim() != was.trim()

    val canSave: Boolean
        get() = !busy && !done && name.isNotBlank() && nextSlug.isNotEmpty() && changed

    fun typed(text: String) = copy(name = text, error = null)
    fun working() = copy(busy = true, error = null)
    fun failed(message: String) = copy(busy = false, error = message)
    fun finished() = copy(busy = false, done = true, error = null)
}
