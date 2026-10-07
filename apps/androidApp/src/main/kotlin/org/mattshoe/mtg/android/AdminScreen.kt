package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import org.mattshoe.mtg.core.Role

/**
 * Admin Settings, on Android. Sibling of `AdminPage`.
 *
 * Two screens: who is here, and one person. Matt: "I need to be able
 * to search users and then tapping one needs to open a user details
 * page where i can assign roles!!!"
 *
 * A row says who somebody is and nothing else. Everything you can
 * *do* to them is on their own page, where there is room for it — the
 * first version jammed a chip, a warning and a button in beside the
 * name, which was unreadable at two accounts and would not survive a
 * third role.
 */
@Composable
fun AdminScreen(
    state: People,
    me: String?,
    person: Person?,
    onSearch: (String) -> Unit = {},
    onOpen: (Person) -> Unit = {},
    onSetRole: (Person, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        if (person != null) {
            PersonPage(state, person, me, onSetRole, onBack)
        } else {
            Everybody(state, onSearch, onOpen)
        }
    }
}

@Composable
private fun Everybody(state: People, onSearch: (String) -> Unit, onOpen: (Person) -> Unit) {
    Panel(
        head = "Who is here",
        note = "${state.rows.size} " + if (state.rows.size == 1) "account" else "accounts",
    ) {
        Field(
            value = state.query,
            onValueChange = onSearch,
            placeholder = "Search by name, address or role",
            modifier = Modifier.testTag("people-search"),
        )
        when {
            state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("people-busy"))
            state.error != null -> Line(
                "Could not load accounts: ${state.error}",
                Bad,
                modifier = Modifier.testTag("people-err"),
            )
            state.rows.isEmpty() -> Line("No accounts yet.", Ink3)
            state.nothingMatched -> Line("Nobody matches that.", Ink3)
            else -> state.shown.forEach { p -> PersonRow(p, onOpen) }
        }
    }
}

/** One account: who they are, and a way in. Nothing to press by mistake. */
@Composable
private fun PersonRow(person: Person, onOpen: (Person) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // A whole row is the target, at a thumb's height.
            .heightIn(min = 48.dp)
            .clickable { onOpen(person) }
            .padding(vertical = 7.dp)
            .testTag("person-${person.slug}"),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.avatar, on = person.isAdmin, size = 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Line(person.shownName, Ink, Design.BODY)
            person.address?.let { Line(it, Ink3, Design.MINI) }
        }
        Tag(person.role)
        Line("›", Ink3, Design.BODY)
    }
}

/**
 * One account's page: the facts, then the role.
 *
 * The roles come from `Role.all` rather than a toggle between the two
 * there are today, so a third needs no new control — Matt: "WHAT
 * HAPPENS WHEN SET HAVE 20 DIFFERENT FUCKING ROLES?!?!"
 */
@Composable
private fun PersonPage(
    state: People,
    person: Person,
    me: String?,
    onSetRole: (Person, String) -> Unit,
    onBack: () -> Unit,
) {
    Ghost("← Everybody") { onBack() }

    Panel {
        Row(
            Modifier.fillMaxWidth().testTag("person-head"),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(person.avatar, on = person.isAdmin, size = 52.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(person.shownName, Ink, Design.H2)
                person.address?.let { Line(it, Ink3, Design.SMALL) }
            }
        }
        Fact("Role", person.role)
        Fact("Collection", person.slug)
        Fact("Key", person.key)
    }

    Line("Role", Ink, Design.H3)
    state.error?.let { Line(it, Bad, Design.SMALL) }
    Role.all.forEach { role ->
        val on = person.role == role
        Choice(role, describe(role), on) {
            if (!on && !state.isChanging(person.slug)) onSetRole(person, role)
        }
    }
    if (state.strands(person.slug, me)) {
        // The one change nothing here can undo: `ADMIN_PASSWORD` and a
        // script are the way back. Said, not refused — Matt: "I want to
        // be able to assign and remove roles at will!!!!"
        Line(
            "You are the only admin. Taking your own role away leaves nobody who can "
                + "hand it out, and only the server password could undo it.",
            Ink3,
            Design.MINI,
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Line(label, Ink3, Design.MINI, modifier = Modifier.width(100.dp))
        Line(value, Ink, Design.SMALL, modifier = Modifier.weight(1f))
    }
}

private fun describe(role: String): String = when (role) {
    Role.ADMIN -> "Everything: anybody's cards, and handing out roles"
    else -> "Their own collection, and nothing else"
}
