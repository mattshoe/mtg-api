package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
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
 */
@Composable
fun AppNav(state: AppState, onState: (AppState) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Nav(attrs = { classes("app-nav") }) {
        Button(attrs = {
            classes("nav-burger")
            attr("aria-label", if (open) "Close menu" else "Menu")
            attr("aria-expanded", open.toString())
            onClick { open = !open }
        }) { repeat(3) { Span(attrs = { classes("bar") }) {} } }

        if (open) {
            // A press anywhere else closes it, the way a menu is
            // expected to behave. A backdrop rather than a document
            // listener, so there is nothing to unregister.
            Div(attrs = {
                classes("nav-backdrop")
                onClick { open = false }
            }) {}
        }

        Div(attrs = {
            classes("app-menu")
            if (open) classes("open")
        }) {
            // Everything anybody can reach.
            state.admin.visible.filterNot { it.gated }.forEach { view ->
                Button(attrs = {
                    classes("app-tab")
                    if (state.view == view) classes("on")
                    // Closes even when the view picked is the one
                    // already showing — otherwise the menu sits open
                    // over the page.
                    onClick { open = false; onState(state.navigate(view)) }
                }) { Text(view.label) }
            }

            // And the admin half, set apart so it reads as a different
            // kind of thing rather than three more places to go.
            Div(attrs = { classes("app-menu-sep") }) {}
            Div(attrs = { classes("app-menu-group") }) { Text("Admin") }

            state.admin.visible.filter { it.gated }.forEach { view ->
                Button(attrs = {
                    classes("app-tab")
                    if (state.view == view) classes("on")
                    onClick { open = false; onState(state.navigate(view)) }
                }) { Text(view.label) }
            }

            Button(attrs = {
                classes("app-tab", "app-lock")
                onClick {
                    open = false
                    if (state.admin.unlocked) {
                        onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                    } else {
                        onState(state.opening(Overlay.UNLOCK))
                    }
                }
            }) { Text(if (state.admin.unlocked) "Lock" else "Unlock") }
        }
    }
}
