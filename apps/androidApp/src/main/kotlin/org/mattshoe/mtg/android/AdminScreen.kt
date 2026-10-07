package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person

/**
 * Admin Settings, on Android. Sibling of `AdminPage`.
 *
 * Matt: "under account avatar, we'll create a button 'Admin Settings'
 * and in there will live all of the admin knobs and levers like
 * assigning roles etc"
 *
 * Today the only lever is who has which role, under the same heading
 * the website gives it.
 */
@Composable
fun AdminScreen(state: People, me: String?, onChange: (Person) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Panel(
            head = "Who is here",
            note = "${state.rows.size} " + if (state.rows.size == 1) "account" else "accounts",
        ) {
            when {
                state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("people-busy"))
                state.error != null -> Line(
                    "Could not load accounts: ${state.error}",
                    Bad,
                    modifier = Modifier.testTag("people-err"),
                )
                state.rows.isEmpty() -> Line("No accounts yet.", Ink3)
                else -> state.rows.forEach { person -> PersonRow(state, person, me, onChange) }
            }
        }
    }
}

@Composable
private fun PersonRow(state: People, person: Person, me: String?, onChange: (Person) -> Unit) {
    val changing = state.isChanging(person.slug)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("person-${person.slug}"),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.avatar, on = person.isAdmin, size = 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Line(person.shownName, Ink, Design.BODY)
            Line("/c/${person.slug}", Ink3, Design.MINI)
        }
        // What they are, in a word. The button beside it says what it
        // will do, which is the other thing.
        Tag(person.role)
        if (state.mayChange(person.slug, me)) {
            Ghost(
                when {
                    changing -> "Working…"
                    person.isAdmin -> "Make user"
                    else -> "Make admin"
                },
                enabled = !changing,
            ) { onChange(person) }
        } else {
            // The last admin cannot take their own role away: the
            // server refuses it, and a button whose only output is an
            // error message is not a button.
            Line("the only admin", Ink3, Design.MINI)
        }
    }
}
