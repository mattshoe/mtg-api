package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Overlay

/**
 * The menu, in the header.
 *
 * Mounted into the top bar's own slot rather than drawn at the top of
 * the page, because a menu hanging below the bar it belongs to is not
 * a header menu. That means a second composition over the same
 * `AppState` — both read the one `mutableStateOf` in `MtgApp`, so
 * they cannot disagree about which view is current.
 *
 * One hamburger at every width. The row of tabs only ever fitted on a
 * desk, and two behaviours to keep straight is how the phone ended up
 * with no navigation at all.
 *
 * Two controls, not one. The hamburger is where you are going —
 * `Admin.bar`, the same list the phone draws along the bottom. The
 * avatar beside it is who you are and what follows from being them —
 * `Admin.behindProfile`, the account, the way in and out. The core has
 * modelled that split since the account work landed and the phone has
 * drawn it since; the website read `visible` and poured the lot into
 * one menu, so the account, the sign-out and the server log sat under
 * the same hamburger as Library and Decks. Matt: "Why the fuck is all
 * that shit still in the hamburger menu!!!!!"
 */
@Composable
fun AppNav(
    state: AppState,
    onState: (AppState) -> Unit,
    /** Off to Google. The shell owns the address; see `MtgApi.signInUrl`. */
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    // Which of the two is down, or neither. One value rather than a
    // flag each: two panels over each other is neither a place to go
    // nor a profile, and two booleans is one more state than there
    // are menus.
    var shown by remember { mutableStateOf<Menu?>(null) }
    val open = shown == Menu.PLACES
    val profileOpen = shown == Menu.PROFILE

    Nav(attrs = { classes("app-nav") }) {
        Button(attrs = {
            classes("nav-burger")
            attr("aria-label", if (open) "Close menu" else "Menu")
            attr("aria-expanded", open.toString())
            onClick { shown = if (open) null else Menu.PLACES }
        }) { repeat(3) { Span(attrs = { classes("bar") }) {} } }

        if (shown != null) {
            // A press anywhere else closes it, the way a menu is
            // expected to behave. A backdrop rather than a document
            // listener, so there is nothing to unregister.
            Div(attrs = {
                classes("nav-backdrop")
                onClick { shown = null }
            }) {}
        }

        // Where you are going, and nothing else. `bar` is the phone's
        // bottom row — Library, Decks, Stats, and Entry once there is
        // somebody to write as.
        Div(attrs = {
            classes("app-menu")
            if (open) classes("open")
        }) {
            state.admin.bar.forEach { view ->
                Button(attrs = {
                    classes("app-tab")
                    if (state.view == view) classes("on")
                    // Closes even when the view picked is the one
                    // already showing — otherwise the menu sits open
                    // over the page.
                    onClick { shown = null; onState(state.navigate(view)) }
                }) { Text(view.label) }
            }

            // The phone, which is a place you can go and not a view.
            // It was a loose link in `index.html`'s header, where no
            // Kotlin test mounts and no parity pass could see it —
            // Matt: "MOVE THE GOD DAMN LINK INTO THE HAMBURGER
            // MENU!" It never wears the current-view mark, because it
            // leaves the app entirely.
            A(href = "app/", attrs = {
                classes("app-tab", "app-away")
                onClick { shown = null }
            }) { Text("Android app") }
        }
    }

    // Home, then what you are looking at. Drawn here rather than left
    // in the static header because the title changes with the route,
    // and repeated as a heading on every page it was saying the same
    // thing twice.
    A(href = "#/search", attrs = { classes("brand") }) {
        Img(src = "icons/icon-32.png", alt = "Home", attrs = { classes("brand-mark") })
    }
    Span(attrs = { classes("topbar-title") }) { Text(state.title) }

    // Who you are, at the right-hand end of the bar, which is where
    // the phone draws it — past a title that takes `weight(1f)`.
    //
    // Its own box rather than another child of the hamburger's
    // `<nav>`: `margin-left: auto` needs the slack of the whole bar
    // to push into, and the menu hangs off this box, so its
    // `right: 0` means the bar's right edge. Matt: "put the fucking
    // profile menu in the same fucking place on web as android".
    Div(attrs = { classes("nav-profile-box") }) {
        Button(attrs = {
            classes("nav-profile")
            if (profileOpen) classes("on")
            attr("aria-label", "Profile")
            attr("aria-expanded", profileOpen.toString())
            onClick { shown = if (profileOpen) null else Menu.PROFILE }
        }) {
            // Their own picture when Google sent one: it says *which*
            // account at a glance rather than that there is one.
            // Matt: "use the user's Google profile image as the
            // profile icon. If they don't have one then the existing
            // image is fine."
            val face = state.admin.account?.avatar?.takeIf { it.isNotBlank() }
            if (face != null) {
                Img(src = face, alt = "", attrs = { classes("app-avatar") })
            } else {
                ProfileIcon()
            }
        }

        Div(attrs = {
            classes("profile-menu")
            if (profileOpen) classes("open")
        }) {
            // The first thing it says is who is here, because
            // everything under it follows from that. An account says
            // its own name and the address its collection lives at;
            // the operator's password says "Admin", because that is
            // all it can say — it is not anybody.
            Div(attrs = { classes("app-who") }) {
                state.admin.account?.avatar?.takeIf { it.isNotBlank() }
                    ?.let { Img(src = it, alt = "", attrs = { classes("app-avatar") }) }
                Div {
                    Text(
                        state.admin.shownName
                            ?: if (state.admin.unlocked) "Admin" else "Not signed in",
                    )
                    Span(attrs = { classes("app-who-slug") }) {
                        Text(
                            state.admin.account?.let { "/c/${it.slug}" }
                                ?: if (state.admin.unlocked) "Everything is editable" else "Read only",
                        )
                    }
                }
            }

            Div(attrs = { classes("app-menu-sep") }) {}

            // What being admin gets you, which today is the log. It
            // sits here rather than in the hamburger because it is a
            // screen you open when something is wrong, not one you
            // move between.
            state.admin.behindProfile.forEach { view ->
                Button(attrs = {
                    classes("app-tab")
                    if (state.view == view) classes("on")
                    onClick { shown = null; onState(state.navigate(view)) }
                }) { Text(view.label) }
            }

            // "Log out", not "Sign out": the phone says "Log out" and
            // the row above says "Not signed in", so one word for one
            // thing across both. Matt: "Change lock to log out".
            Button(attrs = {
                classes("app-tab", "app-lock")
                onClick {
                    shown = null
                    if (state.admin.signedIn) onSignOut() else onSignIn()
                }
            }) { Text(if (state.admin.signedIn) "Log out" else "Sign in with Google") }

            // The operator's own way in, and only while nobody is
            // signed in: a password is not an account and offering
            // both at once reads as two ways to be the same thing.
            if (!state.admin.signedIn) {
                Button(attrs = {
                    classes("app-tab", "app-lock")
                    onClick {
                        shown = null
                        if (state.admin.unlocked) {
                            onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                        } else {
                            onState(state.opening(Overlay.UNLOCK))
                        }
                    }
                }) { Text(if (state.admin.unlocked) "Log out" else "Log in") }
            }
        }
    }
}

/** Which of the header's two menus is down. */
private enum class Menu { PLACES, PROFILE }
