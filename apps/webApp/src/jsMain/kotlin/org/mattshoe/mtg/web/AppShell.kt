package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View

/**
 * The shell: the nav, the lock, and whichever screen is current.
 *
 * Sibling of `AppShell` on Android. The visible tabs come from `Admin`
 * in the shared core, so a gated view cannot be absent on one platform
 * and present on the other.
 */
@Composable
fun AppShell(
    state: AppState,
    onState: (AppState) -> Unit,
    onUnlock: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenDeck: (String) -> Unit,
    onRunSql: () -> Unit,
    onPreviewEntry: () -> Unit,
    onApplyEntry: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Nav(attrs = { classes("tabs") }) {
        state.admin.visible.forEach { view ->
            Button(attrs = {
                classes("tab")
                if (state.view == view) classes("on")
                onClick { onState(state.navigate(view)) }
            }) { Text(view.label) }
        }
        Button(attrs = {
            classes("btn", "sm", "ghost")
            onClick {
                if (state.admin.unlocked) {
                    onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                } else {
                    asking = true
                }
            }
        }) { Text(if (state.admin.unlocked) "Lock" else "Unlock") }
    }

    when (state.view) {
        View.LIBRARY -> LibraryPage(
            state = state.library,
            onState = { onState(state.copy(library = it)) },
            onSearch = onSearch,
            onOpen = { },
        )

        View.DECKS -> DecksPage(
            state = state.decks,
            onOpen = { onOpenDeck(it.slug) },
            onClose = { onState(state.copy(decks = state.decks.close())) },
        )

        View.STATS -> StatsPage(state.stats) { owner: Owner? ->
            onState(state.copy(stats = state.stats.scopedTo(owner).loading()))
        }

        View.CONSOLE -> ConsolePage(
            state = state.console,
            onState = { onState(state.copy(console = it)) },
            onRun = onRunSql,
        )

        View.LOGS -> LogsPage(state.logs) { onState(state.copy(logs = it)) }

        View.ENTRY -> MassEntryPage(
            state = state.entry,
            onState = { onState(state.copy(entry = it)) },
            onPreview = onPreviewEntry,
            onApply = onApplyEntry,
        )
    }

    state.toast?.let { Div(attrs = { classes("toast") }) { Text(it) } }

    if (asking) {
        Div(attrs = { classes("palette-scrim") }) {
            Div(attrs = { classes("palette") }) {
                H2 { Text("Admin mode") }
                Div(attrs = { classes("muted", "small") }) {
                    Text(
                        "The password, once. It is exchanged for a token that does not " +
                            "expire, and the password itself is never stored.",
                    )
                }
                Input(type = InputType.Password) {
                    classes("field")
                    placeholder("Password")
                    value(password)
                    onInput { password = it.value }
                }
                Div(attrs = { classes("flex") }) {
                    Button(attrs = {
                        classes("btn", "primary")
                        onClick { asking = false; onUnlock(password); password = "" }
                    }) { Text("Unlock") }
                    Button(attrs = {
                        classes("btn", "ghost")
                        onClick { asking = false; password = "" }
                    }) { Text("Cancel") }
                }
            }
        }
    }
}

/** Where a deck tap goes, as a route rather than a special case. */
fun AppState.openDeck(slug: String) = navigate(Route(View.DECKS, slug))
