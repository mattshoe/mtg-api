package org.mattshoe.mtg.core

/**
 * The keyboard, and what the `?` toast says about it.
 *
 * Ported from the keydown handler in `app.js`. A phone has no keyboard
 * most of the time, but it has one the moment anything is plugged in or
 * paired, and the answer to "what does `d` do" should not depend on
 * which of the two apps is in front of you.
 */
sealed interface Action {
    data class Go(val view: View) : Action
    data object OpenPalette : Action
    data object ShowHelp : Action
    data object Close : Action
}

object Shortcuts {

    private val GO = mapOf(
        "s" to View.LIBRARY,
        "d" to View.DECKS,
        "e" to View.ENTRY,
        "g" to View.STATS,
        "v" to View.LOGS,
    )

    /**
     * What a key means right now.
     *
     * `typing` is the guard that matters: every one of these is a bare
     * letter, so without it the shortcuts fire while a card name is
     * being typed and the page jumps away mid-word.
     *
     * A shortcut for a gated view is as hidden as its tab — it returns
     * null while locked rather than bouncing off the lock, because the
     * bounce would tell you the screen exists.
     */
    fun of(key: String, typing: Boolean, admin: Admin, anythingOpen: Boolean): Action? {
        if (key == "Escape") return if (anythingOpen) Action.Close else null
        if (typing) return null
        GO[key]?.let { return if (admin.reachable(it)) Action.Go(it) else null }
        return when (key) {
            "/" -> Action.OpenPalette
            "?" -> Action.ShowHelp
            else -> null
        }
    }

    /** ⌘K and Ctrl-K, which work even while typing. */
    fun ofChord(key: String, meta: Boolean, ctrl: Boolean): Action? =
        if (key.lowercase() == "k" && (meta || ctrl)) Action.OpenPalette else null

    /**
     * The `?` toast. It lists only what is actually reachable.
     *
     * No `l` any more: it toggled the shared password, and signing in
     * or out is a thing you do once through the profile rather than a
     * key you can hit by accident.
     */
    fun help(admin: Admin): String = buildString {
        append("s search · d decks")
        if (admin.signedIn) append(" · e entry")
        append(" · g stats")
        if (admin.account?.isOperator == true) append(" · v logs")
        append(" · / or ⌘K find · esc close")
    }
}
