package org.mattshoe.mtg.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Do these cards exist?
 *
 * The wizard asks before it lets a list through. A typo that reaches the
 * database becomes a card nobody owns and a deck slot nothing can fill,
 * so this goes through the Worker's own `/cards/validate`, which checks
 * the collection first — free, offline — and only then asks Scryfall.
 */
@Serializable
data class NameCheck(
    val name: String = "",
    @SerialName("name_norm") val nameNorm: String = "",
    val ok: Boolean = false,
    val source: String? = null,
    val suggestion: String? = null,
)

@Serializable
data class Validation(
    val ok: Boolean = false,
    val checked: Int = 0,
    val unknown: Int = 0,
    val cards: List<NameCheck> = emptyList(),
) {
    val bad: List<NameCheck> get() = cards.filterNot { it.ok }

    /** The ones worth offering a correction for. */
    val suggestions: List<Pair<String, String>>
        get() = bad.mapNotNull { c -> c.suggestion?.let { c.name to it } }
}
