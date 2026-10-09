package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Releases
import org.mattshoe.mtg.core.Role
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.Tasks

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
    releases: Releases = Releases(),
    tasks: Tasks = Tasks(),
    onToggleDone: () -> Unit = {},
    /** The New task button, which opens its own screen. */
    onNewTask: () -> Unit = {},
    /** A task tapped, which opens its own screen. */
    onOpenTask: (Task) -> Unit = {},
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
            TaskList(tasks, onToggleDone, onNewTask, onOpenTask)
            ReleaseNotes(releases)
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

/**
 * Intake requests and where each one is: the active ones, then the
 * finished ones folded behind a toggle, newest first.
 *
 * Matt: "I want the done ones minimized by default but still
 * browsable, ordered by the time which they completed, most recent
 * first". Sibling of the website's `taskList`.
 */
@Composable
private fun TaskList(tasks: Tasks, onToggleDone: () -> Unit, onNewTask: () -> Unit, onOpen: (Task) -> Unit) {
    Panel(head = "Tasks") {
        // Matt: "I want to be able to tap a "new task" button".
        Box(Modifier.testTag("new-task")) { Primary("New task") { onNewTask() } }
        when {
            tasks.busy -> Line("Loading…", Ink3)
            tasks.error != null -> Line("Could not load tasks: ${tasks.error}", Bad)
            tasks.rows.isEmpty() -> Line("No tasks yet.", Ink3)
            else -> {
                if (tasks.active.isEmpty()) Line("Nothing in progress.", Ink3)
                tasks.active.forEach { TaskRow(it, "task", tasks.now, onOpen) }
                Line(
                    (if (tasks.showDone) "▾ " else "▸ ") + "Done (${tasks.done.size})",
                    Ink,
                    Design.SMALL,
                    FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onToggleDone() }
                        .padding(vertical = 12.dp)
                        .testTag("tasks-done-toggle"),
                )
                if (tasks.showDone) tasks.done.forEach { TaskRow(it, "task-done", tasks.now, onOpen) }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, tag: String, now: Long, onOpen: (Task) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // Matt: "I want to be able to tap on a task and be taken to a
            // details page". The whole row, at a thumb's height, like a person's.
            .heightIn(min = 48.dp)
            .clickable { onOpen(task) }
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Line(task.title, Ink, Design.BODY)
            (task.took ?: task.elapsed(now))?.let { Line(it, Ink3, Design.MINI) }
            task.note?.let { Line(it, Ink3, Design.MINI) }
        }
        Tag(task.status.word)
        Line("›", Ink3, Design.BODY)
    }
}

/**
 * Every build that shipped, newest first: its version, the day, and
 * what a person wrote about it.
 *
 * Matt: "I JUST WANT TO FUCKING SEE THEM IN THE ADMIN SETTINGS!!!!!"
 */
@Composable
private fun ReleaseNotes(releases: Releases) {
    Panel(head = "Release notes") {
        when {
            releases.busy -> Line("Loading…", Ink3)
            releases.error != null -> Line("Could not load release notes: ${releases.error}", Bad)
            releases.rows.isEmpty() -> Line("No releases yet.", Ink3)
            else -> releases.rows.forEach { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("release"),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Line(r.version, Ink, Design.BODY, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Line(r.date, Ink3, Design.MINI)
                    }
                    Line(r.shownNote, if (r.note == null) Ink3 else Ink, Design.SMALL)
                }
            }
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
            .testTag("person-${person.key}"),
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
        Fact("Key", person.key)
    }

    Line("Role", Ink, Design.H3)
    state.error?.let { Line(it, Bad, Design.SMALL) }
    Role.all.forEach { role ->
        val on = person.role == role
        Choice(role, describe(role), on) {
            if (!on && !state.isChanging(person.key)) onSetRole(person, role)
        }
    }
    if (state.strands(person.key, me)) {
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
