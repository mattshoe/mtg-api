package org.mattshoe.mtg.core

/**
 * The Tools tab: Fetch and Entry, as tiles.
 *
 * Matt: "let's collapse fetch and entry into a "Tools" tab. the tools
 * page should look very similar in presentation to the users tab, but
 * instead of profile avatars, use the current icons on the bottom nav
 * for them".
 *
 * Which tools there are is `Admin.tools`, read off `View.tool`, so
 * Entry still needs somebody signed in. This is what a tile says under
 * its name.
 */
object Tools {
    fun blurb(view: View): String = when (view) {
        View.FETCH -> "Paste a deck list and find where every card is filed."
        View.ENTRY -> "Add cards to your collection, or take them out, by the list."
        else -> ""
    }
}
