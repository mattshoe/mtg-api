package org.mattshoe.mtg.core

/**
 * A link to what is on screen, and what it should be called.
 *
 * Shared so the phone's share sheet and the website's copy button
 * hand over the same URL. Building it in two places is how they end
 * up disagreeing about whether the filters travel with it.
 */
object Share {

    const val SITE = "https://mtg.mattshoe.org/"

    /** The whole address, not the fragment. A hash on its own is not a link. */
    fun link(state: AppState): String = SITE + state.hash()

    /** What a share sheet puts above it. */
    fun title(state: AppState): String = when {
        state.cardRef != null -> state.card?.name.orEmpty().ifBlank { state.title }
        else -> state.title
    }
}

/** Where an export goes. The two things a list is ever wanted for. */
enum class ExportTo(val slug: String, val label: String) {
    CLIPBOARD("clipboard", "Copy"),
    FILE("file", "Download"),
}

/**
 * What a share hands over.
 *
 * A link is for sending somebody the page. A deck list is for putting
 * the deck into somebody else's builder, which is the other half of
 * what a share button is ever pressed for.
 */
enum class ShareWhat(val slug: String, val label: String) {
    LINK("link", "Link"),
    DECKLIST("decklist", "Deck list"),
}
