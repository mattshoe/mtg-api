package org.mattshoe.mtg.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View

/**
 * The shell: the nav, the lock, and whichever screen is current.
 *
 * Which views exist and which are hidden while locked comes from
 * `Admin` in the shared core, so this cannot offer a tab the web does
 * not, or leave an admin screen reachable after locking.
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Only the views the lock allows. Gated ones are absent, not
            // greyed out — the same as the web.
            state.admin.visible.forEach { view ->
                val on = state.view == view
                if (on) {
                    Button(onClick = {}) { Text(view.label, fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { onState(state.navigate(view)) }) {
                        Text(view.label, fontSize = 13.sp)
                    }
                }
            }
            OutlinedButton(onClick = {
                if (state.admin.unlocked) onState(state.copy(admin = state.admin.lock()).navigate(state.route))
                else asking = true
            }) { Text(if (state.admin.unlocked) "Lock" else "Unlock", fontSize = 13.sp) }
        }

        when (state.view) {
            View.LIBRARY -> LibraryScreen(
                state = state.library,
                onState = { onState(state.copy(library = it)) },
                onSearch = onSearch,
                onOpen = { },
            )

            View.DECKS -> DecksScreen(
                state = state.decks,
                onOpen = { onOpenDeck(it.slug) },
                onClose = { onState(state.copy(decks = state.decks.close())) },
            )

            View.STATS -> StatsScreen(state.stats) { owner: Owner? ->
                onState(state.copy(stats = state.stats.scopedTo(owner).loading()))
            }

            View.CONSOLE -> ConsoleScreen(
                state = state.console,
                onState = { onState(state.copy(console = it)) },
                onRun = onRunSql,
            )

            View.LOGS -> LogsScreen(state.logs) { onState(state.copy(logs = it)) }

            View.ENTRY -> MassEntryScreen(
                state = state.entry,
                onState = { onState(state.copy(entry = it)) },
                onPreview = onPreviewEntry,
                onApply = onApplyEntry,
            )
        }

        state.toast?.let { Text(it, Modifier.padding(16.dp), fontSize = 13.sp) }
    }

    if (asking) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Admin mode") },
            text = {
                Column {
                    Text(
                        "The password, once. It is exchanged for a token that does not " +
                            "expire, and the password itself is never stored.",
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { asking = false; onUnlock(password) }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** Where a deck tap goes, as a route rather than a special case. */
fun AppState.openDeck(slug: String) = navigate(Route(View.DECKS, slug))
