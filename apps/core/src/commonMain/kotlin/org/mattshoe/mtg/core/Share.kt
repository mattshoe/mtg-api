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

    /**
     * The Worker's preview page, which the route is put after.
     *
     * A chat app fetches a pasted link to build its preview, and a
     * fragment never reaches a server, so a link to the site itself
     * previewed as the bare site whatever it pointed at. `/s/<route>`
     * answers with that deck's or card's tags and sends the reader on
     * to [SITE] with the same route after the `#` — see src/preview.js.
     */
    const val PREVIEW = "https://mtg-api.mattshoe81.workers.dev/s/"

    /** The whole address, not the fragment. A hash on its own is not a link. */
    fun link(state: AppState): String = PREVIEW + state.hash().removePrefix("#").removePrefix("/")

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
