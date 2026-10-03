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
     * Where a link somebody else opens is served from.
     *
     * Not the site: the site routes on a hash, and a fragment is
     * never sent to a server, so Discord and Slack ask for `/` and
     * unfurl the same bare page whatever was shared. These addresses
     * name the deck or the card in the path, so there is something
     * to answer with — real Open Graph tags, then a redirect into the
     * app for whoever clicks.
     */
    const val PREVIEW = "https://mtg-api.mattshoe81.workers.dev"

    /**
     * The whole address, not the fragment. A hash on its own is not a
     * link.
     *
     * A deck or a card gets its previewable address; anything else
     * has nothing per-page to say, so it stays the plain site link.
     */
    fun link(state: AppState): String {
        val deck = state.route.takeIf { it.view == View.DECKS }?.rest?.takeIf { it.isNotEmpty() }
        if (deck != null) return "$PREVIEW/s/deck/${encode(deck)}"
        state.cardRef?.let { return "$PREVIEW/s/card/${encode(it.nameNorm)}" }
        return SITE + state.hash()
    }

    /** Percent-encoding, for the handful of characters a name can carry. */
    private fun encode(raw: String): String = buildString {
        raw.forEach { c ->
            when {
                // ASCII only. `isLetterOrDigit` is true of 'ö' and
                // every other letter in the world, which would walk
                // straight into the URL unencoded.
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~" -> append(c)
                else -> c.toString().encodeToByteArray().forEach { b ->
                    append('%')
                    append(((b.toInt() and 0xFF) shr 4).toString(16).uppercase())
                    append((b.toInt() and 0x0F).toString(16).uppercase())
                }
            }
        }
    }

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
