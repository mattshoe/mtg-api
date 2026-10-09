package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Releases
import org.mattshoe.mtg.core.Role
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.Tasks

/**
 * Admin Settings, on the web. Sibling of `AdminScreen`.
 *
 * Two pages: who is here, and one person. Matt: "I need to be able to
 * search users and then tapping one needs to open a user details page
 * where i can assign roles!!!"
 *
 * The list is a search box and a row per account, and a row says who
 * somebody is and nothing else. Everything you can *do* to them is on
 * their own page, where there is room for it — the first version
 * jammed a chip, a warning and a button in beside the name, which was
 * unreadable at two accounts and would not survive a third role.
 */
@Composable
fun AdminPage(
    state: People,
    me: String?,
    person: Person?,
    releases: Releases = Releases(),
    tasks: Tasks = Tasks(),
    onToggleDone: () -> Unit = {},
    /** The New task button, which opens its own page. */
    onNewTask: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onOpen: (Person) -> Unit = {},
    onSetRole: (Person, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    Div(attrs = { classes("wrap") }) {
        if (person != null) {
            PersonPage(state, person, me, onSetRole, onBack)
        } else {
            Everybody(state, onSearch, onOpen)
            TaskList(tasks, onToggleDone, onNewTask)
            ReleaseNotes(releases)
        }
    }
}

@Composable
private fun Everybody(state: People, onSearch: (String) -> Unit, onOpen: (Person) -> Unit) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("Who is here") }
            Span(attrs = { classes("spacer") }) {}
            Span(attrs = { classes("muted", "small") }) {
                Text("${state.rows.size} " + if (state.rows.size == 1) "account" else "accounts")
            }
        }
        Div(attrs = { classes("panel-body") }) {
            Input(InputType.Text, attrs = {
                classes("field")
                placeholder("Search by name, address or role")
                attr("aria-label", "Search accounts")
                value(state.query)
                onInput { onSearch(it.value) }
            })

            when {
                state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                state.error != null ->
                    Div(attrs = { classes("err") }) { Text("Could not load accounts: ${state.error}") }
                state.rows.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No accounts yet.") }
                state.nothingMatched ->
                    Div(attrs = { classes("empty") }) { Text("Nobody matches that.") }
                else -> state.shown.forEach { p -> PersonRow(p, onOpen) }
            }
        }
    }
}

/**
 * Intake requests and where each one is: the active ones, then the
 * finished ones folded behind a toggle, newest first.
 *
 * Matt: "I want the done ones minimized by default but still
 * browsable, ordered by the time which they completed, most recent
 * first". Sibling of Android's `TaskList`.
 */
@Composable
private fun TaskList(tasks: Tasks, onToggleDone: () -> Unit, onNewTask: () -> Unit) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("Tasks") }
            // Matt: "I want to be able to tap a "new task" button".
            Button(attrs = {
                classes("btn", "sm", "primary")
                onClick { onNewTask() }
            }) { Text("New task") }
        }
        Div(attrs = { classes("panel-body") }) {
            when {
                tasks.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                tasks.error != null ->
                    Div(attrs = { classes("err") }) { Text("Could not load tasks: ${tasks.error}") }
                tasks.rows.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No tasks yet.") }
                else -> {
                    if (tasks.active.isEmpty()) Div(attrs = { classes("empty") }) { Text("Nothing in progress.") }
                    tasks.active.forEach { TaskRow(it, "data-task", tasks.now) }
                    Button(attrs = {
                        classes("tasks-toggle")
                        attr("data-tasks-toggle", "")
                        attr("aria-expanded", tasks.showDone.toString())
                        onClick { onToggleDone() }
                    }) {
                        Text((if (tasks.showDone) "▾ " else "▸ ") + "Done (${tasks.done.size})")
                    }
                    if (tasks.showDone) tasks.done.forEach { TaskRow(it, "data-task-done", tasks.now) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, marker: String, now: Long) {
    Div(attrs = {
        classes("task")
        attr(marker, task.ref)
    }) {
        Div(attrs = { classes("task-what") }) {
            Span(attrs = { classes("task-title") }) { Text(task.title) }
            task.took?.let { Span(attrs = { classes("muted", "small") }) { Text(it) } }
            task.elapsed(now)?.let { Span(attrs = { classes("muted", "small") }) { Text(it) } }
        }
        Span(attrs = { classes("tag", "mini") }) { Text(task.status.word) }
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
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-head") }) {
            H2 { Text("Release notes") }
        }
        Div(attrs = { classes("panel-body") }) {
            when {
                releases.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                releases.error != null ->
                    Div(attrs = { classes("err") }) { Text("Could not load release notes: ${releases.error}") }
                releases.rows.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No releases yet.") }
                else -> releases.rows.forEach { r ->
                    Div(attrs = {
                        classes("release")
                        attr("data-release", r.tag)
                    }) {
                        Div(attrs = { classes("release-head") }) {
                            Span(attrs = { classes("release-version") }) { Text(r.version) }
                            Span(attrs = { classes("spacer") }) {}
                            Span(attrs = { classes("muted", "small") }) { Text(r.date) }
                        }
                        Div(attrs = { classes(if (r.note == null) "muted" else "release-note", "small") }) {
                            Text(r.shownNote)
                        }
                    }
                }
            }
        }
    }
}

/** One account: who they are, and a way in. Nothing to press by mistake. */
@Composable
private fun PersonRow(person: Person, onOpen: (Person) -> Unit) {
    Button(attrs = {
        classes("person-row")
        onClick { onOpen(person) }
    }) {
        person.avatar?.takeIf { it.isNotBlank() }
            ?.let { Img(src = it, alt = "", attrs = { classes("app-avatar") }) }
        Div(attrs = { classes("person-who") }) {
            Span { Text(person.shownName) }
            person.address?.let { Span(attrs = { classes("app-who-slug") }) { Text(it) } }
        }
        Span(attrs = { classes("spacer") }) {}
        Span(attrs = { classes("tag", "mini") }) { Text(person.role) }
        Span(attrs = { classes("muted", "chev") }) { Text("›") }
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
    Div(attrs = { classes("page-head") }) {
        Button(attrs = {
            classes("btn", "sm", "ghost")
            onClick { onBack() }
        }) { Text("← Everybody") }
    }

    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("panel-body") }) {
            Div(attrs = { classes("person-head") }) {
                person.avatar?.takeIf { it.isNotBlank() }
                    ?.let { Img(src = it, alt = "", attrs = { classes("person-face") }) }
                Div(attrs = { classes("person-who") }) {
                    Span(attrs = { classes("person-name") }) { Text(person.shownName) }
                    person.address?.let { Span(attrs = { classes("app-who-slug") }) { Text(it) } }
                }
            }

            Div(attrs = { classes("facts") }) {
                Fact("Role", person.role)
                Fact("Key", person.key)
            }
        }
    }

    H3 { Text("Role") }
    state.error?.let { Div(attrs = { classes("err") }) { Text(it) } }
    Div(attrs = { classes("pick") }) {
        Role.all.forEach { role ->
            val on = person.role == role
            val busy = state.isChanging(person.key)
            Button(attrs = {
                classes("opt")
                if (on) classes("on")
                attr("aria-pressed", on.toString())
                if (busy || on) attr("disabled", "")
                onClick { onSetRole(person, role) }
            }) {
                Span(attrs = { classes("opt-mark") }) { if (on) Text("✓") }
                Span(attrs = { classes("opt-text") }) {
                    Span(attrs = { classes("opt-label") }) { Text(role) }
                    Span(attrs = { classes("opt-help") }) { Text(describe(role)) }
                }
            }
        }
    }
    if (state.strands(person.key, me)) {
        // The one change nothing here can undo: `ADMIN_PASSWORD` and a
        // script are the way back. Said, not refused — Matt: "I want to
        // be able to assign and remove roles at will!!!!"
        Div(attrs = { classes("muted", "small") }) {
            Text(
                "You are the only admin. Taking your own role away leaves nobody who can "
                    + "hand it out, and only the server password could undo it.",
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Div(attrs = { classes("fact") }) {
        Span(attrs = { classes("fact-k") }) { Text(label) }
        Span(attrs = { classes("fact-v") }) { Text(value) }
    }
}

private fun describe(role: String): String = when (role) {
    Role.ADMIN -> "Everything: anybody's cards, and handing out roles"
    else -> "Their own collection, and nothing else"
}
