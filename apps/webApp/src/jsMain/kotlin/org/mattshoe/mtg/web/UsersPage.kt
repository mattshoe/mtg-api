package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Collector
import org.mattshoe.mtg.core.UsersState

/**
 * The Users tab's front page: everybody's collection as a tile, with
 * their picture, their name and how much they have. Sibling of
 * `UsersScreen` on the phone.
 *
 * Matt: "a collection of 'cards' or 'tiles' or whatever, with a
 * profile Pic on it and stuff, stylish and attractive. kinda like how
 * the Guide page has things". So it is the Guide's tile, the same
 * raised surface and band, with a face on it.
 */
@Composable
fun UsersPage(state: UsersState, onOpen: (Collector) -> Unit, onRetry: () -> Unit) {
    Div(attrs = { classes("wrap", "users") }) {
        Div(attrs = { classes("muted", "small", "users-intro") }) { Text("Everybody's collection, to look through.") }
        when {
            state.busy -> Div(attrs = { classes("empty", "users-busy") }) { Text("Loading…") }
            state.error != null -> {
                Div(attrs = { classes("err", "users-error") }) { Text("Could not load the users: ${state.error}") }
                Button(attrs = {
                    classes("btn", "users-retry")
                    onClick { onRetry() }
                }) { Text("Try again") }
            }
            state.isEmpty -> Div(attrs = { classes("empty") }) { Text("Nobody has a collection yet.") }
            else -> Div(attrs = { classes("user-tiles") }) {
                state.rows.forEach { who ->
                    Button(attrs = {
                        classes("guide-tile", "user-tile")
                        attr("data-user", who.key)
                        onClick { onOpen(who) }
                    }) {
                        Div(attrs = {
                            classes("guide-band")
                            style { property("background-image", band("WUBRG")) }
                        }) {}
                        Face(who)
                        Div(attrs = { classes("user-words") }) {
                            Div(attrs = { classes("guide-tile-title", "user-name") }) { Text(who.shownName) }
                            Div(attrs = { classes("guide-tile-blurb") }) { Text(who.blurb) }
                        }
                    }
                }
            }
        }
    }
}

/** Their picture, or the first letter of their name in a ring where there is none. */
@Composable
private fun Face(who: Collector) {
    val pic = who.avatar
    if (pic != null) Img(src = pic, alt = "", attrs = { classes("user-face") })
    else Span(attrs = { classes("user-face", "user-initial") }) { Text(who.initial) }
}

/**
 * Over somebody's Library: whose it is, and the way back to the tiles.
 * Sibling of `BrowsedHead` in `UsersScreen`.
 */
@Composable
fun BrowsedHead(name: String, onBack: () -> Unit) {
    Div(attrs = { classes("wrap", "users-head") }) {
        Button(attrs = {
            classes("btn", "users-back")
            onClick { onBack() }
        }) { Text("‹ Users") }
        Span(attrs = { classes("users-whose") }) { Text("$name's cards") }
    }
}
